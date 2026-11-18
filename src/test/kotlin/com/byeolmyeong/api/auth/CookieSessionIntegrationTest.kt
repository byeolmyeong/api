package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val APP = "http://localhost:8081"
private const val ADMIN = "http://localhost:5173"
private const val PROFILE = """{"date":"1990-03-15","time":"14:30","placeId":1841277}"""
private const val FORBIDDEN_ORIGIN = """{"error":"FORBIDDEN_ORIGIN"}"""

private fun HttpResponse<String>.sessionSetCookie(): String? =
	headers().allValues("Set-Cookie").firstOrNull { it.startsWith("bm_session=") }

/** `Set-Cookie: bm_session=<값>; …` 에서 값만. */
private fun HttpResponse<String>.sessionCookieValue(): String =
	checkNotNull(sessionSetCookie()) { "bm_session 쿠키가 없다" }.substringAfter("bm_session=").substringBefore(";")

private fun cookieLogin(port: Int): String {
	val response = http(port, "POST", "/auth/dev", """{"cookie":true}""")
	check(response.statusCode() == 200) { "개발용 로그인 실패: ${response.statusCode()}" }
	return response.sessionCookieValue()
}

// 웹의 쿠키 세션(결정 020).
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON,
		"byeolmyeong.cors.allowed-origins=$APP",
		"byeolmyeong.cors.admin-allowed-origins=$ADMIN",
	],
)
class CookieSessionIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `cookie 를 달라고 하면 토큰을 본문에 넣지 않고 httpOnly 쿠키로만 준다`() {
		val response = http(port, "POST", "/auth/dev", """{"cookie":true}""")

		assertEquals(200, response.statusCode())
		assertEquals("{}", response.body())
		val cookie = assertNotNull(response.sessionSetCookie())
		for (attribute in listOf("HttpOnly", "Secure", "SameSite=Lax", "Path=/")) {
			assertTrue(cookie.contains(attribute), "$attribute 가 없다: $cookie")
		}
		assertFalse(cookie.contains("Domain="), "Domain 속성은 두지 않는다: $cookie")
	}

	@Test
	fun `본문 없이 부르면 지금처럼 토큰을 본문으로 주고 쿠키는 주지 않는다`() {
		val response = http(port, "POST", "/auth/dev")
		assertEquals(200, response.statusCode())
		assertTrue(response.body().contains("\"token\""))
		assertEquals(null, response.sessionSetCookie())
	}

	@Test
	fun `쿠키만으로 세션이 필요한 곳에 들어간다`() {
		val cookie = cookieLogin(port)
		assertEquals(204, http(port, "GET", "/auth/session", null, null, "Cookie", "bm_session=$cookie").statusCode())
		assertEquals(401, http(port, "GET", "/auth/session", null, null, "Cookie", "bm_session=not-a-real-token").statusCode())
	}

	@Test
	fun `쿠키로 인증된 GET 이 아닌 요청은 허용한 Origin 이 있어야 한다`() {
		val cookie = cookieLogin(port)

		val noOrigin = http(port, "PUT", "/profile", PROFILE, null, "Cookie", "bm_session=$cookie")
		assertEquals(403, noOrigin.statusCode())
		assertEquals(FORBIDDEN_ORIGIN, noOrigin.body())

		assertEquals(403, http(port, "PUT", "/profile", PROFILE, null, "Cookie", "bm_session=$cookie", "Origin", "https://evil.example").statusCode())
		assertEquals(403, http(port, "PUT", "/profile", PROFILE, null, "Cookie", "bm_session=$cookie", "Origin", "null").statusCode())

		assertEquals(200, http(port, "PUT", "/profile", PROFILE, null, "Cookie", "bm_session=$cookie", "Origin", APP).statusCode())
	}

	@Test
	fun `헤더 토큰 요청은 Origin 이 없어도 된다`() {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", PROFILE, token).statusCode())
	}

	@Test
	fun `로그아웃은 쿠키 세션을 지우고 쿠키도 지운다`() {
		val cookie = cookieLogin(port)

		val response = http(port, "DELETE", "/auth/session", null, null, "Cookie", "bm_session=$cookie", "Origin", APP)
		assertEquals(204, response.statusCode())
		val cleared = assertNotNull(response.sessionSetCookie())
		assertTrue(cleared.contains("Max-Age=0"), "쿠키를 지우지 않았다: $cleared")

		assertEquals(401, http(port, "GET", "/auth/session", null, null, "Cookie", "bm_session=$cookie").statusCode())
	}

	@Test
	fun `앱 경로는 자격 증명을 허용하고 관리자 경로는 허용하지 않는다`() {
		val app = http(port, "OPTIONS", "/profile", null, null, "Origin", APP, "Access-Control-Request-Method", "PUT")
		assertEquals("true", app.headers().firstValue("Access-Control-Allow-Credentials").orElse(null))

		val admin = http(port, "OPTIONS", "/admin/me", null, null, "Origin", ADMIN, "Access-Control-Request-Method", "GET")
		assertEquals(200, admin.statusCode())
		assertEquals(null, admin.headers().firstValue("Access-Control-Allow-Credentials").orElse(null))
	}

	@Test
	fun `사용자 쿠키로는 관리자 API 에 들어갈 수 없다`() {
		val cookie = cookieLogin(port)
		assertEquals(401, http(port, "GET", "/admin/me", null, null, "Cookie", "bm_session=$cookie").statusCode())
	}
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.auth.cookie-secure=false"],
)
class CookieInsecureForLocalIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `로컬 http 설정에서는 Secure 없이 준다`() {
		val cookie = assertNotNull(http(port, "POST", "/auth/dev", """{"cookie":true}""").sessionSetCookie())
		assertFalse(cookie.contains("Secure"), cookie)
		assertTrue(cookie.contains("HttpOnly"), cookie)
	}
}
