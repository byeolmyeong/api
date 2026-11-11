package com.byeolmyeong.api.report

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 영역마다: 처음 한 번 + 다시 생성 2번(요약 해석과 같다). */
private const val MAX_ATTEMPTS = 3

/** `WRITING` 인 채 이만큼 지나면 멈춘 것으로 본다(서버가 쓰는 도중 재시작된 경우). */
private const val STALE_MINUTES = 10

/**
 * 리포트의 글을 쓰는 뒤쪽 일꾼. 스레드 수와 대기 줄이 정해져 있어 요청이 몰려도 스레드가 늘지 않는다.
 * 일 하나 = 한 영역의 글(모델 호출 1~3번). 줄이 차면 그 리포트는 `FAILED` 가 된다.
 */
@Configuration
class ReportExecutorConfig {

	@Bean
	fun reportExecutor(
		@Value("\${byeolmyeong.report.worker-threads}") threads: Int,
		@Value("\${byeolmyeong.report.queue-capacity}") queueCapacity: Int,
	): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = threads
		maxPoolSize = threads
		this.queueCapacity = queueCapacity
		setThreadNamePrefix("report-")
	}
}

/**
 * 심층 리포트. 요청은 자리만 잡고 바로 돌아오고, 글은 뒤에서 영역마다 따로 쓴다.
 * 네 영역이 모두 검증을 통과해야 저장한다. 출생 정보·차트·프롬프트·글을 로그에 남기지 않는다.
 */
@Service
class ReportService(
	private val profiles: BirthProfileService,
	private val repository: ReportRepository,
	private val generator: TextGenerator,
	@Qualifier("reportExecutor") private val executor: Executor,
	private val events: ApplicationEventPublisher,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 지금 차트의 리포트 상태. 프로필이 없거나 만든 적이 없으면 `NONE`. */
	fun current(ownerId: UUID): ReportStatusResponse {
		val birth = profiles.chartInput(ownerId) ?: return ReportStatusResponse(ReportStatus.NONE)
		val stored = repository.find(ownerId, birth.hash, STALE_MINUTES) ?: return ReportStatusResponse(ReportStatus.NONE)
		return ReportStatusResponse(statusOf(stored), stored.report)
	}

	/** 지금 차트의 리포트를 만들기 시작한다. 이미 완성됐거나 쓰는 중이면 시작하지 않고 그 상태를 돌려준다. */
	fun request(ownerId: UUID): ReportStatus {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val run = repository.claim(ownerId, birth.hash, STALE_MINUTES)
			?: return repository.find(ownerId, birth.hash, STALE_MINUTES)?.let(::statusOf) ?: ReportStatus.WRITING
		log.info("event=report_started")
		return start(run, birth.chart())
	}

	private fun statusOf(stored: StoredReport): ReportStatus =
		if (stored.status == ReportStatus.WRITING && stored.stale) ReportStatus.FAILED else stored.status

	// 네 영역을 일꾼에게 하나씩 맡긴다. 요청 스레드는 기다리지 않는다.
	private fun start(run: ReportRun, chart: Chart): ReportStatus {
		// 한 영역이 끝내 실패하면 리포트를 저장할 수 없으므로, 나머지 영역은 다음 시도부터 그만둔다(모델 호출을 아낀다).
		val abandoned = AtomicBoolean(false)
		val attempts = AtomicInteger()
		val areas = try {
			ReportArea.entries.map { area ->
				CompletableFuture.supplyAsync({ writeArea(area, chart, abandoned, attempts) }, executor)
			}
		} catch (e: RejectedExecutionException) {
			abandoned.set(true)
			repository.fail(run)
			log.warn("event=report_failed reason=EXECUTOR_FULL")
			return ReportStatus.FAILED
		}
		CompletableFuture.allOf(*areas.toTypedArray()).whenComplete { _, _ ->
			finish(run, areas.map { runCatching { it.join() }.getOrNull() }, attempts.get())
		}
		return ReportStatus.WRITING
	}

	/** 한 영역을 쓴다. 검증을 통과한 글을 돌려주고, 만들지 못했으면 null. */
	private fun writeArea(area: ReportArea, chart: Chart, abandoned: AtomicBoolean, attempts: AtomicInteger): ReportSection? {
		val request = reportAreaRequest(area, chart)
		repeat(MAX_ATTEMPTS) { attempt ->
			if (abandoned.get()) return null
			attempts.incrementAndGet()
			val section = try {
				generator.generate(request)?.let { parseReportSection(area, it) }
			} catch (e: Exception) {
				// 호출 자체의 실패는 다시 불러도 같다. 예외의 글은 남기지 않는다(사유 코드나 클래스 이름만).
				val reason = (e as? GenerationException)?.reason ?: e.javaClass.simpleName
				log.warn("event=report_area_failed area={} reason={} attempt={}", area, reason, attempt + 1)
				abandoned.set(true)
				return null
			}
			val problem = if (section == null) "UNUSABLE_RESPONSE" else sectionProblem(section, chart)
			if (section != null && problem == null) return section
			log.warn("event=report_attempt_rejected area={} reason={} attempt={}", area, problem, attempt + 1)
		}
		log.warn("event=report_area_failed area={} reason=ATTEMPTS_EXHAUSTED attempt={}", area, MAX_ATTEMPTS)
		abandoned.set(true)
		return null
	}

	// 일꾼 스레드에서 돈다. 여기서 난 오류는 받을 곳이 없으므로 삼키고 로그만 남긴다(행은 WRITING 인 채 남아 10분 뒤 멈춘 것으로 본다).
	private fun finish(run: ReportRun, sections: List<ReportSection?>, attempts: Int) {
		try {
			val report = Report(sections.filterNotNull())
			if (!isComplete(report)) {
				repository.fail(run)
				log.warn("event=report_failed attempts={}", attempts)
			} else if (repository.complete(run, report)) {
				log.info("event=report_done attempts={}", attempts)
				events.publishEvent(ReportDone(run.ownerId))
			} else {
				// 그 사이 계정이 지워졌거나, 멈춘 줄 알고 다시 시작한 생성이 자리를 잡았다.
				log.info("event=report_discarded attempts={}", attempts)
			}
		} catch (e: Exception) {
			log.error("event=report_finish_failed error={}", e.javaClass.simpleName)
		}
	}
}
