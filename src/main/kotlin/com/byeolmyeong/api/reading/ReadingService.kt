package com.byeolmyeong.api.reading

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.Places
import com.byeolmyeong.api.birth.toUtc
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
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
	private val places: Places,
	private val repository: ReadingRepository,
	private val generator: ReadingGenerator,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()

	fun summary(ownerId: UUID): Reading {
		val profile = profiles.find(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val place = checkNotNull(places.find(profile.placeId)) { "저장된 출생지가 목록에 없다" }
		val utc = toUtc(profile.date, profile.time)
		val timeKnown = profile.time != null
		// 차트를 정하는 입력만으로 만든다. 시각을 모르면 위경도는 차트에 쓰이지 않으므로 넣지 않는다.
		val chartHash = sha256(
			if (timeKnown) "v1|${utc.epochSecond}|${place.lat}|${place.lon}" else "v1|${utc.epochSecond}|time-unknown",
		)
		repository.find(ownerId, SUMMARY, chartHash)?.let { return it }

		val draft = generate(computeChart(utc, place.lat, place.lon, timeKnown))
		// 동시에 두 요청이 생성했으면 먼저 들어간 글만 남고, 둘 다 그 글을 돌려준다.
		repository.insertIfAbsent(ownerId, SUMMARY, chartHash, draft, newShareId())
		return checkNotNull(repository.find(ownerId, SUMMARY, chartHash)) { "방금 저장한 글이 없다" }
	}

	private fun generate(chart: Chart): ReadingDraft {
		val prompt = buildReadingPrompt(chart)
		repeat(MAX_ATTEMPTS) { attempt ->
			val draft = try {
				generator.generate(prompt)
			} catch (e: ReadingGenerationException) {
				log.warn("event=reading_generation_failed reason={} attempt={}", e.reason, attempt + 1)
				throw e
			}
			val problem = if (draft == null) "UNUSABLE_RESPONSE" else draftProblem(draft, chart)
			if (draft != null && problem == null) return draft
			log.warn("event=reading_attempt_rejected reason={} attempt={}", problem, attempt + 1)
		}
		log.warn("event=reading_generation_failed reason=ATTEMPTS_EXHAUSTED attempt={}", MAX_ATTEMPTS)
		throw ReadingGenerationException("ATTEMPTS_EXHAUSTED")
	}

	private fun newShareId(): String =
		Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SHARE_ID_BYTES).also(random::nextBytes))

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
