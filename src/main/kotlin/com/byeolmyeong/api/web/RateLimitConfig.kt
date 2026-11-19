package com.byeolmyeong.api.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

private const val WINDOW_MILLIS = 60_000L

/**
 * `POST /charts`·`GET /places`·`POST /synastry/direct`·`GET /faqs`·`GET /app-config`·`GET /shared/{shareId}`·`/auth/login`·`/auth/signup`
 * 의 주소별 요청 수 제한. 한도를 넘으면 429. 로그인 · 가입은 한 한도를 같이 쓴다(결정 021 — 카카오 코드는 확인 전에 카카오를 부른다).
 */
@Configuration
class RateLimitConfig(
	@Value("\${byeolmyeong.rate-limit.charts-per-minute}") private val chartsPerMinute: Int,
	@Value("\${byeolmyeong.rate-limit.places-per-minute}") private val placesPerMinute: Int,
	@Value("\${byeolmyeong.rate-limit.synastry-per-minute}") private val synastryPerMinute: Int,
	@Value("\${byeolmyeong.rate-limit.faqs-per-minute}") private val faqsPerMinute: Int,
	@Value("\${byeolmyeong.rate-limit.app-config-per-minute}") private val appConfigPerMinute: Int,
	@Value("\${byeolmyeong.rate-limit.shared-per-minute}") private val sharedPerMinute: Int,
	@Value("\${byeolmyeong.rate-limit.auth-per-minute}") private val authPerMinute: Int,
) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(RateLimitInterceptor(chartsPerMinute)).addPathPatterns("/charts")
		registry.addInterceptor(RateLimitInterceptor(placesPerMinute)).addPathPatterns("/places")
		registry.addInterceptor(RateLimitInterceptor(synastryPerMinute)).addPathPatterns("/synastry/direct")
		registry.addInterceptor(RateLimitInterceptor(faqsPerMinute)).addPathPatterns("/faqs")
		registry.addInterceptor(RateLimitInterceptor(appConfigPerMinute)).addPathPatterns("/app-config")
		registry.addInterceptor(RateLimitInterceptor(sharedPerMinute)).addPathPatterns("/shared/*")
		registry.addInterceptor(RateLimitInterceptor(authPerMinute)).addPathPatterns("/auth/login", "/auth/signup")
	}
}

private class RateLimitInterceptor(limit: Int) : HandlerInterceptor {
	private val limiter = RateLimiter(limit, WINDOW_MILLIS, System::currentTimeMillis)

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		// 주소는 연결의 상대 주소다. 프록시 뒤에 두게 되면 전달 헤더 설정을 함께 정한다.
		if (limiter.tryAcquire(request.remoteAddr)) return true
		response.status = 429
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		response.writer.write("""{"error":"RATE_LIMITED"}""")
		return false
	}
}
