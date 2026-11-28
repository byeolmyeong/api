package com.byeolmyeong.api.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.cors.CorsUtils
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

private const val OWNER_ATTRIBUTE = "byeolmyeong.ownerId"

/** 세션 검사가 확인한 소유자를 요청에 적는다. 같은 패키지의 다른 인터셉터(생성 속도 제한)가 [ownerOf] 로 읽는다. */
internal fun markOwner(request: HttpServletRequest, owner: UUID) = request.setAttribute(OWNER_ATTRIBUTE, owner)

/** 세션 검사를 거친 요청의 소유자. 세션이 없는 경로면 null. */
internal fun ownerOf(request: HttpServletRequest): UUID? = request.getAttribute(OWNER_ATTRIBUTE) as UUID?
private const val TOKEN_ATTRIBUTE = "byeolmyeong.sessionToken"
private const val BEARER = "Bearer "

/** 쿠키로 인증돼도 `Origin` 을 보지 않는 메서드. 나머지는 상태를 바꾸므로 허용한 주소에서 온 것이어야 한다(결정 021). */
private val SAFE_METHODS = setOf("GET", "HEAD")

/**
 * 로그인 전에 쓰는 곳: "메서드 + 경로". 여기 없는 요청은 모두 세션이 있어야 한다(새 엔드포인트는 기본이 "세션 필요"다).
 * 경로만 열면 같은 경로에 나중에 생긴 다른 메서드도 세션 없이 열리므로 메서드를 함께 적는다(배포 전 확인 5). HEAD 는 GET 과 같이 연다.
 * 초대 경로(`/invites/…`)는 만들 때 필요한 메서드만 더한다.
 */
private val PUBLIC_ENDPOINTS = listOf(
	"GET" to "/health",
	"GET" to "/places",
	"GET" to "/faqs",
	"GET" to "/notices",
	"GET" to "/app-config",
	"GET" to "/shared/*",
	"POST" to "/auth/dev",
	"POST" to "/auth/login",
	"POST" to "/auth/signup",
	"POST" to "/auth/nickname-check",
	"POST" to "/auth/apple/notifications",
)

private val PATH_MATCHER = AntPathMatcher()

/**
 * 정지된 계정(결정 044 의 2)이 쓸 수 있는 곳: "메서드 + 경로". 문의 셋(잘못된 정지를 바로잡을 연락 길) · 계정 보기(정지 화면을 그린다) ·
 * 로그아웃 · 계정 삭제(스스로 떠날 수 있다). 여기 없는 요청은 모두 403 `ACCOUNT_SUSPENDED` 다(새 엔드포인트도 기본이 막힘). HEAD 는 GET 과 같다.
 */
private val OPEN_WHEN_SUSPENDED = listOf(
	"POST" to "/inquiries",
	"GET" to "/inquiries",
	"GET" to "/inquiries/*",
	"GET" to "/account",
	"DELETE" to "/auth/session",
	"DELETE" to "/account",
	// 로그아웃할 때 앱이 기기 토큰도 지운다(결정 042 — 정지 중 로그아웃한 기기 잠금 화면에 알림이 남지 않게)
	"DELETE" to "/push/devices/*",
)

private fun matchesAny(endpoints: List<Pair<String, String>>, request: HttpServletRequest): Boolean {
	val method = if (request.method == "HEAD") "GET" else request.method
	return endpoints.any { (allowed, pattern) -> allowed == method && PATH_MATCHER.match(pattern, request.requestURI) }
}

private fun isPublic(request: HttpServletRequest): Boolean = matchesAny(PUBLIC_ENDPOINTS, request)

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

	fun id(): UUID = checkNotNull(ownerOf(request)) {
		"세션 검사를 거치지 않은 경로에서 소유자를 물었다"
	}

	/** 이 요청의 세션 토큰(로그아웃이 그 세션을 지울 때). 로그에 남기지 않는다. */
	fun sessionToken(): String = checkNotNull(request.getAttribute(TOKEN_ATTRIBUTE) as String?) {
		"세션 검사를 거치지 않은 경로에서 세션 토큰을 물었다"
	}
}

/**
 * 세션 검사. `Authorization: Bearer <token>` 이 없으면 웹의 쿠키 `bm_session` 을 읽는다(결정 021). 없거나 틀리면 401 `UNAUTHORIZED`.
 * 쿠키로 인증된 GET 이 아닌 요청은 `Origin` 이 앱의 허용 주소(`byeolmyeong.cors.allowed-origins`)여야 하고, 아니면 403 `FORBIDDEN_ORIGIN` —
 * 브라우저는 다른 사이트에서 연 요청에도 쿠키를 실어 보낼 수 있다. 정지된 계정은 [OPEN_WHEN_SUSPENDED] 말고 403 `ACCOUNT_SUSPENDED`(결정 044).
 * 서블릿 필터가 아니라 인터셉터다: 브라우저의 다른 출처 요청에 CORS 허용 헤더가 먼저 붙어야 앱이 401 을 읽을 수 있다.
 */
@Component
class SessionInterceptor(
	private val auth: AuthService,
	private val cookie: SessionCookie,
	@Value("\${byeolmyeong.cors.allowed-origins:}") allowedOrigins: List<String>,
) : HandlerInterceptor {

	private val origins = allowedOrigins.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		// 브라우저가 본 요청 전에 묻는 요청에는 세션 헤더가 없다. 허용 여부는 CORS 설정이 답한다.
		if (CorsUtils.isPreFlightRequest(request)) return true
		if (isPublic(request)) return true
		val bearer = request.getHeader("Authorization")?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)
		val token = bearer ?: cookie.read(request)
		if (bearer == null && token != null && request.method !in SAFE_METHODS && request.getHeader("Origin") !in origins) {
			return reject(response, 403, "FORBIDDEN_ORIGIN")
		}
		val owner = token?.let(auth::sessionOf) ?: return reject(response, 401, "UNAUTHORIZED")
		// 정지 검사는 세션 검사 바로 뒤다(점검 503 · 강제 업데이트 426 은 그보다 먼저 — 결정 044 의 4).
		if (owner.suspended && !matchesAny(OPEN_WHEN_SUSPENDED, request)) return reject(response, 403, "ACCOUNT_SUSPENDED")
		markOwner(request, owner.accountId)
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

@Configuration
class SessionConfig(private val interceptor: SessionInterceptor) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(interceptor).excludePathPatterns("/error", ADMIN_PATHS)
	}
}
