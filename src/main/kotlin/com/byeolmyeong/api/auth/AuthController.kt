package com.byeolmyeong.api.auth

import com.fasterxml.jackson.annotation.JsonInclude
import com.byeolmyeong.api.web.BirthRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 세션 토큰. 웹이 쿠키로 받기로 했으면 null 이고 본문에서 빠진다(결정 021). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TokenResponse(val token: String?)

/** 세션을 본문 대신 쿠키로 달라는 요청(웹). */
data class CookieRequest(val cookie: Boolean? = null)

/** `POST /auth/login`. 값 검증은 `LoginService.identify` 가 한다. `redirectUri` 는 카카오 웹(코드와 함께)과 애플 웹(`authorizationCode` 와 함께)이 쓴다. */
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

/** 애플 서버 알림의 본문. */
data class AppleNotificationRequest(val payload: String?)

/**
 * `GET/PATCH /account` 의 응답(결정 025 · 027 · 028 · 042). 없는 값은 null.
 * [todaySkyPush] 는 오늘의 하늘 알림이 실제로 켜졌는지(직접 정하지 않았으면 푸시 수신 동의), [todaySkyTime] 은 받는 한국 시각 `HH:mm`.
 */
data class AccountResponse(
	val nickname: String?,
	val email: String?,
	val provider: String,
	val marketingConsent: Boolean,
	val marketingConsentAt: java.time.Instant?,
	val pushConsent: Boolean,
	val pushConsentAt: java.time.Instant?,
	val avatar: Int?,
	/** 이용 정지(결정 044). 참이면 앱이 정지 화면(앱 결정 065 의 5)을 띄운다. 사유는 주지 않는다. */
	val suspended: Boolean,
	val todaySkyPush: Boolean,
	val todaySkyTime: String,
)

/** `POST /auth/nickname-check`(결정 025). */
data class NicknameCheckRequest(val signupToken: String? = null, val nickname: String? = null)

data class NicknameCheckResponse(val available: Boolean)

/** `PATCH /account`. 보낸 칸만 바꾼다(결정 025 · 027 · 028 · 042). */
data class AccountPatchRequest(
	val nickname: String? = null,
	val marketingConsent: Boolean? = null,
	val pushConsent: Boolean? = null,
	val avatar: Int? = null,
	val todaySkyPush: Boolean? = null,
	val todaySkyTime: String? = null,
)

/**
 * `POST /auth/signup`. 출생 정보는 `PUT /profile` 과 같은 칸이다. 닉네임은 필수, 마케팅 수신 동의는 선택(결정 025).
 * 약관 · 처리방침 버전(결정 026) · 프로필 1~8(결정 028)은 필수, 푸시 수신 동의는 선택(결정 027). 동의 시각은 받지 않는다(서버 시각).
 */
data class SignupRequest(
	val signupToken: String?,
	val date: String?,
	val time: String?,
	val placeId: Int?,
	val nickname: String? = null,
	val avatar: Int? = null,
	val termsVersion: String? = null,
	val privacyVersion: String? = null,
	val pushConsent: Boolean? = null,
	val marketingConsent: Boolean? = null,
	val cookie: Boolean?,
)

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
		return when (val outcome = login.login(identity, request.authorizationCode, request.redirectUri)) {
			is LoginOutcome.SignedIn ->
				LoginResponse("SIGNED_IN", token = deliver(outcome.sessionToken, request.cookie == true, response).token)
			is LoginOutcome.SignupRequired ->
				LoginResponse("SIGNUP_REQUIRED", signupToken = outcome.signupToken, expiresAt = outcome.expiresAt.toString())
		}
	}

	/** 애플 서버 알림(결정 021). 애플이 서명한 `payload` 를 확인하고 처리한다. */
	@PostMapping("/auth/apple/notifications")
	fun appleNotification(@RequestBody request: AppleNotificationRequest): ResponseEntity<Void> {
		auth.appleNotification(request.payload?.takeIf { it.isNotBlank() } ?: throw InvalidNotificationException())
		return ResponseEntity.ok().build()
	}

	/** 가입(결정 021). 지금 약관에 동의하고(결정 026) 만 14세 이상일 때만 계정·출생 정보·세션을 만든다. */
	@PostMapping("/auth/signup")
	fun signup(@RequestBody request: SignupRequest, response: HttpServletResponse): ResponseEntity<TokenResponse> {
		val session = login.signup(
			request.signupToken,
			BirthRequest(request.date, request.time, request.placeId),
			nickname = request.nickname,
			avatar = request.avatar,
			termsVersion = request.termsVersion,
			privacyVersion = request.privacyVersion,
			marketingConsent = request.marketingConsent == true,
			pushConsent = request.pushConsent == true,
		)
		return ResponseEntity.status(HttpStatus.CREATED).body(deliver(session, request.cookie == true, response))
	}

	/** 가입 전에 닉네임을 쓸 수 있는지(결정 025). 가입권이 있어야 한다. 영문 대소문자는 같은 이름이다. */
	@PostMapping("/auth/nickname-check")
	fun nicknameCheck(@RequestBody request: NicknameCheckRequest): NicknameCheckResponse =
		NicknameCheckResponse(login.nicknameAvailable(request.signupToken, request.nickname))

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

	/** 계정 화면의 값(결정 025). 세션의 주인 것만. */
	@GetMapping("/account")
	fun account(): AccountResponse = auth.account(owner.id())

	/** 닉네임·수신 동의(마케팅 · 푸시)·프로필을 바꾼다(결정 025 · 027 · 028). 보낸 칸만. */
	@PatchMapping("/account")
	fun updateAccount(@RequestBody request: AccountPatchRequest): AccountResponse =
		auth.updateAccount(owner.id(), request)

	/** 계정 삭제. 세션의 주인만 지운다. 웹의 쿠키도 지운다. */
	@DeleteMapping("/account")
	fun delete(response: HttpServletResponse): ResponseEntity<Void> {
		auth.deleteAccount(owner.id())
		sessionCookie.clear(response)
		return ResponseEntity.noContent().build()
	}

	/** 웹이면 쿠키로 주고 본문에는 토큰을 넣지 않는다. 앱이면 본문으로 준다. */
	private fun deliver(token: String, cookie: Boolean, response: HttpServletResponse): TokenResponse {
		if (!cookie) return TokenResponse(token)
		sessionCookie.write(response, token)
		return TokenResponse(null)
	}
}
