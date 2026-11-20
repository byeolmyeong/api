package com.byeolmyeong.api.report

import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.GenerationQuota
import com.byeolmyeong.api.web.GenerationRateLimitedException
import com.byeolmyeong.api.web.PurchaseRequiredException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 조각마다: 처음 한 번 + 다시 생성 2번(요약 해석 · 리포트와 같다). */
private const val MAX_ATTEMPTS = 3

/**
 * `WRITING` 인데 살아 있다는 표시(`active_at`)가 이만큼(초) 끊기면 멈춘 것으로 본다(서버가 쓰는 도중 꺼진 경우). 옛 리포트와 같다(결정 029 의 1).
 * 표시는 `byeolmyeong.report.heartbeat-seconds`(15초)마다 적으므로 여덟 번을 놓쳐야 한다. 자리를 잡은 뒤 흐른 시간으로는 가리지 않는다.
 */
private const val STALE_SECONDS = 120

/**
 * 자세한 풀이의 한 조각(나 탭 풀이의 영역 하나, 시기 풀이의 9줄 묶음 하나): 요청 하나를 보내 [read] 로 읽고 [problem] 으로 검증한다.
 * 틀리면 같은 요청으로 다시 부른다. [name] 은 로그의 `area` 값이다.
 */
class DetailPart<P : Any>(
	val name: String,
	val request: GenerationRequest,
	val read: (JsonNode) -> P?,
	val problem: (P) -> String?,
)

/** 한 번의 생성: 조각들, 다 모인 조각(순서대로)으로 저장할 본문을 만드는 것(저장할 수 없으면 null), 완성 이벤트를 만드는 것. */
class DetailJob<P : Any>(
	val parts: List<DetailPart<P>>,
	val assemble: (List<P>) -> Any?,
	val done: (UUID) -> Any,
)

/**
 * 자세한 풀이(나 탭 풀이 · 시기 풀이)의 생성 고리 하나(결정 029 — 상태 · 심장박동 · 멈춤 판정 · 그만두기 · 끝내기를 종류마다 두 벌로 만들지 않는다).
 * 요청은 자리만 잡고 바로 돌아오고, 조각들은 뒤에서 리포트의 일꾼(`reportExecutor`)이 한꺼번에 쓴다. 모든 조각이 검증을 통과해야 저장한다.
 * 출생 정보 · 차트 · 프롬프트 · 글을 로그에 남기지 않는다(이벤트 이름 · 조각 이름 · 사유 코드 · 시도 횟수만).
 */
@Service
class DetailReportService(
	private val repository: DetailReportRepository,
	private val generator: TextGenerator,
	@Qualifier("reportExecutor") private val executor: Executor,
	private val events: ApplicationEventPublisher,
	private val quota: GenerationQuota,
	private val entitlement: EntitlementPolicy,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	// 이 서버가 맡아 아직 끝내지 않은 생성(줄에서 기다리는 것 포함, 종류를 가리지 않는다).
	private val live = ConcurrentHashMap.newKeySet<DetailReportRun>()

	/** 이 서버가 맡은 생성이 살아 있다고 행에 적는다. 서버가 꺼지면 더 적히지 않아 그 행은 멈춘 것으로 보인다. */
	@Scheduled(fixedDelayString = "\${byeolmyeong.report.heartbeat-seconds}", timeUnit = TimeUnit.SECONDS)
	fun heartbeat() {
		for (run in live) {
			try {
				repository.beat(run)
			} catch (e: Exception) {
				log.warn("event={}_heartbeat_failed error={}", run.kind.event, e.javaClass.simpleName)
			}
		}
	}

	/** 저장된 풀이와 그 상태(멈춘 `WRITING` 은 `FAILED`). 행이 없으면 null. */
	fun <T : Any> find(ownerId: UUID, kind: DetailReportKind, chartHash: String, bodyType: Class<T>): Pair<ReportStatus, T?>? =
		repository.find(ownerId, kind, chartHash, STALE_SECONDS, bodyType)?.let { stored ->
			val status = if (stored.status == ReportStatus.WRITING && stored.stale) ReportStatus.FAILED else stored.status
			status to stored.body
		}

	/**
	 * 풀이를 만들기 시작한다. 이미 완성됐거나 쓰는 중이면 시작하지 않고 그 상태를 돌려준다.
	 * 새로 시작할 때만 "받을 수 있나"(결정 029 의 4 — 거절이면 402, 행을 만들지 않는다)를 묻고, [prepare](차트 · 조각 만들기)를 자리를 잡기 전에 부른다 —
	 * 계산이 실패해도 `WRITING` 인 채 남는 행이 없다. 자리를 잡은 뒤 생성 한도를 한 번 센다(결정 023 — 조각이 여럿이어도, 다시 생성해도 한 번).
	 */
	fun <P : Any> request(ownerId: UUID, kind: DetailReportKind, chartHash: String, prepare: () -> DetailJob<P>): ReportStatus {
		repository.status(ownerId, kind, chartHash, STALE_SECONDS)?.takeIf { it == ReportStatus.DONE || it == ReportStatus.WRITING }?.let { return it }
		if (!entitlement.allows(ownerId, kind)) throw PurchaseRequiredException()
		val job = prepare()
		val run = repository.claim(ownerId, kind, chartHash, STALE_SECONDS)
			?: return repository.status(ownerId, kind, chartHash, STALE_SECONDS) ?: ReportStatus.WRITING
		// 넘으면 그 자리를 실패로 돌려 쓰는 중으로 남기지 않는다.
		try {
			quota.acquire(ownerId)
		} catch (e: GenerationRateLimitedException) {
			repository.fail(run)
			throw e
		}
		log.info("event={}_started", kind.event)
		// 감사 기록(결정 015): 만들기 시작한 요청만 남긴다. 옛 리포트와 같은 행동 코드에 종류만 붙인다.
		events.publishEvent(AuditEvent(AuditAction.REPORT_REQUESTED, AuditActor.user(ownerId), detail = mapOf("kind" to kind.name)))
		return start(run, job)
	}

	// 조각을 일꾼에게 하나씩 맡긴다. 요청 스레드는 기다리지 않는다.
	private fun <P : Any> start(run: DetailReportRun, job: DetailJob<P>): ReportStatus {
		// 한 조각이 끝내 실패하거나 이 생성이 자리를 잃으면 풀이를 저장할 수 없으므로, 나머지 조각은 다음 시도부터 그만둔다(모델 호출을 아낀다).
		val abandoned = AtomicBoolean(false)
		val attempts = AtomicInteger()
		val parts: List<CompletableFuture<P?>>
		live += run
		try {
			parts = job.parts.map { part -> CompletableFuture.supplyAsync({ write(run, part, abandoned, attempts) }, executor) }
		} catch (e: RejectedExecutionException) {
			abandoned.set(true)
			live -= run
			repository.fail(run)
			log.warn("event={}_failed reason=EXECUTOR_FULL", run.kind.event)
			return ReportStatus.FAILED
		}
		CompletableFuture.allOf(*parts.toTypedArray()).whenComplete { _, _ ->
			finish(run, job, parts.map { runCatching { it.join() }.getOrNull() }, attempts.get())
		}
		return ReportStatus.WRITING
	}

	/**
	 * 이 생성을 그만둬야 하는지. 모델을 부르기 전마다 본다(줄에서 나온 직후, 그리고 답을 받고 다시 부르기 전).
	 * 그만두는 경우: 다른 조각이 끝내 실패했다, 줄이 차서 실패로 끝났다, 행이 지워졌다, 다른 생성이 자리를 잡았다.
	 */
	private fun stopped(run: DetailReportRun, abandoned: AtomicBoolean): Boolean {
		if (abandoned.get()) return true
		if (repository.owns(run)) return false
		abandoned.set(true)
		return true
	}

	/** 한 조각을 쓴다. 검증을 통과한 조각을 돌려주고, 만들지 못했으면 null. */
	private fun <P : Any> write(run: DetailReportRun, part: DetailPart<P>, abandoned: AtomicBoolean, attempts: AtomicInteger): P? {
		val event = run.kind.event
		repeat(MAX_ATTEMPTS) { attempt ->
			if (stopped(run, abandoned)) return null
			attempts.incrementAndGet()
			val written = try {
				generator.generate(part.request)?.let(part.read)
			} catch (e: Exception) {
				// 호출 자체의 실패는 다시 불러도 같다. 예외의 글은 남기지 않는다(사유 코드나 클래스 이름만).
				val reason = (e as? GenerationException)?.reason ?: e.javaClass.simpleName
				log.warn("event={}_area_failed area={} reason={} attempt={}", event, part.name, reason, attempt + 1)
				abandoned.set(true)
				return null
			}
			val problem = if (written == null) "UNUSABLE_RESPONSE" else part.problem(written)
			if (written != null && problem == null) return written
			log.warn("event={}_attempt_rejected area={} reason={} attempt={}", event, part.name, problem, attempt + 1)
		}
		log.warn("event={}_area_failed area={} reason=ATTEMPTS_EXHAUSTED attempt={}", event, part.name, MAX_ATTEMPTS)
		abandoned.set(true)
		return null
	}

	// 일꾼 스레드에서 돈다. 여기서 난 오류는 받을 곳이 없으므로 로그만 남기고, 그 생성의 행을 바로 FAILED 로 돌린다
	// (2분 기다리지 않고 다시 POST 하면 새로 시작할 수 있게). 그것마저 실패하면 살아 있다는 표시가 끊겨 2분 뒤 멈춘 것으로 보인다.
	private fun <P : Any> finish(run: DetailReportRun, job: DetailJob<P>, parts: List<P?>, attempts: Int) {
		val event = run.kind.event
		try {
			val body = if (parts.any { it == null }) null else job.assemble(parts.filterNotNull())
			if (body == null) {
				// 자리를 잃어 그만둔 생성이면 바꿀 행이 없다.
				if (repository.fail(run)) log.warn("event={}_failed attempts={}", event, attempts) else log.info("event={}_discarded attempts={}", event, attempts)
			} else if (repository.complete(run, body)) {
				log.info("event={}_done attempts={}", event, attempts)
				events.publishEvent(job.done(run.ownerId))
			} else {
				// 그 사이 계정이 지워졌거나, 멈춘 줄 알고 다시 시작한 생성이 자리를 잡았다.
				log.info("event={}_discarded attempts={}", event, attempts)
			}
		} catch (e: Exception) {
			log.error("event={}_finish_failed error={}", event, e.javaClass.simpleName)
			runCatching { repository.fail(run) }
		} finally {
			live -= run
		}
	}
}
