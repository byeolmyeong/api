package com.byeolmyeong.api.appconfig

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsUtils
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 강제 업데이트(결정 016). 앱이 보내는 `X-App-Platform`·`X-App-Version` 을 보고, 그 플랫폼의 막기가 켜져 있고 버전이 최소 버전보다 낮으면
 * 426 `UPDATE_REQUIRED` 로 끝낸다. 헤더가 없는 요청(웹)·모르는 플랫폼·읽을 수 없는 버전은 통과시킨다.
 * 서블릿 필터가 아니라 인터셉터다: 426 에도 CORS 허용 헤더가 붙어야 한다(세션 검사와 같은 이유).
 */
@Component
class AppUpdateInterceptor(private val configs: AppConfigService) : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		// 브라우저가 본 요청 전에 묻는 요청은 건드리지 않는다. 허용 여부는 CORS 설정이 답한다.
		if (CorsUtils.isPreFlightRequest(request)) return true
		if (!configs.blocks(request.getHeader("X-App-Platform"), request.getHeader("X-App-Version"))) return true
		response.status = 426
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		response.writer.write("""{"error":"UPDATE_REQUIRED"}""")
		return false
	}
}

/**
 * 세션 검사보다 먼저 본다(옛 앱은 401 이 아니라 426 을 받는다). 막지 않는 경로: 설정 조회(막힌 앱이 안내를 읽는다), 상태 확인,
 * 관리자 API 전부(관리 화면은 잠기지 않는다 — 잘못 넣은 값을 고칠 곳이다).
 */
@Configuration
class AppUpdateConfig(private val interceptor: AppUpdateInterceptor) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE).excludePathPatterns("/app-config", "/health", "/admin/**", "/error")
	}
}
