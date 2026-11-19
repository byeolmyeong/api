package com.byeolmyeong.api.auth

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals

// 로그인 · 가입의 주소별 요청 수 제한(결정 021). 카카오 코드는 확인 전에 카카오를 부르므로 막지 않으면 부풀릴 수 있다.
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["byeolmyeong.rate-limit.auth-per-minute=2"])
class AuthRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `로그인과 가입을 합쳐 주소별 분당 한도를 넘으면 429 다`() {
		assertEquals(400, http(port, "POST", "/auth/login", """{"provider":"NAVER"}""").statusCode())
		assertEquals(401, http(port, "POST", "/auth/signup", """{"signupToken":"x","date":"1990-01-01"}""").statusCode())

		val limited = http(port, "POST", "/auth/login", """{"provider":"NAVER"}""")
		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
	}
}
