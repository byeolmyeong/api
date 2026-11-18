package com.byeolmyeong.api.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

private const val NAME = "bm_session"

/** 브라우저가 쿠키를 들고 있을 수 있는 가장 긴 기간. 실제 만료는 서버가 정한다(마지막 사용 후 90일, 결정 021). */
private val MAX_AGE = Duration.ofDays(400)

/**
 * 웹의 세션 쿠키(결정 021)를 쓰고 지우고 읽는 한 곳. `HttpOnly; SameSite=Lax; Path=/`, Domain 속성 없이 이 서버 주소에만 간다.
 * `Secure` 는 로컬 http 개발에서만 끈다(`SESSION_COOKIE_SECURE=false`). 쿠키 값(세션 토큰)은 로그에 남기지 않는다.
 */
@Component
class SessionCookie(@Value("\${byeolmyeong.auth.cookie-secure}") private val secure: Boolean) {

	fun write(response: HttpServletResponse, token: String) = add(response, token, MAX_AGE)

	fun clear(response: HttpServletResponse) = add(response, "", Duration.ZERO)

	/** 요청의 세션 쿠키 값. 없거나 비었으면 null. */
	fun read(request: HttpServletRequest): String? =
		request.cookies?.firstOrNull { it.name == NAME }?.value?.takeIf { it.isNotEmpty() }

	private fun add(response: HttpServletResponse, value: String, maxAge: Duration) {
		val cookie = ResponseCookie.from(NAME, value)
			.httpOnly(true)
			.secure(secure)
			.sameSite("Lax")
			.path("/")
			.maxAge(maxAge)
			.build()
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
	}
}
