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
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 세션 만료(마지막 사용 후 90일)와 로그아웃. 결정 021.
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class SessionExpiryIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private fun hash(token: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

	private fun lastUsedDaysAgo(token: String, days: Int) {
		jdbc.update("update sessions set last_used_at = now() - make_interval(days => ?) where token_hash = ?", days, hash(token))
	}

	private fun lastUsed(token: String): Timestamp? =
		jdbc.queryForList("select last_used_at from sessions where token_hash = ?", Timestamp::class.java, hash(token)).firstOrNull()

	@Test
	fun `마지막 사용 후 90일이 지난 세션은 401 이고 그 세션 행을 지운다`() {
		val token = devToken(port)
		lastUsedDaysAgo(token, 91)

		assertEquals(401, http(port, "GET", "/auth/session", token = token).statusCode())
		assertEquals(null, lastUsed(token), "만료된 세션 행이 남았다")
	}

	@Test
	fun `90일이 안 된 세션은 통하고 마지막 사용 시각을 지금으로 고친다`() {
		val token = devToken(port)
		lastUsedDaysAgo(token, 89)

		assertEquals(204, http(port, "GET", "/auth/session", token = token).statusCode())
		val after = lastUsed(token)!!
		assertTrue(after.toInstant().isAfter(java.time.Instant.now().minusSeconds(60)), "마지막 사용이 고쳐지지 않았다: $after")
	}

	@Test
	fun `마지막 사용이 하루 안이면 시각을 고쳐 쓰지 않는다`() {
		val token = devToken(port)
		jdbc.update("update sessions set last_used_at = now() - interval '1 hour' where token_hash = ?", hash(token))
		val before = lastUsed(token)

		assertEquals(204, http(port, "GET", "/auth/session", token = token).statusCode())
		assertEquals(before, lastUsed(token))
	}

	@Test
	fun `로그아웃은 그 세션만 지운다`() {
		val token = devToken(port)
		val other = devToken(port) // 같은 개발용 계정의 다른 기기

		assertEquals(204, http(port, "DELETE", "/auth/session", token = token).statusCode())

		assertEquals(401, http(port, "GET", "/auth/session", token = token).statusCode())
		assertEquals(204, http(port, "GET", "/auth/session", token = other).statusCode())
	}

	@Test
	fun `세션 확인과 로그아웃은 세션이 없으면 401 이다`() {
		assertEquals(401, http(port, "GET", "/auth/session").statusCode())
		assertEquals(401, http(port, "DELETE", "/auth/session").statusCode())
	}
}
