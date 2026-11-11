package com.byeolmyeong.api.direction

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 이성 주제. 대상은 소유자의 지금 차트다(출생 정보를 고치면 새 글). */
	fun love(ownerId: UUID): Direction {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val target = birth.hash
		repository.find(ownerId, TOPIC_LOVE, target)?.let { return it }

		val draft = generateLove(birth.chart())
		// 동시에 두 요청이 생성했으면 먼저 들어간 글만 남고, 둘 다 그 글을 돌려준다.
		repository.insertIfAbsent(ownerId, TOPIC_LOVE, target, draft)
		return checkNotNull(repository.find(ownerId, TOPIC_LOVE, target)) { "방금 저장한 글이 없다" }
	}

	private fun generateLove(chart: Chart): DirectionDraft {
		val request = loveDirectionRequest(chart)
		repeat(MAX_ATTEMPTS) { attempt ->
			val draft = try {
				generator.generate(request)?.let(::parseDirectionDraft)
			} catch (e: GenerationException) {
				log.warn("event=direction_generation_failed topic={} reason={} attempt={}", TOPIC_LOVE, e.reason, attempt + 1)
				throw e
			}
			val problem = if (draft == null) "UNUSABLE_RESPONSE" else draftProblem(draft, chart)
			if (draft != null && problem == null) return draft
			log.warn("event=direction_attempt_rejected topic={} reason={} attempt={}", TOPIC_LOVE, problem, attempt + 1)
		}
		log.warn("event=direction_generation_failed topic={} reason=ATTEMPTS_EXHAUSTED attempt={}", TOPIC_LOVE, MAX_ATTEMPTS)
		throw GenerationException("ATTEMPTS_EXHAUSTED")
	}
}
