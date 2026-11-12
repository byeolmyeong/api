package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.ChartInput
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.scoreOfSum
import com.byeolmyeong.api.chart.synastryAspects
import com.byeolmyeong.api.chart.synastrySum
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.SynastryFacts
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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

/** 처음 한 번 + 다시 생성 2번(요약 해석과 같다). */
private const val MAX_ATTEMPTS = 3

/**
 * 리포트가 `WRITING` 인데 살아 있다는 표시(`active_at`)가 이만큼 끊기면 멈춘 것으로 본다(서버가 쓰는 도중 꺼진 경우. 심층 리포트와 같다).
 * 표시는 `byeolmyeong.report.heartbeat-seconds`(15초)마다 적으므로 여덟 번을 놓쳐야 한다.
 * 자리를 잡은 뒤 흐른 시간으로는 가리지 않는다: 줄에서 오래 기다리는 것은 멈춘 것이 아니다.
 */
internal const val STALE_SECONDS = 120

/** 내 출생 정보가 그 궁합을 계산한 때와 다르다(409 `PROFILE_CHANGED`). */
class ProfileChangedException : RuntimeException("PROFILE_CHANGED")

/**
 * 궁합(직접 입력). 점수는 `chart` 가 계산하고 모델은 글만 쓴다. 검증을 통과한 글만 저장한다.
 * 상대의 출생 정보와 행성 위치는 이 요청(리포트는 그 생성) 안에서만 쓰고 저장하지 않는다(결정 010). 로그에도 남기지 않는다.
 * 리포트의 글은 심층 리포트와 같은 뒤쪽 일꾼(`reportExecutor`)이 쓴다: 동시에 나가는 모델 호출 수의 상한을 하나로 둔다.
 */
@Service
class SynastryService(
	private val profiles: BirthProfileService,
	private val repository: SynastryRepository,
	private val generator: TextGenerator,
	@Qualifier("reportExecutor") private val executor: Executor,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	// 이 서버가 맡아 아직 끝내지 않은 리포트 생성(줄에서 기다리는 것 포함).
	private val live = ConcurrentHashMap.newKeySet<SynastryReportRun>()

	/** 이 서버가 맡은 리포트 생성이 살아 있다고 행에 적는다. 서버가 꺼지면 더 적히지 않아 그 행은 멈춘 것으로 보인다. */
	@Scheduled(fixedDelayString = "\${byeolmyeong.report.heartbeat-seconds}", timeUnit = TimeUnit.SECONDS)
	fun heartbeat() {
		for (run in live) {
			try {
				repository.beatReport(run)
			} catch (e: Exception) {
				log.warn("event=synastry_report_heartbeat_failed error={}", e.javaClass.simpleName)
			}
		}
	}

	/** 나(저장된 프로필)와 [partner](요청에 적힌 상대)의 궁합을 계산하고, 글을 받아 저장한다. 겹친 휠은 이 응답에만 싣는다. */
	fun direct(ownerId: UUID, name: String, relation: Relation, partner: ChartInput): Synastry {
		val me = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val facts = facts(me, partner, relation)
		val summary = writeSummary(facts)
		val id = repository.insert(NewSynastry(ownerId, name, relation, facts.score, summary, me.hash))
		log.info("event=synastry_created relation={}", relation)
		return find(ownerId, id).synastry.copy(overlay = overlayOf(facts.mine, facts.theirs, facts.aspects))
	}

	fun list(ownerId: UUID): List<Synastry> = repository.list(ownerId, STALE_SECONDS).map { it.synastry }

	fun get(ownerId: UUID, id: UUID): Synastry = find(ownerId, id).synastry

	fun ranking(ownerId: UUID, relation: Relation): List<RankingEntry> = repository.ranking(ownerId, relation, STALE_SECONDS)
		.map { it.synastry }
		.map { RankingEntry(it.id, it.name, it.score, it.scoreLabel, it.keywords) }

	/** 궁합 리포트의 상태와, 완성됐으면 글. */
	fun report(ownerId: UUID, id: UUID): SynastryReportResponse {
		find(ownerId, id)
		val stored = repository.findReport(id, STALE_SECONDS) ?: return SynastryReportResponse(SynastryReportStatus.NONE)
		return SynastryReportResponse(stored.status, stored.report)
	}

	/**
	 * 궁합 리포트를 만들기 시작한다. 이미 완성됐거나 쓰는 중이면 시작하지 않고 그 상태를 돌려준다([partner] 를 읽지 않는다).
	 * 상대의 출생 정보는 저장돼 있지 않으므로 [partner] 로 다시 받아 계산하고, 그 점수가 저장된 점수와 같을 때만 쓴다.
	 */
	fun requestReport(ownerId: UUID, id: UUID, partner: () -> ChartInput): SynastryReportStatus {
		val stored = find(ownerId, id)
		val status = stored.synastry.reportStatus
		if (status == SynastryReportStatus.DONE || status == SynastryReportStatus.WRITING) return status
		val me = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		if (me.hash != stored.ownerChartHash) throw ProfileChangedException()
		val facts = facts(me, partner(), stored.synastry.relation)
		if (facts.score != stored.synastry.score) throw BadRequestException("BIRTH_MISMATCH")
		// 그 사이 다른 요청이 자리를 잡았으면 그 상태를 돌려준다.
		val run = repository.claimReport(id, STALE_SECONDS) ?: return find(ownerId, id).synastry.reportStatus
		log.info("event=synastry_report_started")
		return start(run, facts)
	}

	// 소유자 검사는 여기 한 곳이다: 남의 것과 없는 것은 같은 404.
	private fun find(ownerId: UUID, id: UUID): StoredSynastry =
		repository.find(ownerId, id, STALE_SECONDS) ?: throw NotFoundException("NOT_FOUND")

	private fun facts(me: ChartInput, partner: ChartInput, relation: Relation): SynastryFacts {
		val mine = me.chart()
		val theirs = partner.chart()
		val aspects = synastryAspects(mine, theirs)
		return SynastryFacts(mine, theirs, aspects, relation, scoreOfSum(synastrySum(aspects, relation)))
	}

	private fun writeSummary(facts: SynastryFacts): SynastrySummary {
		val request = summaryRequest(facts)
		repeat(MAX_ATTEMPTS) { attempt ->
			val summary = try {
				generator.generate(request)?.let(::parseSummary)
			} catch (e: GenerationException) {
				// 호출 자체의 실패는 다시 불러도 같다.
				log.warn("event=synastry_generation_failed reason={} attempt={}", e.reason, attempt + 1)
				throw e
			}
			val problem = if (summary == null) "UNUSABLE_RESPONSE" else summaryProblem(summary)
			if (summary != null && problem == null) return summary
			log.warn("event=synastry_attempt_rejected reason={} attempt={}", problem, attempt + 1)
		}
		log.warn("event=synastry_generation_failed reason=ATTEMPTS_EXHAUSTED attempt={}", MAX_ATTEMPTS)
		throw GenerationException("ATTEMPTS_EXHAUSTED")
	}

	// 네 영역을 일꾼에게 하나씩 맡긴다. 요청 스레드는 기다리지 않는다. 재료([facts])는 이 생성이 끝나면 버려진다.
	private fun start(run: SynastryReportRun, facts: SynastryFacts): SynastryReportStatus {
		// 한 영역이 끝내 실패하거나 이 생성이 자리를 잃으면 리포트를 저장할 수 없으므로, 나머지 영역은 다음 시도부터 그만둔다(모델 호출을 아낀다).
		val abandoned = AtomicBoolean(false)
		val attempts = AtomicInteger()
		live += run
		val areas = try {
			SynastryReportArea.entries.map { area ->
				CompletableFuture.supplyAsync({ writeArea(run, area, facts, abandoned, attempts) }, executor)
			}
		} catch (e: RejectedExecutionException) {
			abandoned.set(true)
			live -= run
			repository.failReport(run)
			log.warn("event=synastry_report_failed reason=EXECUTOR_FULL")
			return SynastryReportStatus.FAILED
		}
		CompletableFuture.allOf(*areas.toTypedArray()).whenComplete { _, _ ->
			finish(run, areas.map { runCatching { it.join() }.getOrNull() }, attempts.get())
		}
		return SynastryReportStatus.WRITING
	}

	/**
	 * 이 생성을 그만둬야 하는지. 모델을 부르기 전마다 본다(줄에서 나온 직후, 그리고 답을 받고 다시 부르기 전).
	 * 그만두는 경우: 다른 영역이 끝내 실패했다, 줄이 차서 실패로 끝났다, 행이 지워졌다, 다른 생성이 자리를 잡았다.
	 */
	private fun stopped(run: SynastryReportRun, abandoned: AtomicBoolean): Boolean {
		if (abandoned.get()) return true
		if (repository.ownsReport(run)) return false
		abandoned.set(true)
		return true
	}

	/** 한 영역을 쓴다. 검증을 통과한 글을 돌려주고, 만들지 못했으면 null. */
	private fun writeArea(
		run: SynastryReportRun,
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
	private fun finish(run: SynastryReportRun, sections: List<SynastryReportSection?>, attempts: Int) {
		try {
			val report = SynastryReport(sections.filterNotNull())
			if (!isComplete(report)) {
				// 자리를 잃어 그만둔 생성이면 바꿀 행이 없다.
				if (repository.failReport(run)) {
					log.warn("event=synastry_report_failed attempts={}", attempts)
				} else {
					log.info("event=synastry_report_discarded attempts={}", attempts)
				}
			} else if (repository.completeReport(run, report)) {
				log.info("event=synastry_report_done attempts={}", attempts)
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
