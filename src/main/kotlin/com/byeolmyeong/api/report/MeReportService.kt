package com.byeolmyeong.api.report

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.BasisCandidate
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.generation.basisCandidates
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.GenerationQuota
import com.byeolmyeong.api.web.GenerationRateLimitedException
import com.byeolmyeong.api.web.NotFoundException
import com.byeolmyeong.api.web.PurchaseRequiredException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 영역마다: 처음 한 번 + 다시 생성 2번(요약 해석 · 리포트와 같다). */
private const val MAX_ATTEMPTS = 3

/** `WRITING` 인데 살아 있다는 표시가 이만큼(초) 끊기면 멈춘 것으로 본다. 옛 리포트와 같다(결정 029 의 1). */
private const val STALE_SECONDS = 120

/**
 * 나 탭 풀이(결정 029 · 030). 요청은 자리만 잡고 바로 돌아오고, 글은 뒤에서 영역 다섯을 한꺼번에 따로 쓴다(리포트의 일꾼을 같이 쓴다).
 * 다섯 영역이 모두 검증을 통과해야 저장한다. 출생 정보 · 차트 · 프롬프트 · 글을 로그에 남기지 않는다.
 */
@Service
class MeReportService(
	private val profiles: BirthProfileService,
	private val repository: DetailReportRepository,
	private val generator: TextGenerator,
	@Qualifier("reportExecutor") private val executor: Executor,
	private val events: ApplicationEventPublisher,
	private val quota: GenerationQuota,
	private val entitlement: EntitlementPolicy,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	// 이 서버가 맡아 아직 끝내지 않은 생성(줄에서 기다리는 것 포함).
	private val live = ConcurrentHashMap.newKeySet<DetailReportRun>()

	/** 이 서버가 맡은 생성이 살아 있다고 행에 적는다. 서버가 꺼지면 더 적히지 않아 그 행은 멈춘 것으로 보인다. */
	@Scheduled(fixedDelayString = "\${byeolmyeong.report.heartbeat-seconds}", timeUnit = TimeUnit.SECONDS)
	fun heartbeat() {
		for (run in live) {
			try {
				repository.beat(run)
			} catch (e: Exception) {
				log.warn("event=me_report_heartbeat_failed error={}", e.javaClass.simpleName)
			}
		}
	}

	/** 지금 차트의 나 탭 풀이 상태. 프로필이 없거나 만든 적이 없으면 `NONE`. */
	fun current(ownerId: UUID): MeReportStatusResponse {
		val birth = profiles.chartInput(ownerId) ?: return MeReportStatusResponse(ReportStatus.NONE)
		val stored = find(ownerId, birth.hash) ?: return MeReportStatusResponse(ReportStatus.NONE)
		return MeReportStatusResponse(statusOf(stored), stored.body)
	}

	/**
	 * 지금 차트의 나 탭 풀이를 만들기 시작한다. 이미 완성됐거나 쓰는 중이면 시작하지 않고 그 상태를 돌려준다.
	 * 새로 시작할 때만 "받을 수 있나"(결정 029 의 4)를 묻고 — 거절이면 402, 행을 만들지 않는다 — 자리를 잡은 뒤 생성 한도를 한 번 센다(결정 023).
	 */
	fun request(ownerId: UUID): ReportStatus {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		find(ownerId, birth.hash)?.let(::statusOf)?.takeIf { it == ReportStatus.DONE || it == ReportStatus.WRITING }?.let { return it }
		if (!entitlement.allows(ownerId, DetailReportKind.ME)) throw PurchaseRequiredException()
		// 차트는 새로 시작할 때만, 자리를 잡기 전에 계산한다: 완성 · 쓰는 중이면 계산하지 않고, 계산이 실패해도 WRITING 인 채 남는 행이 없다.
		val chart = birth.chart()
		val run = repository.claim(ownerId, DetailReportKind.ME, birth.hash, STALE_SECONDS)
			?: return find(ownerId, birth.hash)?.let(::statusOf) ?: ReportStatus.WRITING
		// 자리를 잡은 뒤 센다(결정 023). 넘으면 그 자리를 실패로 돌려 쓰는 중으로 남기지 않는다.
		try {
			quota.acquire(ownerId)
		} catch (e: GenerationRateLimitedException) {
			repository.fail(run)
			throw e
		}
		log.info("event=me_report_started")
		// 감사 기록(결정 015): 만들기 시작한 요청만 남긴다. 옛 리포트와 같은 행동 코드에 종류만 붙인다.
		events.publishEvent(AuditEvent(AuditAction.REPORT_REQUESTED, AuditActor.user(ownerId), detail = mapOf("kind" to DetailReportKind.ME.name)))
		return start(run, chart)
	}

	private fun find(ownerId: UUID, chartHash: String): StoredDetailReport<MeReport>? =
		repository.find(ownerId, DetailReportKind.ME, chartHash, STALE_SECONDS, MeReport::class.java)

	private fun statusOf(stored: StoredDetailReport<*>): ReportStatus =
		if (stored.status == ReportStatus.WRITING && stored.stale) ReportStatus.FAILED else stored.status

	// 다섯 영역을 일꾼에게 하나씩 맡긴다. 요청 스레드는 기다리지 않는다.
	private fun start(run: DetailReportRun, chart: Chart): ReportStatus {
		val candidates = basisCandidates(chart)
		// 한 영역이 끝내 실패하거나 이 생성이 자리를 잃으면 풀이를 저장할 수 없으므로, 나머지 영역은 다음 시도부터 그만둔다(모델 호출을 아낀다).
		val abandoned = AtomicBoolean(false)
		val attempts = AtomicInteger()
		val areas: List<CompletableFuture<MeArea?>>
		live += run
		try {
			areas = MeReportArea.entries.map { area ->
				CompletableFuture.supplyAsync({ writeArea(run, area, chart, candidates, abandoned, attempts) }, executor)
			}
		} catch (e: RejectedExecutionException) {
			abandoned.set(true)
			live -= run
			repository.fail(run)
			log.warn("event=me_report_failed reason=EXECUTOR_FULL")
			return ReportStatus.FAILED
		}
		CompletableFuture.allOf(*areas.toTypedArray()).whenComplete { _, _ ->
			finish(run, areas.map { runCatching { it.join() }.getOrNull() }, attempts.get())
		}
		return ReportStatus.WRITING
	}

	/**
	 * 이 생성을 그만둬야 하는지. 모델을 부르기 전마다 본다(줄에서 나온 직후, 그리고 답을 받고 다시 부르기 전).
	 * 그만두는 경우: 다른 영역이 끝내 실패했다, 줄이 차서 실패로 끝났다, 행이 지워졌다, 다른 생성이 자리를 잡았다.
	 */
	private fun stopped(run: DetailReportRun, abandoned: AtomicBoolean): Boolean {
		if (abandoned.get()) return true
		if (repository.owns(run)) return false
		abandoned.set(true)
		return true
	}

	/** 한 영역을 쓴다: 응답을 읽고 검증해, 틀리면 같은 요청으로 다시 부른다. 검증을 통과한 영역을 돌려주고, 만들지 못했으면 null. */
	private fun writeArea(
		run: DetailReportRun,
		area: MeReportArea,
		chart: Chart,
		candidates: List<BasisCandidate>,
		abandoned: AtomicBoolean,
		attempts: AtomicInteger,
	): MeArea? {
		val request = meAreaRequest(area, chart, candidates)
		val candidateIds = candidates.map { it.id }.toSet()
		repeat(MAX_ATTEMPTS) { attempt ->
			if (stopped(run, abandoned)) return null
			attempts.incrementAndGet()
			val draft = try {
				generator.generate(request)?.let { parseMeArea(area, it) }
			} catch (e: Exception) {
				// 호출 자체의 실패는 다시 불러도 같다. 예외의 글은 남기지 않는다(사유 코드나 클래스 이름만).
				val reason = (e as? GenerationException)?.reason ?: e.javaClass.simpleName
				log.warn("event=me_report_area_failed area={} reason={} attempt={}", area, reason, attempt + 1)
				abandoned.set(true)
				return null
			}
			val problem = if (draft == null) "UNUSABLE_RESPONSE" else meAreaProblem(draft, candidateIds)
			if (draft != null && problem == null) return meArea(draft, chart, candidates)
			log.warn("event=me_report_attempt_rejected area={} reason={} attempt={}", area, problem, attempt + 1)
		}
		log.warn("event=me_report_area_failed area={} reason=ATTEMPTS_EXHAUSTED attempt={}", area, MAX_ATTEMPTS)
		abandoned.set(true)
		return null
	}

	// 일꾼 스레드에서 돈다. 여기서 난 오류는 받을 곳이 없으므로 삼키고 로그만 남긴다(행은 WRITING 인 채 남고, 살아 있다는 표시가 끊겨 멈춘 것으로 보인다).
	private fun finish(run: DetailReportRun, areas: List<MeArea?>, attempts: Int) {
		try {
			val report = MeReport(areas.filterNotNull())
			if (!isComplete(report)) {
				// 자리를 잃어 그만둔 생성이면 바꿀 행이 없다.
				if (repository.fail(run)) log.warn("event=me_report_failed attempts={}", attempts) else log.info("event=me_report_discarded attempts={}", attempts)
			} else if (repository.complete(run, report)) {
				log.info("event=me_report_done attempts={}", attempts)
				events.publishEvent(MeReportDone(run.ownerId))
			} else {
				// 그 사이 계정이 지워졌거나, 멈춘 줄 알고 다시 시작한 생성이 자리를 잡았다.
				log.info("event=me_report_discarded attempts={}", attempts)
			}
		} catch (e: Exception) {
			log.error("event=me_report_finish_failed error={}", e.javaClass.simpleName)
		} finally {
			live -= run
		}
	}
}
