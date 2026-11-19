package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals

/**
 * 결정 022: 생성 경로의 계정별 제한이 실제 서버에서 세션 검사 다음에 돈다.
 * 출생 정보 없이 부르면 컨트롤러는 404 를 주지만, 제한은 컨트롤러 전에 세므로 한도를 넘은 요청은 429 다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON,
		// 고정 창이라 경계를 걸치면 세는 수가 처음부터 다시 간다. 분 창(1분마다 경계)이 아니라 시간 창으로 한도를 확인한다.
		"byeolmyeong.rate-limit.generation-per-minute=100",
		"byeolmyeong.rate-limit.generation-per-hour=2",
	],
)
class GenerationRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `세션 없는 생성 요청은 세지 않고 401 이다`() {
		repeat(4) { assertEquals(401, http(port, "POST", "/directions", """{}""").statusCode()) }
	}

	@Test
	fun `한 계정이 생성 경로를 합쳐 한도를 넘으면 429 다`() {
		val token = devToken(port)
		val first = http(port, "POST", "/readings", """{}""", token).statusCode()
		val second = http(port, "POST", "/reports", """{}""", token).statusCode()
		val third = http(port, "POST", "/synastry/direct", """{}""", token)
		assertEquals(false, first == 429 || second == 429, "한도 안의 요청은 429 가 아니다: $first $second")
		assertEquals(429, third.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", third.body())
		assertEquals(200, http(port, "GET", "/faqs").statusCode())
	}
}
