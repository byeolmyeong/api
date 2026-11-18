package com.byeolmyeong.api.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsUtils
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

private const val OWNER_ATTRIBUTE = "byeolmyeong.ownerId"
private const val TOKEN_ATTRIBUTE = "byeolmyeong.sessionToken"
private const val BEARER = "Bearer "

/** 로그인 전에 쓰는 곳. 여기 없는 경로는 모두 세션이 있어야 한다(새 엔드포인트는 기본이 "세션 필요"다). */
private val PUBLIC_PATHS = arrayOf("/health", "/places", "/auth/dev", "/auth/login", "/invites/*", "/shared/*", "/error")

/** 로그인 전에 쓰는 곳 가운데 읽기(GET)만 여는 경로. 같은 경로의 다른 메서드는 세션이 있어야 한다. */
private val PUBLIC_GET_PATHS = setOf("/faqs", "/app-config")

/**
 * 관리자 API. 사용자 세션 검사는 이 아래를 보지 않는다 — 열어 두는 것이 아니라, 같은 경로 전부를 관리자 세션 검사
 * (`admin.AdminSessionInterceptor`)가 맡는다(결정 014). 사용자 토큰은 거기서 통하지 않는다.
 */
private const val ADMIN_PATHS = "/admin/**"

/**
 * 소유자를 얻는 한 곳. 요청 본문이나 경로의 id 를 소유자로 믿지 않고, 세션 토큰으로 확인한 계정만 돌려준다.
 * 세션이 필요한 엔드포인트의 컨트롤러가 주입받아 쓴다.
 */
@Component
class CurrentOwner(private val request: HttpServletRequest) {

	fun id(): UUID = checkNotNull(request.getAttribute(OWNER_ATTRIBUTE) as UUID?) {
		"세션 검사를 거치지 않은 경로에서 소유자를 물었다"
	}

	/** 이 요청의 세션 토큰(로그아웃이 그 세션을 지울 때). 로그에 남기지 않는다. */
	fun sessionToken(): String = checkNotNull(request.getAttribute(TOKEN_ATTRIBUTE) as String?) {
		"세션 검사를 거치지 않은 경로에서 세션 토큰을 물었다"
	}
}

/**
 * 세션 검사. `Authorization: Bearer <token>` 이 없거나 틀리면 401 `UNAUTHORIZED` 로 끝낸다.
 * 서블릿 필터가 아니라 인터셉터다: 브라우저의 다른 출처 요청에 CORS 허용 헤더가 먼저 붙어야 앱이 401 을 읽을 수 있다.
 */
@Component
class SessionInterceptor(private val auth: AuthService) : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		// 브라우저가 본 요청 전에 묻는 요청에는 세션 헤더가 없다. 허용 여부는 CORS 설정이 답한다.
		if (CorsUtils.isPreFlightRequest(request)) return true
		if (request.method == "GET" && request.requestURI in PUBLIC_GET_PATHS) return true
		val token = request.getHeader("Authorization")?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)
		val owner = token?.let(auth::ownerOf)
		if (owner != null) {
			request.setAttribute(OWNER_ATTRIBUTE, owner)
			request.setAttribute(TOKEN_ATTRIBUTE, token)
			return true
		}
		response.status = 401
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		response.writer.write("""{"error":"UNAUTHORIZED"}""")
		return false
	}
}

@Configuration
class SessionConfig(private val interceptor: SessionInterceptor) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(interceptor).excludePathPatterns(*PUBLIC_PATHS, ADMIN_PATHS)
	}
}
