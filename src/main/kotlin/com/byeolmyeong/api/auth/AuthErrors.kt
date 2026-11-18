package com.byeolmyeong.api.auth

import com.byeolmyeong.api.web.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 로그인·가입의 오류 응답(결정 021, docs/architecture.md "로그인과 가입"). 응답에 토큰이나 입력값을 넣지 않는다. */
@RestControllerAdvice
class AuthErrors {

	@ExceptionHandler(InvalidIdTokenException::class)
	fun invalidIdToken() = error(401, "INVALID_ID_TOKEN")

	@ExceptionHandler(InvalidCodeException::class)
	fun invalidCode() = error(401, "INVALID_CODE")

	@ExceptionHandler(SignupExpiredException::class)
	fun signupExpired() = error(401, "SIGNUP_EXPIRED")

	@ExceptionHandler(UnderAgeException::class)
	fun underAge() = error(403, "UNDER_AGE")

	@ExceptionHandler(ProviderUnavailableException::class)
	fun providerUnavailable() = error(502, "PROVIDER_UNAVAILABLE")

	private fun error(status: Int, code: String): ResponseEntity<ErrorResponse> = ResponseEntity.status(status).body(ErrorResponse(code))
}
