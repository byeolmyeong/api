package com.byeolmyeong.api.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L

/** 계정별 생성 한도를 넘었다(429 `RATE_LIMITED`, 결정 022·023). */
class GenerationRateLimitedException : RuntimeException("RATE_LIMITED")

/**
 * 생성 기능의 계정별 한도(결정 022 의 숫자, 결정 023 의 세는 방식). 서비스가 저장본이 없음을 확인하고
 * 모델 생성을 시작하기 직전에 [acquire] 를 한 번 부른다(재시도는 세지 않는다). 저장본·404 는 여기까지 오지 않는다.
 * 분 창을 넘은 요청은 시간 창을 쓰지 않는다. 서버 메모리에서 센다(서버 한 대).
 */
@Component
class GenerationQuota(
	@Value("\${byeolmyeong.rate-limit.generation-per-minute}") perMinute: Int,
	@Value("\${byeolmyeong.rate-limit.generation-per-hour}") perHour: Int,
	now: () -> Long = System::currentTimeMillis,
) {
	private val minute = RateLimiter(perMinute, MINUTE_MILLIS, now)
	private val hour = RateLimiter(perHour, HOUR_MILLIS, now)

	fun acquire(ownerId: UUID) {
		val key = ownerId.toString()
		if (!(minute.tryAcquire(key) && hour.tryAcquire(key))) throw GenerationRateLimitedException()
	}
}
