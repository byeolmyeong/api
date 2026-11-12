package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.BadRequestException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

data class AdminLoginResponse(val token: String, val expiresAt: String, val admin: Admin)

/** 관리자 로그인·로그아웃·나(결정 014). 로그인만 세션 없이 열려 있다. */
@RestController
class AdminAuthController(private val auth: AdminAuthService, private val current: CurrentAdmin) {

	@PostMapping("/admin/auth/login")
	fun login(@RequestBody body: JsonNode, request: HttpServletRequest): AdminLoginResponse {
		body.requireObject()
		val loginId = body.textOrNull("loginId") ?: throw BadRequestException("INVALID_REQUEST")
		val password = body.textOrNull("password") ?: throw BadRequestException("INVALID_REQUEST")
		// 주소는 연결의 상대 주소다(web.RateLimitConfig 와 같다).
		val login = auth.login(loginId, password, request.remoteAddr)
		return AdminLoginResponse(login.token, login.expiresAt.utcSeconds(), login.admin)
	}

	@PostMapping("/admin/auth/logout")
	fun logout(): ResponseEntity<Void> {
		auth.logout(current.token())
		return ResponseEntity.noContent().build()
	}

	@GetMapping("/admin/me")
	fun me(): Admin = current.get()

	@PutMapping("/admin/me/password")
	fun changePassword(@RequestBody body: JsonNode): ResponseEntity<Void> {
		body.requireObject()
		val currentPassword = body.textOrNull("currentPassword") ?: throw BadRequestException("INVALID_REQUEST")
		val newPassword = body.textOrNull("newPassword") ?: throw BadRequestException("INVALID_REQUEST")
		auth.changePassword(current.get(), current.token(), currentPassword, newPassword)
		return ResponseEntity.noContent().build()
	}
}
