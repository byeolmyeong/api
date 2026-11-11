package com.byeolmyeong.api.reading

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

private const val SUMMARY = "SUMMARY"

/** 처음 한 번 + 다시 생성 2번. */
private const val MAX_ATTEMPTS = 3
private const val SHARE_ID_BYTES = 16

/**
 * 요약 해석. 같은 소유자·같은 차트의 글은 한 번만 만들고, 검증을 통과한 글만 저장한다.
 * 출생 정보·차트·프롬프트·글을 로그에 남기지 않는다(실패 사유 코드와 시도 횟수만).
 */
@Service
class ReadingService(
	private val profiles: BirthProfileService,
	private val repository: ReadingRepository,
	private val generator: TextGenerator,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()

	fun summary(ownerId: UUID): Reading {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val chartHash = birth.hash
		repository.find(ownerId, SUMMARY, chartHash)?.let { return it }

		val draft = generate(birth.chart())
		// 동시에 두 요청이 생성했으면 먼저 들어간 글만 남고, 둘 다 그 글을 돌려준다.
		repository.insertIfAbsent(ownerId, SUMMARY, chartHash, draft, newShareId())
		return checkNotNull(repository.find(ownerId, SUMMARY, chartHash)) { "방금 저장한 글이 없다" }
	}

	private fun generate(chart: Chart): ReadingDraft {
		val request = readingRequest(chart)
		repeat(MAX_ATTEMPTS) { attempt ->
			val draft = try {
				generator.generate(request)?.let(::parseReadingDraft)
			} catch (e: GenerationException) {
				log.warn("event=reading_generation_failed reason={} attempt={}", e.reason, attempt + 1)
				throw e
			}
			val problem = if (draft == null) "UNUSABLE_RESPONSE" else draftProblem(draft, chart)
			if (draft != null && problem == null) return draft
			log.warn("event=reading_attempt_rejected reason={} attempt={}", problem, attempt + 1)
		}
		log.warn("event=reading_generation_failed reason=ATTEMPTS_EXHAUSTED attempt={}", MAX_ATTEMPTS)
		throw GenerationException("ATTEMPTS_EXHAUSTED")
	}

	private fun newShareId(): String =
		Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SHARE_ID_BYTES).also(random::nextBytes))
}
