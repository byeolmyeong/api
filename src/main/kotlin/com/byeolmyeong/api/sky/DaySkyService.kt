package com.byeolmyeong.api.sky

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.profile.BirthProfileChanged
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.GenerationQuota
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 처음 한 번 + 다시 생성 2번. */
private const val MAX_ATTEMPTS = 3

/** 진행 중인 같은 생성을 기다리는 최대 시간. 생성 한 번은 모델 호출 시간 제한(120초) × 시도 횟수 안에 끝난다. */
private const val IN_FLIGHT_WAIT_MINUTES = 10L

/**
 * 그날의 하늘(결정 034). 같은 소유자 · 같은 차트 · 같은 날의 글은 한 번만 만들고, 검증을 통과한 글만 저장한다.
 * 출생 정보 · 차트 · 프롬프트 · 글 · 날짜를 로그에 남기지 않는다(실패 사유 코드와 시도 횟수만).
 */
@Service
class DaySkyService(
	private val profiles: BirthProfileService,
	private val repository: DaySkyRepository,
	private val generator: TextGenerator,
	private val quota: GenerationQuota,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 진행 중인 생성(소유자 · 차트 해시 · 날짜 → 결과). 서버 메모리에만 있고 끝나면 지운다. */
	private val inFlight = ConcurrentHashMap<String, CompletableFuture<DaySky>>()

	/**
	 * [ownerId] 의 [date](한국 날짜) 그날의 하늘을 만들거나 저장본을 돌려준다. 저장본이 있으면 모델 · 생성 한도 없이 바로 준다.
	 * 같은 소유자 · 차트 · 날의 생성이 진행 중이면 그 결과(실패 · 429 포함)를 기다린다.
	 * [countQuota] 가 true 면 모델을 부르기 직전에 생성 한도를 한 번 센다(`POST /day-sky` — 결정 023). 서버가 스스로 만드는 때
	 * (푸시 묶음의 아침 미리 만들기)는 false 로 부른다 — 생성 고리는 같다. 날짜 범위는 부르는 쪽이 본다([parseDaySkyDate]).
	 * 저장한 출생 정보가 없으면 [NotFoundException](`NO_PROFILE`), 글을 만들지 못하면 [GenerationException](저장하지 않는다).
	 */
	fun daySky(ownerId: UUID, date: LocalDate, countQuota: Boolean): DaySky {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val chartHash = birth.hash
		repository.find(ownerId, chartHash, date)?.let { return it }

		// 같은 소유자 · 차트 · 날의 생성이 진행 중이면 그 결과를 기다린다(모델을 두 번 부르지 않는다 — 결정 034 의 4).
		val key = "$ownerId:$chartHash:$date"
		val mine = CompletableFuture<DaySky>()
		inFlight.putIfAbsent(key, mine)?.let { return await(it) }
		try {
			// 앞 요청이 방금 끝나고 진행 표시를 지운 틈이면 저장본이 있다.
			val sky = repository.find(ownerId, chartHash, date) ?: run {
				// 모델을 부르기 직전에 센다(결정 023). 한도를 넘으면 기다리던 요청도 같은 429 를 받는다.
				if (countQuota) quota.acquire(ownerId)
				create(ownerId, birth.chart(), chartHash, date)
			}
			mine.complete(sky)
			return sky
		} catch (e: Throwable) {
			mine.completeExceptionally(e)
			throw e
		} finally {
			inFlight.remove(key, mine)
		}
	}

	/** 출생 정보가 바뀌면 옛 차트의 그날의 하늘을 지운다(결정 041). 다음 요청이 새 출생 정보로 만든다. */
	@EventListener
	fun on(event: BirthProfileChanged) = repository.deleteOtherCharts(event.ownerId, event.chartHash)

	/** 진행 중인 생성의 결과를 기다린다. 그 생성이 실패하면 같은 예외를 던진다(앞 요청과 같은 응답). */
	private fun await(running: CompletableFuture<DaySky>): DaySky = try {
		running.get(IN_FLIGHT_WAIT_MINUTES, TimeUnit.MINUTES)
	} catch (e: ExecutionException) {
		throw e.cause ?: e
	} catch (e: TimeoutException) {
		throw GenerationException("IN_FLIGHT_WAIT_TIMEOUT")
	}

	private fun create(ownerId: UUID, chart: Chart, chartHash: String, date: LocalDate): DaySky {
		val plan = dayPlan(chart, date)
		val body = daySkyBody(generate(chart, plan), plan)
		// 동시에 두 생성이 저장하면 먼저 들어간 글만 남고, 둘 다 그 글을 돌려준다.
		repository.insertIfAbsent(ownerId, chartHash, date, body)
		return checkNotNull(repository.find(ownerId, chartHash, date)) { "방금 저장한 글이 없다" }
	}

	private fun generate(chart: Chart, plan: DayPlan): DaySkyDraft {
		val request = daySkyRequest(chart, plan)
		repeat(MAX_ATTEMPTS) { attempt ->
			val draft = try {
				generator.generate(request)?.let { parseDaySkyDraft(it, needsTitle = plan.title == null) }
			} catch (e: GenerationException) {
				log.warn("event=day_sky_generation_failed reason={} attempt={}", e.reason, attempt + 1)
				throw e
			}
			val problem = if (draft == null) "UNUSABLE_RESPONSE" else daySkyProblem(draft)
			if (draft != null && problem == null) return draft
			log.warn("event=day_sky_attempt_rejected reason={} attempt={}", problem, attempt + 1)
		}
		log.warn("event=day_sky_generation_failed reason=ATTEMPTS_EXHAUSTED attempt={}", MAX_ATTEMPTS)
		throw GenerationException("ATTEMPTS_EXHAUSTED")
	}
}
