package com.byeolmyeong.api.sky

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
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LIMIT = 2

/**
 * `GET /timing/month` 의 계정별 분당 한도(결정 032 의 5, `byeolmyeong.rate-limit.month-per-minute`). 생성 한도와 따로다.
 * 창은 분마다 바뀌므로, 창이 한 번 바뀌어도 한도를 넘는 요청이 나오도록 [LIMIT] × 2 + 1 번까지 보낸다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.month-per-minute=$LIMIT"],
)
class MonthSkyRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Test
	fun `계정마다 분당 한도를 넘으면 429 이고 다른 계정은 그대로 받는다`() {
		jdbc.update("delete from accounts")
		val token = devToken(port)
		// 출생 정보가 없는 계정: 계산 없이 404 로 빨리 답하지만 한도는 센다
		val statuses = (1..LIMIT * 2 + 1).map { http(port, "GET", "/timing/month", token = token) }
		assertTrue(statuses.take(LIMIT).all { it.statusCode() == 404 }, statuses.map { it.statusCode() }.toString())
		val limited = statuses.firstOrNull { it.statusCode() == 429 }
		assertEquals("""{"error":"RATE_LIMITED"}""", limited?.body(), statuses.map { it.statusCode() }.toString())

		// 개발용 로그인은 늘 같은 계정이라 다른 계정은 세션을 직접 넣는다
		val other = "another-accounts-session-token"
		val otherId = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", otherId)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(other), otherId)
		assertEquals(404, http(port, "GET", "/timing/month", token = other).statusCode(), "다른 계정은 따로 센다")
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
