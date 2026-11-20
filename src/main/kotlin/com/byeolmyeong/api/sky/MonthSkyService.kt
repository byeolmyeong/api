package com.byeolmyeong.api.sky

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.notableDays
import com.byeolmyeong.api.generation.dayCategory
import com.byeolmyeong.api.generation.monthPhrases
import com.byeolmyeong.api.web.NotFoundException
import com.byeolmyeong.api.web.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.YearMonth
import java.util.UUID

private const val MINUTE_MILLIS = 60_000L

/** 한 계정이 분당 한도를 넘었다(429 `RATE_LIMITED`). 생성 한도와 따로다(결정 032 의 5). */
class MonthRateLimitedException : RuntimeException("RATE_LIMITED")

/**
 * 이번 달(결정 032). 요청 때마다 계산하고 저장하지 않는다 — 같은 차트 · 같은 달은 언제나 같은 값이다. 평생 시기 구간만 차트 해시마다 몇 분 기억한다([IntervalCache]).
 * 모델을 부르지 않고 생성 한도에 세지 않는다. 남용은 계정별 분당 요청 수로 막는다. 출생 정보 · 계산 결과를 로그에 남기지 않는다.
 */
@Service
class MonthSkyService(
	private val profiles: BirthProfileService,
	@Value("\${byeolmyeong.rate-limit.month-per-minute}") perMinute: Int,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val limiter = RateLimiter(perMinute, MINUTE_MILLIS, System::currentTimeMillis)
	private val intervals = IntervalCache()

	fun month(ownerId: UUID, month: YearMonth): MonthSky {
		if (!limiter.tryAcquire(ownerId.toString())) {
			log.warn("event=month_rate_limited")
			throw MonthRateLimitedException()
		}
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		val chart = birth.chart()
		val days = notableDays(chart, month) { dayCategory(it.mover, it.target) }
		return MonthSky(
			month = month.toString(),
			timeKnown = chart.timeKnown,
			themes = monthThemes(intervals.intervals(birth.hash, chart, birth.utc), month),
			days = days.zip(monthPhrases(days), ::monthDay),
		)
	}
}
