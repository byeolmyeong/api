package com.byeolmyeong.api.direction

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.ChartInput
import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.chart.computeIntervals
import com.byeolmyeong.api.chart.computeMilestones
import com.byeolmyeong.api.chart.scoreOfSum
import com.byeolmyeong.api.chart.synastryAspects
import com.byeolmyeong.api.chart.synastrySum
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.SynastryFacts
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.generation.basisVocabulary
import com.byeolmyeong.api.generation.synastryBasisVocabulary
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** 처음 한 번 + 다시 생성 2번. */
private const val MAX_ATTEMPTS = 3

/**
 * 방향성 제시. 같은 소유자·주제·대상의 글은 한 번만 만들고, 검증을 통과한 글만 저장한다.
 * 출생 정보·차트·프롬프트·글을 로그에 남기지 않는다(주제·실패 사유 코드·시도 횟수만).
 */
@Service
class DirectionService(
	private val profiles: BirthProfileService,
	private val repository: DirectionRepository,
	private val generator: TextGenerator,
	private val events: ApplicationEventPublisher,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 이성 주제. 대상은 소유자의 지금 차트다(출생 정보를 고치면 새 글). */
	fun love(ownerId: UUID): Direction {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val target = birth.hash
		repository.find(ownerId, TOPIC_LOVE, target)?.let { return it }

		val chart = birth.chart()
		val draft = generate(ownerId, TOPIC_LOVE, loveDirectionRequest(chart)) { draftProblem(it, basisVocabulary(chart), numbers = true) }
		return store(ownerId, TOPIC_LOVE, target, draft)
	}

	/**
	 * 미래 주제. 대상은 소유자의 지금 차트와 "지금의 시기가 같은 동안" 이다(새 구간에 들어가거나 구간이 끝나면 새 글. 결정 011).
	 * 오늘을 덮는 저장본이 있으면 시기를 계산하지 않는다. 없을 때만 계산한다(약 0.3초).
	 */
	fun future(ownerId: UUID): Direction {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val today = LocalDate.now(KOREA_ZONE)
		repository.findByTargetPrefix(ownerId, TOPIC_FUTURE, futureTargetPrefix(birth.hash))
			.firstOrNull { (target, _) -> futureTargetCovers(target, today) }
			?.let { (_, direction) -> return direction }

		val chart = birth.chart()
		val situation = futureSituation(computeMilestones(birth.utc, KOREA_ZONE), computeIntervals(chart, birth.utc), today)
		val draft = generate(ownerId, TOPIC_FUTURE, futureDirectionRequest(chart, situation)) { draftProblem(it, basisVocabulary(chart), numbers = false) }
		return store(ownerId, TOPIC_FUTURE, futureTarget(birth.hash, situation), draft)
	}

	/**
	 * 궁합 주제. 대상은 궁합 한 건이다. 이미 만든 글이 있으면 그대로 주고 [partner] 를 읽지 않는다.
	 * 없으면 상대의 출생 정보를 [partner] 로 다시 받아 그 자리에서 두 차트를 겹치고, 그 점수가 저장된 점수와 같을 때만 쓴다.
	 * 상대의 출생 정보·행성 위치·연결 목록은 이 요청 안에서만 쓰고 저장하지 않는다(결정 010·011). 로그에도 남기지 않는다.
	 */
	fun synastry(ownerId: UUID, synastryId: UUID, partner: () -> ChartInput): Direction {
		// 소유자 검사는 여기 한 곳이다: 남의 것과 없는 것은 같은 404.
		val about = repository.findSynastry(ownerId, synastryId) ?: throw NotFoundException("NOT_FOUND")
		val target = synastryId.toString()
		repository.find(ownerId, TOPIC_SYNASTRY, target)?.let { return it }

		val me = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		if (me.hash != about.ownerChartHash) throw ProfileChangedException()
		val mine = me.chart()
		val theirs = partner().chart()
		val aspects = synastryAspects(mine, theirs)
		val facts = SynastryFacts(mine, theirs, aspects, about.relation, scoreOfSum(synastrySum(aspects, about.relation)))
		if (facts.score != about.score) throw BadRequestException("BIRTH_MISMATCH")
		val draft = generate(ownerId, TOPIC_SYNASTRY, synastryDirectionRequest(facts)) { draftProblem(it, synastryBasisVocabulary(mine), numbers = false) }
		return store(ownerId, TOPIC_SYNASTRY, target, draft)
	}

	// 동시에 두 요청이 생성했으면 먼저 들어간 글만 남고, 둘 다 그 글을 돌려준다.
	private fun store(ownerId: UUID, topic: String, target: String, draft: DirectionDraft): Direction {
		repository.insertIfAbsent(ownerId, topic, target, draft)
		// 감사 기록(결정 015): 새 글을 만든 요청만 남긴다. 궁합 주제는 어느 궁합의 글인지(id)도 남긴다.
		val synastryId = target.takeIf { topic == TOPIC_SYNASTRY }
		events.publishEvent(AuditEvent(AuditAction.DIRECTION_GENERATED, AuditActor.user(ownerId), synastryId?.let { "SYNASTRY" }, synastryId, detail = mapOf("topic" to topic)))
		return checkNotNull(repository.find(ownerId, topic, target)) { "방금 저장한 글이 없다" }
	}

	// 끝내 만들지 못하면(502) 감사 기록에 실패로 남기고 그대로 던진다.
	private fun generate(ownerId: UUID, topic: String, request: GenerationRequest, problemOf: (DirectionDraft) -> String?): DirectionDraft =
		try {
			generate(topic, request, problemOf)
		} catch (e: GenerationException) {
			events.publishEvent(AuditEvent(AuditAction.DIRECTION_GENERATED, AuditActor.user(ownerId), success = false, detail = mapOf("topic" to topic)))
			throw e
		}

	private fun generate(topic: String, request: GenerationRequest, problemOf: (DirectionDraft) -> String?): DirectionDraft {
		repeat(MAX_ATTEMPTS) { attempt ->
			val draft = try {
				generator.generate(request)?.let(::parseDirectionDraft)
			} catch (e: GenerationException) {
				log.warn("event=direction_generation_failed topic={} reason={} attempt={}", topic, e.reason, attempt + 1)
				throw e
			}
			val problem = if (draft == null) "UNUSABLE_RESPONSE" else problemOf(draft)
			if (draft != null && problem == null) return draft
			log.warn("event=direction_attempt_rejected topic={} reason={} attempt={}", topic, problem, attempt + 1)
		}
		log.warn("event=direction_generation_failed topic={} reason=ATTEMPTS_EXHAUSTED attempt={}", topic, MAX_ATTEMPTS)
		throw GenerationException("ATTEMPTS_EXHAUSTED")
	}
}
