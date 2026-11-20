package com.byeolmyeong.api.reading

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.profile.BirthProfileChanged
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.share.ReadingCardRequested
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.GenerationQuota
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.UUID

// 요약 해석 v2(결정 030 · 031 — 근거 구조 · 보완점 2 · 만든 때). 옛 "SUMMARY" 행은 읽지 않으므로 다음 요청이 새로 만든다(지우거나 고치지 않는다).
private const val SUMMARY = "SUMMARY_V2"

/** 처음 한 번 + 다시 생성 2번. */
private const val MAX_ATTEMPTS = 3

/** 진행 중인 같은 생성을 기다리는 최대 시간. 생성 한 번은 모델 호출 시간 제한(120초) × 시도 횟수 안에 끝난다. */
private const val IN_FLIGHT_WAIT_MINUTES = 10L
private const val SHARE_ID_BYTES = 16

/** 공유 카드의 키워드로 내주는 문단 제목의 수(앞에서부터). 마지막 문단(과제)은 내주지 않는다. */
private const val SHARE_KEYWORDS = 3

/**
 * 요약 해석. 같은 소유자·같은 차트의 글은 한 번만 만들고, 검증을 통과한 글만 저장한다.
 * 출생 정보·차트·프롬프트·글을 로그에 남기지 않는다(실패 사유 코드와 시도 횟수만).
 */
@Service
class ReadingService(
	private val profiles: BirthProfileService,
	private val repository: ReadingRepository,
	private val generator: TextGenerator,
	private val events: ApplicationEventPublisher,
	private val quota: GenerationQuota,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()

	/** 진행 중인 요약 해석 생성(소유자·종류·차트 해시 → 결과). 서버 메모리에만 있고 끝나면 지운다. */
	private val inFlight = ConcurrentHashMap<String, CompletableFuture<Reading>>()

	fun summary(ownerId: UUID): Reading {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val chartHash = birth.hash
		repository.find(ownerId, SUMMARY, chartHash)?.let { return it }

		// 결정 022: 같은 소유자·같은 차트의 생성이 진행 중이면 그 결과를 기다린다(모델을 두 번 부르지 않는다).
		val key = "$ownerId:$SUMMARY:$chartHash"
		val mine = CompletableFuture<Reading>()
		inFlight.putIfAbsent(key, mine)?.let { return await(it) }
		try {
			// 앞 요청이 방금 끝나고 진행 표시를 지운 틈이면 저장본이 있다.
			val reading = repository.find(ownerId, SUMMARY, chartHash) ?: run {
				// 모델을 부르기 직전에 센다(결정 023). 한도를 넘으면 기다리던 요청도 같은 429 를 받는다.
				quota.acquire(ownerId)
				createSummary(ownerId, birth.chart(), chartHash)
			}
			mine.complete(reading)
			return reading
		} catch (e: Throwable) {
			mine.completeExceptionally(e)
			throw e
		} finally {
			inFlight.remove(key, mine)
		}
	}

	/** 진행 중인 생성의 결과를 기다린다. 그 생성이 실패하면 같은 예외를 던진다(앞 요청과 같은 응답). */
	private fun await(running: CompletableFuture<Reading>): Reading = try {
		running.get(IN_FLIGHT_WAIT_MINUTES, TimeUnit.MINUTES)
	} catch (e: ExecutionException) {
		throw e.cause ?: e
	} catch (e: TimeoutException) {
		throw GenerationException("IN_FLIGHT_WAIT_TIMEOUT")
	}

	private fun createSummary(ownerId: UUID, chart: Chart, chartHash: String): Reading {
		// 감사 기록(결정 015): 새 글을 만든 요청만 남긴다. 저장본을 돌려준 요청·기다린 요청은 남기지 않는다.
		val body = try {
			readingBody(generate(chart), chart)
		} catch (e: GenerationException) {
			events.publishEvent(AuditEvent(AuditAction.READING_GENERATED, AuditActor.user(ownerId), success = false))
			throw e
		}
		// 동시에 두 요청이 생성했으면 먼저 들어간 글만 남고, 둘 다 그 글을 돌려준다.
		repository.insertIfAbsent(ownerId, SUMMARY, chartHash, body, newShareId())
		events.publishEvent(AuditEvent(AuditAction.READING_GENERATED, AuditActor.user(ownerId)))
		return checkNotNull(repository.find(ownerId, SUMMARY, chartHash)) { "방금 저장한 글이 없다" }
	}

	/** 출생 정보가 바뀌면 옛 차트의 요약 해석을 지운다(결정 041). 다음 `POST /readings` 가 새 출생 정보로 만든다. */
	@EventListener
	fun on(event: BirthProfileChanged) = repository.deleteOtherCharts(event.ownerId, event.chartHash)

	/** 공유 카드에 실을 글을 묻는 이벤트(결정 012). 그 소유자의 지금 차트의 요약 해석이 있을 때만 답한다. 근거와 본문은 주지 않는다. */
	@EventListener
	fun on(event: ReadingCardRequested) {
		val birth = profiles.chartInput(event.ownerId) ?: return
		val reading = repository.find(event.ownerId, SUMMARY, birth.hash) ?: return
		event.headline = reading.headline
		event.keywords = reading.sections.take(SHARE_KEYWORDS).map { it.title }
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
