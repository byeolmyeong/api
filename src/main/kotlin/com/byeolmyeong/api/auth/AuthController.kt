package com.byeolmyeong.api.auth

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

data class TokenResponse(val token: String)

@RestController
class AuthController(private val auth: AuthService, private val owner: CurrentOwner) {

	/** 개발용 로그인(결정 005). 설정이 꺼져 있으면(운영) 없는 경로처럼 404 다. */
	@PostMapping("/auth/dev")
	fun dev(): ResponseEntity<TokenResponse> =
		auth.devLogin()?.let { ResponseEntity.ok(TokenResponse(it)) } ?: ResponseEntity.notFound().build()

	/** 세션이 살아 있는지(웹이 쿠키 세션을 확인할 때). 세션 검사를 지났으면 204. */
	@GetMapping("/auth/session")
	fun session(): ResponseEntity<Void> = ResponseEntity.noContent().build()

	/** 로그아웃. 이 세션만 지운다(결정 020). */
	@DeleteMapping("/auth/session")
	fun logout(): ResponseEntity<Void> {
		auth.logout(owner.sessionToken())
		return ResponseEntity.noContent().build()
	}

	/** 계정 삭제. 세션의 주인만 지운다. */
	@DeleteMapping("/account")
	fun delete(): ResponseEntity<Void> {
		auth.deleteAccount(owner.id())
		return ResponseEntity.noContent().build()
	}
}
