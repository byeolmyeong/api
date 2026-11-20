package com.byeolmyeong.api.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsUtils
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

private const val ADMIN_ATTRIBUTE = "byeolmyeong.admin"
private const val TOKEN_ATTRIBUTE = "byeolmyeong.adminToken"
private const val BEARER = "Bearer "

/** 로그인한 관리자를 얻는 한 곳. 요청 본문이나 경로의 id 를 "누가 했는지" 로 믿지 않는다. */
@Component
class CurrentAdmin(private val request: HttpServletRequest) {

	fun get(): Admin = checkNotNull(request.getAttribute(ADMIN_ATTRIBUTE) as Admin?) {
		"관리자 세션 검사를 거치지 않은 경로에서 관리자를 물었다"
	}

	/** 이 요청이 들고 온 토큰(로그아웃·비밀번호 변경이 "이 세션" 을 가릴 때 쓴다). */
	fun token(): String = checkNotNull(request.getAttribute(TOKEN_ATTRIBUTE) as String?)
}

/** 임시 비밀번호로 들어온 관리자가 바꾸기 전에 쓸 수 있는 곳: "메서드 + 경로"(결정 043 의 5). */
private val OPEN_BEFORE_CHANGE = setOf("PUT /admin/me/password", "POST /admin/auth/logout", "GET /admin/me")

/**
 * 관리자 세션 검사. `/admin/` 아래는 로그인 말고 전부 `Authorization: Bearer <관리자 토큰>` 이 있어야 한다.
 * 없거나 틀리거나 끝났거나 꺼진 관리자면 401 `UNAUTHORIZED`. 앱 사용자의 세션 토큰은 여기서 통하지 않는다(테이블이 다르다).
 * 임시 비밀번호로 들어온 관리자는 [OPEN_BEFORE_CHANGE] 말고는 403 `PASSWORD_CHANGE_REQUIRED`(없는 경로도).
 */
@Component
class AdminSessionInterceptor(private val auth: AdminAuthService) : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		// 브라우저가 본 요청 전에 묻는 요청에는 세션 헤더가 없다. 허용 여부는 CORS 설정이 답한다.
		if (CorsUtils.isPreFlightRequest(request)) return true
		val token = request.getHeader("Authorization")?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)
		val admin = token?.let(auth::adminOf)
		if (admin == null) return reject(response, 401, "UNAUTHORIZED")
		if (admin.mustChangePassword && "${request.method} ${request.requestURI}" !in OPEN_BEFORE_CHANGE) {
			return reject(response, 403, "PASSWORD_CHANGE_REQUIRED")
		}
		request.setAttribute(ADMIN_ATTRIBUTE, admin)
		request.setAttribute(TOKEN_ATTRIBUTE, token)
		return true
	}

	private fun reject(response: HttpServletResponse, status: Int, code: String): Boolean {
		response.status = status
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		response.writer.write("""{"error":"$code"}""")
		return false
	}
}

/** `/admin/` 아래 전부(없는 경로까지)에 관리자 세션 검사를 건다. 로그인만 뺀다. 사용자 세션 검사(`auth`)는 이 아래를 보지 않는다. */
@Configuration
class AdminSessionConfig(private val interceptor: AdminSessionInterceptor) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(interceptor).addPathPatterns("/admin/**").excludePathPatterns("/admin/auth/login")
	}
}
