package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val HEX32 = Regex("^[0-9a-f]{32}$")

/** 앱 결정 040 · 081: 분석 도구에는 계정 id 대신 되돌릴 수 없는 분석용 id 를 보낸다. 서버가 `GET /auth/analytics-id` 로 준다. */
class AnalyticsIdTest {

	@Test
	fun `같은 계정은 늘 같은 값, 다른 계정은 다른 값이고 32자 16진수다`() {
		val a = UUID.fromString("3f2a9c1e-1111-4000-8000-000000000001")
		val b = UUID.fromString("3f2a9c1e-1111-4000-8000-000000000002")
		assertEquals(analyticsIdOf(a), analyticsIdOf(a))
		assertNotEquals(analyticsIdOf(a), analyticsIdOf(b))
		assertTrue(HEX32.matches(analyticsIdOf(a)))
	}

	@Test
	fun `계정 id 가 그대로 들어 있지 않다`() {
		val id = UUID.randomUUID()
		val analyticsId = analyticsIdOf(id)
		assertFalse(analyticsId.contains(id.toString().replace("-", "").take(12)))
	}
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class AnalyticsIdIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Test
	fun `세션이 있으면 200 과 분석용 id 를 준다 — 두 번 불러도 같다`() {
		val token = devToken(port)
		val first = http(port, "GET", "/auth/analytics-id", token = token)
		val second = http(port, "GET", "/auth/analytics-id", token = token)

		assertEquals(200, first.statusCode())
		val node = JsonMapper().readTree(first.body())
		assertEquals(listOf("analyticsId"), node.propertyNames().toList())
		val analyticsId = node["analyticsId"].asString()
		assertTrue(HEX32.matches(analyticsId))
		assertEquals(first.body(), second.body())
		val owner = jdbc.queryForObject("select id from accounts where provider = 'DEV'", UUID::class.java)!!
		assertEquals(analyticsIdOf(owner), analyticsId)
		assertFalse(first.body().contains(owner.toString()), "계정 id 는 응답에 나오지 않는다")
	}

	@Test
	fun `세션이 없으면 401 이다`() {
		assertEquals(401, http(port, "GET", "/auth/analytics-id").statusCode())
	}

	@Test
	fun `세션 확인은 그대로 204 다 — 더하기만 해서 옛 앱이 깨지지 않는다(결정 081)`() {
		assertEquals(204, http(port, "GET", "/auth/session", token = devToken(port)).statusCode())
	}
}
