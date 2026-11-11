package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.ChartInput
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.scoreOfSum
import com.byeolmyeong.api.chart.synastryAspects
import com.byeolmyeong.api.chart.synastrySum
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/** 처음 한 번 + 다시 생성 2번(요약 해석과 같다). */
private const val MAX_ATTEMPTS = 3

/** 리포트가 `WRITING` 인 채 이만큼 지나면 멈춘 것으로 본다(심층 리포트와 같다). */
internal const val STALE_MINUTES = 10

/**
 * 궁합(직접 입력). 점수는 `chart` 가 계산하고 모델은 글만 쓴다. 검증을 통과한 글만 저장한다.
 * 상대의 출생 정보와 행성 위치는 이 요청 안에서만 쓰고 저장하지 않는다(결정 008). 로그에도 남기지 않는다.
 */
@Service
class SynastryService(
	private val profiles: BirthProfileService,
	private val repository: SynastryRepository,
	private val generator: TextGenerator,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 나(저장된 프로필)와 [partner](요청에 적힌 상대)의 궁합을 계산하고, 글을 받아 저장한다. 겹친 휠은 이 응답에만 싣는다. */
	fun direct(ownerId: UUID, name: String, relation: Relation, partner: ChartInput): Synastry {
		val me = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val facts = facts(me, partner, relation)
		val summary = writeSummary(facts)
		val id = repository.insert(NewSynastry(ownerId, name, relation, facts.score, summary, me.hash))
		log.info("event=synastry_created relation={}", relation)
		return find(ownerId, id).synastry.copy(overlay = overlayOf(facts.mine, facts.theirs, facts.aspects))
	}

	fun list(ownerId: UUID): List<Synastry> = repository.list(ownerId, STALE_MINUTES).map { it.synastry }

	fun get(ownerId: UUID, id: UUID): Synastry = find(ownerId, id).synastry

	fun ranking(ownerId: UUID, relation: Relation): List<RankingEntry> = repository.ranking(ownerId, relation, STALE_MINUTES)
		.map { it.synastry }
		.map { RankingEntry(it.id, it.name, it.score, it.scoreLabel, it.keywords) }

	// 소유자 검사는 여기 한 곳이다: 남의 것과 없는 것은 같은 404.
	private fun find(ownerId: UUID, id: UUID): StoredSynastry =
		repository.find(ownerId, id, STALE_MINUTES) ?: throw NotFoundException("NOT_FOUND")

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
}
