package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.http.HttpResponse
import kotlin.test.assertEquals

private fun HttpResponse<String>.allowOrigin(): String? = headers().firstValue("Access-Control-Allow-Origin").orElse(null)

/** 관리 화면의 주소([ADMIN_ORIGIN])와 앱의 주소([APP_ORIGIN])를 둘 다 정한 서버. */
@AdminIntegrationTest
class AdminCorsIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	private fun preflight(path: String, origin: String, method: String): HttpResponse<String> = http(
		port, "OPTIONS", path, null, null,
		"Origin", origin, "Access-Control-Request-Method", method, "Access-Control-Request-Headers", "authorization,content-type",
	)

	@Test
	fun `관리 화면의 주소는 admin 아래를 부를 수 있다`() {
		for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
			val response = preflight("/admin/faqs/order", ADMIN_ORIGIN, method)
			assertEquals(200, response.statusCode(), method)
			assertEquals(ADMIN_ORIGIN, response.allowOrigin())
			val headers = response.headers().firstValue("Access-Control-Allow-Headers").orElse("").lowercase()
			assertEquals(true, headers.contains("authorization") && headers.contains("content-type"))
		}
		assertEquals(ADMIN_ORIGIN, preflight("/admin/auth/login", ADMIN_ORIGIN, "POST").allowOrigin())

		val me = http(port, "GET", "/admin/me", null, adminToken(port), "Origin", ADMIN_ORIGIN)
		assertEquals(200, me.statusCode())
		assertEquals(ADMIN_ORIGIN, me.allowOrigin())

		val unauthorized = http(port, "GET", "/admin/me", null, null, "Origin", ADMIN_ORIGIN)
		assertEquals(401, unauthorized.statusCode())
		assertEquals(ADMIN_ORIGIN, unauthorized.allowOrigin(), "화면이 401 을 읽을 수 있어야 한다")
	}

	@Test
	fun `앱의 주소와 모르는 주소는 admin 아래를 부를 수 없다`() {
		val token = adminToken(port)
		for (origin in listOf(APP_ORIGIN, "https://evil.example")) {
			for (path in listOf("/admin/auth/login", "/admin/me", "/admin/inquiries", "/admin/no-such-path")) {
				val asked = preflight(path, origin, "POST")
				assertEquals(403, asked.statusCode(), "$origin $path")
				assertEquals(null, asked.allowOrigin())
			}
			val me = http(port, "GET", "/admin/me", null, token, "Origin", origin)
			assertEquals(403, me.statusCode(), "맞는 토큰이 있어도 다른 출처의 브라우저 요청은 거절한다")
			assertEquals(null, me.allowOrigin())
			val login = http(port, "POST", "/admin/auth/login", json(mapOf("loginId" to ROOT_ID, "password" to ROOT_PASSWORD)), null, "Origin", origin)
			assertEquals(403, login.statusCode())
			assertEquals(false, login.body().contains("token"))
		}
	}

	@Test
	fun `관리 화면의 주소는 앱의 경로를 부를 수 없고 앱의 주소는 그대로 부른다`() {
		for (path in listOf("/charts", "/profile", "/inquiries", "/faqs")) {
			assertEquals(403, preflight(path, ADMIN_ORIGIN, "POST").statusCode(), path)
			assertEquals(APP_ORIGIN, preflight(path, APP_ORIGIN, "POST").allowOrigin(), path)
		}
		val health = http(port, "GET", "/health", null, null, "Origin", ADMIN_ORIGIN)
		assertEquals(null, health.allowOrigin())
		assertEquals(APP_ORIGIN, http(port, "GET", "/health", null, null, "Origin", APP_ORIGIN).allowOrigin())
		// 앱의 경로도 PATCH 를 연다(PATCH /account, 결정 025). 관리 화면의 주소는 여전히 앱의 경로를 부를 수 없다.
		assertEquals(APP_ORIGIN, preflight("/account", APP_ORIGIN, "PATCH").allowOrigin())
		assertEquals(403, preflight("/account", ADMIN_ORIGIN, "PATCH").statusCode())
	}
}
