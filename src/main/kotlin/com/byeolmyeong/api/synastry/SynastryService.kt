package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.birth.BirthProfile
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.ChartInput
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.scoreOfSum
import com.byeolmyeong.api.chart.synastryAspects
import com.byeolmyeong.api.chart.synastrySum
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.SynastryFacts
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.profile.BirthProfileChanged
import com.byeolmyeong.api.share.RankingCardRequested
import com.byeolmyeong.api.share.ScoreCardRequested
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.GenerationQuota
import com.byeolmyeong.api.web.GenerationRateLimitedException
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 처음 한 번 + 다시 생성 2번(요약 해석과 같다). */
private const val MAX_ATTEMPTS = 3

/**
 * `WRITING` 인데 살아 있다는 표시(`active_at`)가 이만큼 끊기면 멈춘 것으로 본다(서버가 쓰는 도중 꺼진 경우. 심층 리포트와 같다).
 * 표시는 `byeolmyeong.report.heartbeat-seconds`(15초)마다 적으므로 여덟 번을 놓쳐야 한다.
 * 자리를 잡은 뒤 흐른 시간으로는 가리지 않는다: 줄에서 오래 기다리는 것은 멈춘 것이 아니다.
 */
internal const val STALE_SECONDS = 120

/** 한 번의 생성이 받아 둔 글: 요약(실패면 null)과 네 영역(못 쓴 영역은 null). */
private class Written(val summary: SynastrySummary?, val sections: List<SynastryReportSection?>)

/**
 * 궁합(직접 입력). 점수는 `chart` 가 계산하고 모델은 글만 쓴다. 검증을 통과한 글만 저장한다.
 * 한 번에 만든다(결정 053 의 3): 행을 `WRITING` 으로 넣고 바로 답한 뒤, 뒤에서 요약을 쓰고 그다음 네 영역을 동시에 써서 모두 통과하면 `DONE`.
 * 상대의 출생 정보는 그 궁합 행에만 저장하고(결정 053 의 2) 로그 · 감사 기록에 남기지 않는다. 행성 위치는 저장하지 않는다.
 * 글은 심층 리포트와 같은 뒤쪽 일꾼(`reportExecutor`)이 쓴다: 동시에 나가는 모델 호출 수의 상한을 하나로 둔다.
 */
@Service
class SynastryService(
	private val profiles: BirthProfileService,
	private val repository: SynastryRepository,
	private val generator: TextGenerator,
	@Qualifier("reportExecutor") private val executor: Executor,
	private val events: ApplicationEventPublisher,
	private val quota: GenerationQuota,
	@Value("\${byeolmyeong.synastry.daily-limit}") private val dailyLimit: Int,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	// 이 서버가 맡아 아직 끝내지 않은 생성(줄에서 기다리는 것 포함).
	private val live = ConcurrentHashMap.newKeySet<SynastryRun>()

	/** 이 서버가 맡은 생성이 살아 있다고 행에 적는다. 서버가 꺼지면 더 적히지 않아 그 행은 멈춘 것으로 보인다. */
	@Scheduled(fixedDelayString = "\${byeolmyeong.report.heartbeat-seconds}", timeUnit = TimeUnit.SECONDS)
	fun heartbeat() {
		for (run in live) {
			try {
				repository.beat(run)
			} catch (e: Exception) {
				log.warn("event=synastry_report_heartbeat_failed error={}", e.javaClass.simpleName)
			}
		}
	}

	/** 나(저장된 프로필)와 [partner](요청에 적힌 상대)의 궁합을 계산해 `WRITING` 으로 넣고, 글은 뒤에서 쓴다. */
	fun create(ownerId: UUID, name: String, relation: Relation, partner: BirthProfile): SynastryStarted {
		val me = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		// 하루 안전 한도(결정 053 의 6): 그날 만든 궁합 수. 보통 사람은 닿지 않는다. 동시에 보낸 요청은 한두 개 넘을 수 있다(잠그지 않는다).
		if (repository.createdToday(ownerId) >= dailyLimit) {
			log.info("event=synastry_daily_limited")
			throw GenerationRateLimitedException()
		}
		// 모델을 부르기 전에 센다(결정 023).
		quota.acquire(ownerId)
		val facts = facts(me, profiles.chartInput(partner), relation)
		val run = repository.insert(NewSynastry(ownerId, name, relation, facts.score, me.hash, partner))
		log.info("event=synastry_created relation={}", relation)
		// 감사 기록(결정 015 · 053 의 9): 이름과 출생 정보는 남기지 않는다.
		events.publishEvent(AuditEvent(AuditAction.SYNASTRY_CREATED, AuditActor.user(ownerId), "SYNASTRY", run.id.toString(), detail = mapOf("relation" to relation.name)))
		return SynastryStarted(run.id, start(run, facts, me.hash))
	}

	/**
	 * 실패한(또는 멈춘) 궁합을 같은 행에서 처음부터 다시 쓴다(결정 053 의 3). 지금의 내 차트와 저장된 상대 출생 정보로 점수도 다시 계산한다.
	 * 다시 쓸 수 없는 상태(완성 · 쓰는 중 · 상대 출생 정보가 없는 옛 행)면 그 상태를 준다.
	 */
	fun retry(ownerId: UUID, id: UUID): SynastryStarted {
		val stored = find(ownerId, id)
		val status = stored.synastry.status
		val partner = stored.partner
		val relation = stored.synastry.relation
		if (status != SynastryStatus.FAILED || partner == null || relation == null) return SynastryStarted(id, status)
		val me = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		quota.acquire(ownerId)
		val facts = facts(me, profiles.chartInput(partner), relation)
		// 그 사이 다른 요청이 자리를 잡았으면 그 상태를 준다.
		val run = repository.claim(ownerId, id, facts.score, me.hash, STALE_SECONDS) ?: return SynastryStarted(id, find(ownerId, id).synastry.status)
		log.info("event=synastry_report_started")
		events.publishEvent(AuditEvent(AuditAction.SYNASTRY_REPORT_REQUESTED, AuditActor.user(ownerId), "SYNASTRY", id.toString()))
		return SynastryStarted(id, start(run, facts, me.hash))
	}

	fun list(ownerId: UUID): List<Synastry> = repository.list(ownerId, STALE_SECONDS).map { it.synastry }

	/**
	 * 궁합 하나. 완성됐으면 이렇게 본 이유(상대 출생 정보가 있을 때 읽을 때 계산)와 네 영역의 글을 싣는다.
	 * 완성된 글이 지금의 내 차트로 쓴 것이 아니면(출생 정보를 바꿨다) 여기서 다시 쓰기 시작하고 그 상태를 준다(결정 053 의 5).
	 */
	fun get(ownerId: UUID, id: UUID): Synastry {
		val stored = find(ownerId, id)
		val synastry = stored.synastry
		if (synastry.status != SynastryStatus.DONE) return synastry
		if (rewriteIfStale(ownerId, stored)) return find(ownerId, id).synastry
		return synastry.copy(reasons = reasons(ownerId, stored), report = repository.findReport(id))
	}

	/**
	 * 내 출생 정보가 바뀌었다(결정 053 의 5): 상대 출생 정보가 저장된 궁합의 점수를 새 차트로 바로 다시 계산한다.
	 * 글은 그대로 두고, 그 궁합을 열 때(`get`) 다시 쓴다. 옛 궁합(상대 출생 정보 없음)은 그대로다.
	 */
	@EventListener
	fun on(event: BirthProfileChanged) {
		val mine = profiles.chartInput(event.ownerId)?.chart() ?: return
		val rows = repository.rescorable(event.ownerId)
		for (row in rows) {
			repository.rescore(row.id, scoreOfSum(synastrySum(synastryAspects(mine, profiles.chartInput(row.partner).chart()), row.relation)))
		}
		log.info("event=synastry_rescored count={}", rows.size)
	}

	fun ranking(ownerId: UUID, relation: Relation): List<RankingEntry> = repository.ranking(ownerId, relation, STALE_SECONDS)
		.map { it.synastry }
		.map { RankingEntry(it.id, it.name, it.score!!, it.scoreLabel, it.keywords!!) }

	/**
	 * 궁합 한 건을 지운다(결정 019). 남의 것과 없는 것은 같은 404. 상대 출생 정보 · 리포트는 같은 행 · 외래 키로 함께 지워지고, 그 궁합의 방향성 제시는
	 * [SynastryDeleted] 를 들은 `direction` 이 같은 트랜잭션에서 지운다. 공유 카드는 스냅샷이라 건드리지 않는다(결정 012).
	 * 쓰는 중인 생성은 자기 행이 없어진 것을 보고 그만둔다(저장할 때도 행이 없어 아무것도 넣지 않는다).
	 */
	@Transactional
	fun delete(ownerId: UUID, id: UUID) {
		if (!repository.delete(ownerId, id)) throw NotFoundException("NOT_FOUND")
		events.publishEvent(SynastryDeleted(ownerId, id))
		log.info("event=synastry_deleted")
		events.publishEvent(AuditEvent(AuditAction.SYNASTRY_DELETED, AuditActor.user(ownerId), "SYNASTRY", id.toString()))
	}

	/** 공유 카드에 실을 글을 묻는 이벤트(결정 012). 그 소유자의 완성된 궁합일 때만 답한다(남의 것·없는 것·쓰는 중에는 답하지 않는다). */
	@EventListener
	fun on(event: ScoreCardRequested) {
		val synastry = repository.find(event.ownerId, event.synastryId, STALE_SECONDS)?.synastry?.takeIf { it.status == SynastryStatus.DONE } ?: return
		event.partnerName = synastry.name
		event.score = synastry.score
		event.scoreLabel = synastry.scoreLabel
		event.headline = synastry.headline
	}

	/** 공유 카드에 실을 랭킹을 묻는 이벤트(결정 012). 그 소유자의 랭킹을 순위대로, 이름과 점수만 준다. */
	@EventListener
	fun on(event: RankingCardRequested) {
		event.entries = ranking(event.ownerId, event.relation).map { it.name to it.score }
	}

	/**
	 * 완성된 글을 쓴 내 차트가 지금과 다르면 같은 행에서 다시 쓰기 시작한다. 시작했으면(또는 다른 요청이 먼저 시작했으면) true.
	 * 열기만 한 요청이라 하루 한도 · 생성 한도는 세지 않는다(결정 053 의 5).
	 */
	private fun rewriteIfStale(ownerId: UUID, stored: StoredSynastry): Boolean {
		val partner = stored.partner ?: return false
		val relation = stored.synastry.relation ?: return false
		val me = profiles.chartInput(ownerId) ?: return false
		if (me.hash == stored.ownerChartHash) return false
		val facts = facts(me, profiles.chartInput(partner), relation)
		val run = repository.claim(ownerId, stored.synastry.id, facts.score, me.hash, STALE_SECONDS) ?: return true
		log.info("event=synastry_report_started")
		events.publishEvent(AuditEvent(AuditAction.SYNASTRY_REPORT_REQUESTED, AuditActor.user(ownerId), "SYNASTRY", stored.synastry.id.toString()))
		start(run, facts, me.hash)
		return true
	}

	// 소유자 검사는 여기 한 곳이다: 남의 것과 없는 것은 같은 404.
	private fun find(ownerId: UUID, id: UUID): StoredSynastry =
		repository.find(ownerId, id, STALE_SECONDS) ?: throw NotFoundException("NOT_FOUND")

	// 상대 출생 정보가 없는 행(옛 행 · 링크)에는 이유가 없다. 점수와 같은 차트(지금의 내 차트)로 계산한다.
	private fun reasons(ownerId: UUID, stored: StoredSynastry): List<SynastryReason>? {
		val partner = stored.partner ?: return null
		val relation = stored.synastry.relation ?: return null
		val me = profiles.chartInput(ownerId) ?: return null
		return reasonsOf(synastryAspects(me.chart(), profiles.chartInput(partner).chart()), relation)
	}

	private fun facts(me: ChartInput, partner: ChartInput, relation: Relation): SynastryFacts {
		val mine = me.chart()
		val theirs = partner.chart()
		val aspects = synastryAspects(mine, theirs)
		return SynastryFacts(mine, theirs, aspects, relation, scoreOfSum(synastrySum(aspects, relation)))
	}

	/**
	 * 요약을 일꾼에게 맡기고, 요약이 통과하면 네 영역을 일꾼에게 하나씩 맡긴다. 요청 스레드도 일꾼 스레드도 다른 일을 기다리며 막히지 않는다.
	 * 재료([facts])는 이 생성이 끝나면 버려진다. [chartHash] 는 이 글을 쓰는 내 차트다.
	 */
	private fun start(run: SynastryRun, facts: SynastryFacts, chartHash: String): SynastryStatus {
		// 한 글이 끝내 실패하거나 이 생성이 자리를 잃으면 저장할 수 없으므로, 나머지는 다음 시도부터 그만둔다(모델 호출을 아낀다).
		val abandoned = AtomicBoolean(false)
		val attempts = AtomicInteger()
		val began = System.nanoTime()
		live += run
		val summary = try {
			CompletableFuture.supplyAsync({ writeSummary(run, facts, abandoned, attempts) }, executor)
		} catch (e: RejectedExecutionException) {
			abandoned.set(true)
			live -= run
			repository.fail(run)
			log.warn("event=synastry_report_failed reason=EXECUTOR_FULL")
			return SynastryStatus.FAILED
		}
		summary.thenCompose { written ->
			if (written == null) return@thenCompose CompletableFuture.completedFuture(Written(null, emptyList()))
			val areas = try {
				SynastryReportArea.entries.map { area -> CompletableFuture.supplyAsync({ writeArea(run, area, facts, abandoned, attempts) }, executor) }
			} catch (e: RejectedExecutionException) {
				abandoned.set(true)
				log.warn("event=synastry_report_failed reason=EXECUTOR_FULL")
				return@thenCompose CompletableFuture.completedFuture(Written(written, emptyList()))
			}
			CompletableFuture.allOf(*areas.toTypedArray()).handle { _, _ -> Written(written, areas.map { runCatching { it.join() }.getOrNull() }) }
		}.whenComplete { written, _ -> finish(run, written, chartHash, attempts.get(), began) }
		return SynastryStatus.WRITING
	}

	/**
	 * 이 생성을 그만둬야 하는지. 모델을 부르기 전마다 본다(줄에서 나온 직후, 그리고 답을 받고 다시 부르기 전).
	 * 그만두는 경우: 다른 글이 끝내 실패했다, 줄이 차서 실패로 끝났다, 행이 지워졌다, 다른 생성이 자리를 잡았다.
	 */
	private fun stopped(run: SynastryRun, abandoned: AtomicBoolean): Boolean {
		if (abandoned.get()) return true
		if (repository.owns(run)) return false
		abandoned.set(true)
		return true
	}

	/** 키워드 셋과 한 줄 요약을 쓴다. 통과한 글을 돌려주고, 만들지 못했으면 null. */
	private fun writeSummary(run: SynastryRun, facts: SynastryFacts, abandoned: AtomicBoolean, attempts: AtomicInteger): SynastrySummary? {
		val request = summaryRequest(facts)
		repeat(MAX_ATTEMPTS) { attempt ->
			if (stopped(run, abandoned)) return null
			attempts.incrementAndGet()
			val summary = try {
				generator.generate(request)?.let(::parseSummary)
			} catch (e: Exception) {
				// 호출 자체의 실패는 다시 불러도 같다. 예외의 글은 남기지 않는다(사유 코드나 클래스 이름만).
				val reason = (e as? GenerationException)?.reason ?: e.javaClass.simpleName
				log.warn("event=synastry_generation_failed reason={} attempt={}", reason, attempt + 1)
				abandoned.set(true)
				return null
			}
			val problem = if (summary == null) "UNUSABLE_RESPONSE" else summaryProblem(summary)
			if (summary != null && problem == null) return summary
			log.warn("event=synastry_attempt_rejected reason={} attempt={}", problem, attempt + 1)
		}
		log.warn("event=synastry_generation_failed reason=ATTEMPTS_EXHAUSTED attempt={}", MAX_ATTEMPTS)
		abandoned.set(true)
		return null
	}

	/** 한 영역을 쓴다. 검증을 통과한 글을 돌려주고, 만들지 못했으면 null. */
	private fun writeArea(
		run: SynastryRun,
		area: SynastryReportArea,
		facts: SynastryFacts,
		abandoned: AtomicBoolean,
		attempts: AtomicInteger,
	): SynastryReportSection? {
		val request = synastryAreaRequest(area, facts)
		repeat(MAX_ATTEMPTS) { attempt ->
			if (stopped(run, abandoned)) return null
			attempts.incrementAndGet()
			val section = try {
				generator.generate(request)?.let { parseSynastrySection(area, it) }
			} catch (e: Exception) {
				// 호출 자체의 실패는 다시 불러도 같다. 예외의 글은 남기지 않는다(사유 코드나 클래스 이름만).
				val reason = (e as? GenerationException)?.reason ?: e.javaClass.simpleName
				log.warn("event=synastry_report_area_failed area={} reason={} attempt={}", area, reason, attempt + 1)
				abandoned.set(true)
				return null
			}
			val problem = if (section == null) "UNUSABLE_RESPONSE" else synastrySectionProblem(section, facts.mine)
			if (section != null && problem == null) return section
			log.warn("event=synastry_report_attempt_rejected area={} reason={} attempt={}", area, problem, attempt + 1)
		}
		log.warn("event=synastry_report_area_failed area={} reason=ATTEMPTS_EXHAUSTED attempt={}", area, MAX_ATTEMPTS)
		abandoned.set(true)
		return null
	}

	// 일꾼 스레드에서 돈다. 여기서 난 오류는 받을 곳이 없으므로 삼키고 로그만 남긴다(행은 WRITING 인 채 남고, 살아 있다는 표시가 끊겨 멈춘 것으로 보인다).
	// elapsed_ms 는 넣은 때부터 끝날 때까지 — 한 번에 만들기의 기다림을 잰다(결정 053 의 결과).
	private fun finish(run: SynastryRun, written: Written?, chartHash: String, attempts: Int, began: Long) {
		try {
			val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
			val summary = written?.summary
			val report = SynastryReport(written?.sections.orEmpty().filterNotNull())
			if (summary == null || !isComplete(report)) {
				// 자리를 잃어 그만둔 생성이면 바꿀 행이 없다.
				if (repository.fail(run)) {
					log.warn("event=synastry_report_failed attempts={} elapsed_ms={}", attempts, elapsed)
				} else {
					log.info("event=synastry_report_discarded attempts={}", attempts)
				}
			} else if (repository.complete(run, summary, report, chartHash)) {
				log.info("event=synastry_report_done attempts={} elapsed_ms={}", attempts, elapsed)
				repository.ownerOf(run.id)?.let { events.publishEvent(SynastryReportDone(it, run.id)) }
			} else {
				// 그 사이 궁합이나 계정이 지워졌거나, 멈춘 줄 알고 다시 시작한 생성이 자리를 잡았다.
				log.info("event=synastry_report_discarded attempts={}", attempts)
			}
		} catch (e: Exception) {
			log.error("event=synastry_report_finish_failed error={}", e.javaClass.simpleName)
		} finally {
			live -= run
		}
	}
}
