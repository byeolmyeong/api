package com.byeolmyeong.api.auth

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletResponse
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

@RestController
class AuthController(
	private val auth: AuthService,
	private val owner: CurrentOwner,
	private val sessionCookie: SessionCookie,
) {

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
