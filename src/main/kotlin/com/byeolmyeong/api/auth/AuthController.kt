package com.byeolmyeong.api.auth

import com.fasterxml.jackson.annotation.JsonInclude
import com.byeolmyeong.api.web.BirthRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 세션 토큰. 웹이 쿠키로 받기로 했으면 null 이고 본문에서 빠진다(결정 021). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TokenResponse(val token: String?)

/** 세션을 본문 대신 쿠키로 달라는 요청(웹). */
data class CookieRequest(val cookie: Boolean? = null)

/** `POST /auth/login`. 값 검증은 `LoginService.identify` 가 한다. `authorizationCode`(애플)는 S7 이 쓴다. */
data class LoginRequest(
	val provider: String?,
	val idToken: String?,
	val code: String?,
	val redirectUri: String?,
	val authorizationCode: String?,
	val cookie: Boolean?,
)

/** `POST /auth/login` 의 응답. `SIGNED_IN` 이면 `token`(쿠키면 없음), `SIGNUP_REQUIRED` 면 `signupToken`·`expiresAt`. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LoginResponse(val status: String, val token: String? = null, val signupToken: String? = null, val expiresAt: String? = null)

/** `POST /auth/signup`. 출생 정보는 `PUT /profile` 과 같은 칸이다. */
data class SignupRequest(val signupToken: String?, val date: String?, val time: String?, val placeId: Int?, val cookie: Boolean?)

@RestController
class AuthController(
	private val auth: AuthService,
	private val login: LoginService,
	private val owner: CurrentOwner,
	private val sessionCookie: SessionCookie,
) {

	/** 소셜 로그인(결정 021). 계정이 있으면 세션, 없으면 가입권. */
	@PostMapping("/auth/login")
	fun login(@RequestBody request: LoginRequest, response: HttpServletResponse): LoginResponse {
		val identity = login.identify(ProviderCredential(request.provider, request.idToken, request.code, request.redirectUri))
		return when (val outcome = login.login(identity)) {
			is LoginOutcome.SignedIn ->
				LoginResponse("SIGNED_IN", token = deliver(outcome.sessionToken, request.cookie == true, response).token)
			is LoginOutcome.SignupRequired ->
				LoginResponse("SIGNUP_REQUIRED", signupToken = outcome.signupToken, expiresAt = outcome.expiresAt.toString())
		}
	}

	/** 가입(결정 021). 만 14세 이상일 때만 계정·출생 정보·세션을 만든다. */
	@PostMapping("/auth/signup")
	fun signup(@RequestBody request: SignupRequest, response: HttpServletResponse): ResponseEntity<TokenResponse> {
		val session = login.signup(request.signupToken, BirthRequest(request.date, request.time, request.placeId))
		return ResponseEntity.status(HttpStatus.CREATED).body(deliver(session, request.cookie == true, response))
	}

	/** 개발용 로그인(결정 005). 설정이 꺼져 있으면(운영) 없는 경로처럼 404 다. */
	@PostMapping("/auth/dev")
	fun dev(@RequestBody(required = false) body: CookieRequest?, response: HttpServletResponse): ResponseEntity<TokenResponse> {
		val token = auth.devLogin() ?: return ResponseEntity.notFound().build()
		return ResponseEntity.ok(deliver(token, body?.cookie == true, response))
	}

	/** 세션이 살아 있는지(웹이 쿠키 세션을 확인할 때). 세션 검사를 지났으면 204. */
	@GetMapping("/auth/session")
	fun session(): ResponseEntity<Void> = ResponseEntity.noContent().build()

	/** 로그아웃. 이 세션만 지운다(결정 021). */
	@DeleteMapping("/auth/session")
	fun logout(response: HttpServletResponse): ResponseEntity<Void> {
		auth.logout(owner.sessionToken())
		sessionCookie.clear(response)
		return ResponseEntity.noContent().build()
	}

	/** 계정 삭제. 세션의 주인만 지운다. */
	@DeleteMapping("/account")
	fun delete(): ResponseEntity<Void> {
		auth.deleteAccount(owner.id())
		return ResponseEntity.noContent().build()
	}

	/** 웹이면 쿠키로 주고 본문에는 토큰을 넣지 않는다. 앱이면 본문으로 준다. */
	private fun deliver(token: String, cookie: Boolean, response: HttpServletResponse): TokenResponse {
		if (!cookie) return TokenResponse(token)
		sessionCookie.write(response, token)
		return TokenResponse(null)
	}
}
