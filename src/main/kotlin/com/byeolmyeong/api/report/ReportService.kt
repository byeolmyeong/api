package com.byeolmyeong.api.report

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.ChartInput
import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.share.NextTurnCardRequested
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.GenerationQuota
import com.byeolmyeong.api.web.GenerationRateLimitedException
import com.byeolmyeong.api.web.NotFoundException
import com.byeolmyeong.api.web.PurchaseRequiredException
import com.byeolmyeong.api.web.UpdateRequiredException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 영역마다(시기 글도): 처음 한 번 + 다시 생성 2번(요약 해석과 같다). */
private const val MAX_ATTEMPTS = 3

/**
 * `WRITING` 인데 살아 있다는 표시(`active_at`)가 이만큼 끊기면 멈춘 것으로 본다(서버가 쓰는 도중 꺼진 경우).
 * 표시는 `byeolmyeong.report.heartbeat-seconds`(15초)마다 적으므로 여덟 번을 놓쳐야 한다.
 * 자리를 잡은 뒤 흐른 시간으로는 가리지 않는다: 줄에서 오래 기다리는 것은 멈춘 것이 아니다.
 */
private const val STALE_SECONDS = 120

/**
 * 리포트의 글을 쓰는 뒤쪽 일꾼. 스레드 수와 대기 줄이 정해져 있어 요청이 몰려도 스레드가 늘지 않는다.
 * 일 하나 = 한 영역의 글 또는 시기(타임라인 계산 + 글), 모델 호출 1~3번. 줄이 차면 그 리포트는 `FAILED` 가 된다.
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
		// 서버를 닫을 때(배포에서 옛 색을 내릴 때, 결정 046) 맡은 일을 끊지 않는다(닫기 시작할 때 먼저 멈추거나 쉬지 않는다).
		// 기다리는 것은 ReportExecutorDrain 한 곳이다. 여기서 또 기다리면 그 시간을 넘긴 일을 박동 없이 한 번 더 기다린다.
		setWaitForTasksToCompleteOnShutdown(true)
	}

	@Bean
	fun reportExecutorDrain(
		@Qualifier("reportExecutor") executor: ThreadPoolTaskExecutor,
		@Value("\${byeolmyeong.report.shutdown-wait-seconds}") waitSeconds: Long,
	) = ReportExecutorDrain(executor, waitSeconds)
}

/**
 * 서버를 닫을 때 일꾼이 맡은 일을 다 쓸 때까지 여기서 기다린다(결정 046 의 4). 웹 서버가 새 요청을 그만 받고 받던 요청을 끝낸 다음이다.
 * 일꾼이 스스로 기다리는 때(빈을 없앨 때)에는 서비스 빈과 그 박동 일정이 이미 없어져, 기다리는 동안 행의 살아 있다는 표시가 끊긴다.
 * 그러면 2분 뒤 새 서버가 그 생성을 멈춘 것으로 보고 다시 시작할 수 있다. 그래서 빈을 없애기 전, 박동이 아직 도는 이 단계에서 기다린다.
 * 박동 일정이 닫기 시작할 때 먼저 멈추지 않게 하는 설정은 `spring.task.scheduling.shutdown.await-termination`(application.properties).
 */
class ReportExecutorDrain(private val executor: ThreadPoolTaskExecutor, private val waitSeconds: Long) : SmartLifecycle {
	private val log = LoggerFactory.getLogger(javaClass)

	@Volatile
	private var running = false

	override fun start() {
		running = true
	}

	override fun isRunning(): Boolean = running

	// 단계가 높은 것부터 멈춘다: 웹 서버의 차분한 종료(새 요청을 그만 받고 받던 요청을 끝냄) 바로 다음, 웹 서버를 닫기 전.
	override fun getPhase(): Int = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1

	override fun stop() {
		running = false
		log.info("event=report_drain_started active={} queued={}", executor.activeCount, executor.queueSize)
		// 새 일은 받지 않고, 맡은 일이 줄의 것까지 끝날 때까지(최대 shutdown-wait-seconds) 기다린다.
		// 못 끝낸 일은 서버가 꺼지며 멈추고, 2분 뒤 FAILED 로 보여 사람이 다시 만든다.
		val pool = executor.threadPoolExecutor
		pool.shutdown()
		if (pool.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
			log.info("event=report_drain_done")
		} else {
			log.warn("event=report_drain_timeout active={} queued={}", executor.activeCount, executor.queueSize)
		}
	}
}

/**
 * 심층 리포트. 요청은 자리만 잡고 바로 돌아오고, 글은 뒤에서 영역마다 따로 쓴다. 시기(마디·구간)도 뒤에서 계산한다.
 * 네 영역과 시기 글이 모두 검증을 통과해야 저장한다. 출생 정보·차트·프롬프트·글을 로그에 남기지 않는다.
 */
@Service
class ReportService(
	private val profiles: BirthProfileService,
	private val repository: ReportRepository,
	private val generator: TextGenerator,
	@Qualifier("reportExecutor") private val executor: Executor,
	private val events: ApplicationEventPublisher,
	private val quota: GenerationQuota,
	private val entitlement: EntitlementPolicy,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	// 이 서버가 맡아 아직 끝내지 않은 생성(줄에서 기다리는 것 포함).
	private val live = ConcurrentHashMap.newKeySet<ReportRun>()

	/** 이 서버가 맡은 생성이 살아 있다고 행에 적는다. 서버가 꺼지면 더 적히지 않아 그 행은 멈춘 것으로 보인다. */
	@Scheduled(fixedDelayString = "\${byeolmyeong.report.heartbeat-seconds}", timeUnit = TimeUnit.SECONDS)
	fun heartbeat() {
		for (run in live) {
			try {
				repository.beat(run)
			} catch (e: Exception) {
				log.warn("event=report_heartbeat_failed error={}", e.javaClass.simpleName)
			}
		}
	}

	/** 지금 차트의 리포트 상태. 프로필이 없거나 만든 적이 없으면 `NONE`. */
	fun current(ownerId: UUID): ReportStatusResponse {
		val birth = profiles.chartInput(ownerId) ?: return ReportStatusResponse(ReportStatus.NONE)
		val stored = repository.find(ownerId, birth.hash, STALE_SECONDS) ?: return ReportStatusResponse(ReportStatus.NONE)
		// "지금 나의 구간" 은 저장하지 않는다. 읽을 때마다 오늘 날짜로 가린다.
		val report = stored.report?.let { it.copy(timing = it.timing.copy(current = timingNow(it.timing, LocalDate.now(KOREA_ZONE)))) }
		return ReportStatusResponse(statusOf(stored), report)
	}

	/** 공유 카드에 실을 다음 변곡점을 묻는 이벤트(결정 012). 그 소유자의 완성된 리포트에 오늘 뒤의 마디가 있을 때만 답한다. 본문은 주지 않는다. */
	@EventListener
	fun on(event: NextTurnCardRequested) {
		val next = current(event.ownerId).report?.timing?.current?.nextMilestone ?: return
		event.date = next.date
		event.theme = next.title
	}

	/**
	 * 지금 차트의 리포트를 만들기 시작한다. 이미 완성됐거나 쓰는 중이면 시작하지 않고 그 상태를 돌려준다.
	 * 새로 시작할 때만 자세한 풀이와 같은 판정을 거친다(결정 056 의 12 — 옛 앱 426 · 상품이 없으면 402 · 묻지 못하면 503, 행을 만들지 않는다).
	 * 이 경로의 받기는 1.0.0 수령자로 치지 않는다(앱이 부르지 않는 옛 경로 — 계획 S⑤ 정리에서 뺀다).
	 */
	fun request(ownerId: UUID, appVersion: String?): ReportStatus {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		repository.find(ownerId, birth.hash, STALE_SECONDS)?.let(::statusOf)?.takeIf { it == ReportStatus.DONE || it == ReportStatus.WRITING }?.let { return it }
		if (entitlement.requiresUpdate(appVersion)) throw UpdateRequiredException()
		if (!entitlement.hasDetail(ownerId)) throw PurchaseRequiredException()
		// 차트는 자리를 잡기 전에 계산한다: 계산이 실패해도 WRITING 인 채 남는 행이 없다.
		val chart = birth.chart()
		val run = repository.claim(ownerId, birth.hash, STALE_SECONDS)
			?: return repository.find(ownerId, birth.hash, STALE_SECONDS)?.let(::statusOf) ?: ReportStatus.WRITING
		// 자리를 잡은 뒤 센다(결정 023). 넘으면 그 자리를 실패로 돌려 쓰는 중으로 남기지 않는다.
		try {
			quota.acquire(ownerId)
		} catch (e: GenerationRateLimitedException) {
			repository.fail(run)
			throw e
		}
		log.info("event=report_started")
		// 감사 기록(결정 015): 만들기 시작한 요청만 남긴다. 뒤에서 끝난 결과는 리포트 행의 상태에 있다.
		events.publishEvent(AuditEvent(AuditAction.REPORT_REQUESTED, AuditActor.user(ownerId)))
		return start(run, birth, chart)
	}

	private fun statusOf(stored: StoredReport): ReportStatus =
		if (stored.status == ReportStatus.WRITING && stored.stale) ReportStatus.FAILED else stored.status

	// 네 영역과 시기를 일꾼에게 하나씩 맡긴다. 요청 스레드는 기다리지 않는다.
	private fun start(run: ReportRun, birth: ChartInput, chart: Chart): ReportStatus {
		// 한 영역이 끝내 실패하거나 이 생성이 자리를 잃으면 리포트를 저장할 수 없으므로, 나머지 영역은 다음 시도부터 그만둔다(모델 호출을 아낀다).
		val abandoned = AtomicBoolean(false)
		val attempts = AtomicInteger()
		val areas: List<CompletableFuture<ReportSection?>>
		val timing: CompletableFuture<ReportTiming?>
		live += run
		try {
			areas = ReportArea.entries.map { area ->
				CompletableFuture.supplyAsync({ writeArea(run, area, chart, abandoned, attempts) }, executor)
			}
			timing = CompletableFuture.supplyAsync({ writeTiming(run, birth, chart, abandoned, attempts) }, executor)
		} catch (e: RejectedExecutionException) {
			abandoned.set(true)
			live -= run
			repository.fail(run)
			log.warn("event=report_failed reason=EXECUTOR_FULL")
			return ReportStatus.FAILED
		}
		CompletableFuture.allOf(timing, *areas.toTypedArray()).whenComplete { _, _ ->
			finish(run, areas.map { runCatching { it.join() }.getOrNull() }, runCatching { timing.join() }.getOrNull(), attempts.get())
		}
		return ReportStatus.WRITING
	}

	/** 한 영역을 쓴다. 검증을 통과한 글을 돌려주고, 만들지 못했으면 null. */
	private fun writeArea(run: ReportRun, area: ReportArea, chart: Chart, abandoned: AtomicBoolean, attempts: AtomicInteger): ReportSection? =
		write(run, area.name, reportAreaRequest(area, chart), abandoned, attempts, { parseReportSection(area, it) }) { sectionProblem(it, chart) }

	/**
	 * 시기를 계산하고(약 0.3초, 그래서 요청 스레드가 아니라 여기서 한다) 다가오는 줄에 모델의 글을 붙인다.
	 * 해와 기간은 서버가 계산한 값만 쓴다. 모델이 옮겨 적은 해가 다르면 그 응답을 버린다.
	 */
	private fun writeTiming(run: ReportRun, birth: ChartInput, chart: Chart, abandoned: AtomicBoolean, attempts: AtomicInteger): ReportTiming? {
		if (stopped(run, abandoned)) return null
		val draft = computeTiming(chart, birth.utc)
		val items = timingItemsForModel(draft, LocalDate.now(KOREA_ZONE))
		val texts = write(run, "TIMING", reportTimingRequest(chart, items), abandoned, attempts, ::parseTimingTexts) { timingTextProblem(it, items) }
		return texts?.let(draft.timing::withTexts)
	}

	/**
	 * 이 생성을 그만둬야 하는지. 모델을 부르기 전마다 본다(줄에서 나온 직후, 그리고 답을 받고 다시 부르기 전).
	 * 그만두는 경우: 다른 영역이 끝내 실패했다, 줄이 차서 실패로 끝났다, 행이 지워졌다, 다른 생성이 자리를 잡았다.
	 */
	private fun stopped(run: ReportRun, abandoned: AtomicBoolean): Boolean {
		if (abandoned.get()) return true
		if (repository.owns(run)) return false
		abandoned.set(true)
		return true
	}

	// 한 영역(또는 시기)의 글을 받는다: 응답을 [read] 로 읽고 [problemOf] 로 검증해, 틀리면 같은 요청으로 다시 부른다.
	private fun <T : Any> write(
		run: ReportRun,
		area: String,
		request: GenerationRequest,
		abandoned: AtomicBoolean,
		attempts: AtomicInteger,
		read: (JsonNode) -> T?,
		problemOf: (T) -> String?,
	): T? {
		repeat(MAX_ATTEMPTS) { attempt ->
			if (stopped(run, abandoned)) return null
			attempts.incrementAndGet()
			val section = try {
				generator.generate(request)?.let(read)
			} catch (e: Exception) {
				// 호출 자체의 실패는 다시 불러도 같다. 예외의 글은 남기지 않는다(사유 코드나 클래스 이름만).
				val reason = (e as? GenerationException)?.reason ?: e.javaClass.simpleName
				log.warn("event=report_area_failed area={} reason={} attempt={}", area, reason, attempt + 1)
				abandoned.set(true)
				return null
			}
			val problem = if (section == null) "UNUSABLE_RESPONSE" else problemOf(section)
			if (section != null && problem == null) return section
			log.warn("event=report_attempt_rejected area={} reason={} attempt={}", area, problem, attempt + 1)
		}
		log.warn("event=report_area_failed area={} reason=ATTEMPTS_EXHAUSTED attempt={}", area, MAX_ATTEMPTS)
		abandoned.set(true)
		return null
	}

	// 일꾼 스레드에서 돈다. 여기서 난 오류는 받을 곳이 없으므로 삼키고 로그만 남긴다(행은 WRITING 인 채 남고, 살아 있다는 표시가 끊겨 멈춘 것으로 보인다).
	private fun finish(run: ReportRun, sections: List<ReportSection?>, timing: ReportTiming?, attempts: Int) {
		try {
			val report = timing?.let { Report(sections.filterNotNull(), it) }
			if (report == null || !isComplete(report)) {
				// 자리를 잃어 그만둔 생성이면 바꿀 행이 없다.
				if (repository.fail(run)) log.warn("event=report_failed attempts={}", attempts) else log.info("event=report_discarded attempts={}", attempts)
			} else if (repository.complete(run, report)) {
				log.info("event=report_done attempts={}", attempts)
				events.publishEvent(ReportDone(run.ownerId))
			} else {
				// 그 사이 계정이 지워졌거나, 멈춘 줄 알고 다시 시작한 생성이 자리를 잡았다.
				log.info("event=report_discarded attempts={}", attempts)
			}
		} catch (e: Exception) {
			log.error("event=report_finish_failed error={}", e.javaClass.simpleName)
		} finally {
			live -= run
		}
	}
}
