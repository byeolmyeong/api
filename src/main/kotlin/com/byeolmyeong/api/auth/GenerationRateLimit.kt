package com.byeolmyeong.api.auth

import com.byeolmyeong.api.web.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 모델을 부르는 생성 경로(결정 022). 이 경로들의 POST 를 한 계정이 합쳐서 센다. */
private val GENERATION_PATHS = arrayOf("/readings", "/reports", "/directions", "/synastry/direct", "/synastry/*/report")

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L

/**
 * 생성 기능의 계정별 속도 제한(결정 022). 기능 횟수 제한(결정 003 은 두지 않는다)이 아니라 짧은 시간의 남용을 막는다.
 * 세션 검사 다음에 돌아 소유자를 안다. 소유자가 없으면(세션 없는 요청) 세지 않는다 — 세션 검사가 이미 401 로 막았다.
 * 분 창을 넘은 요청은 시간 창을 쓰지 않는다. 서버 메모리에서 센다(서버 한 대).
 */
class GenerationRateLimitInterceptor(perMinute: Int, perHour: Int, now: () -> Long) : HandlerInterceptor {
	private val minute = RateLimiter(perMinute, MINUTE_MILLIS, now)
	private val hour = RateLimiter(perHour, HOUR_MILLIS, now)

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		if (request.method != "POST") return true
		val owner = ownerOf(request) ?: return true
		val key = owner.toString()
		if (minute.tryAcquire(key) && hour.tryAcquire(key)) return true
		response.status = 429
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		response.writer.write("""{"error":"RATE_LIMITED"}""")
		return false
	}
}

@Configuration
class GenerationRateLimitConfig(
	@Value("\${byeolmyeong.rate-limit.generation-per-minute}") private val perMinute: Int,
	@Value("\${byeolmyeong.rate-limit.generation-per-hour}") private val perHour: Int,
) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		// 세션 검사(순서 0)보다 뒤에 돌아야 소유자를 안다.
		registry.addInterceptor(GenerationRateLimitInterceptor(perMinute, perHour, System::currentTimeMillis))
			.addPathPatterns(*GENERATION_PATHS)
			.order(1)
	}
}
