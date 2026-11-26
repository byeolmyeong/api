package com.byeolmyeong.api.auth

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals

private const val SUSPENDED = """{"error":"ACCOUNT_SUSPENDED"}"""
private const val INQUIRY = """{"category":"ACCOUNT","body":"정지가 잘못된 것 같아요. 확인 부탁드려요."}"""

/**
 * 이용 정지(결정 044): 문의 셋 · 계정 보기 · 로그아웃 · 계정 삭제 말고 모두 403 `ACCOUNT_SUSPENDED`. 점검 503 이 먼저다.
 * 문의 시간당 제한은 기본값(5)을 그대로 본다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["byeolmyeong.app-config.cache-seconds=0"])
class SuspensionIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val mapper = JsonMapper()
	private lateinit var account: UUID
	private lateinit var token: String

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		jdbc.update("update app_maintenance set active = false, message = null")
		account = UUID.randomUUID()
		token = "suspended-session-${UUID.randomUUID()}"
		jdbc.update("insert into accounts (id, provider, provider_user_id, nickname) values (?, 'TEST', ?, '정지된이')", account, account.toString())
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(token), account)
		suspend(true)
	}

	private fun sha256(text: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

	private fun suspend(on: Boolean) {
		if (on) jdbc.update("update accounts set suspended_at = now(), suspension_reason = '테스트' where id = ?", account)
		else jdbc.update("update accounts set suspended_at = null, suspended_by = null, suspension_reason = null where id = ?", account)
	}

	private fun call(method: String, path: String, body: String? = null): HttpResponse<String> = http(port, method, path, body, token)

	@Test
	fun `정지된 계정은 열린 여섯 말고 모두 403 이다 — 없는 경로도`() {
		for ((method, path, body) in listOf(
			Triple("GET", "/profile", null),
			Triple("PUT", "/profile", """{"date":"1990-03-15","time":null,"placeId":null}"""),
			Triple("POST", "/readings", "{}"),
			Triple("GET", "/reading", null),
			Triple("PATCH", "/account", """{"avatar":2}"""),
			Triple("GET", "/auth/session", null),
			Triple("GET", "/auth/analytics-id", null),
			Triple("POST", "/charts", """{"date":"1990-03-15"}"""),
			Triple("GET", "/no-such-path", null),
		)) {
			val response = call(method, path, body)
			assertEquals(403, response.statusCode(), "$method $path")
			assertEquals(SUSPENDED, response.body(), "$method $path")
		}
		assertEquals(null, jdbc.queryForObject("select avatar from accounts where id = ?", Int::class.java, account), "막힌 요청은 아무것도 바꾸지 않는다")
	}

	@Test
	fun `문의 셋과 계정 보기는 열려 있고 계정에 suspended 가 보인다`() {
		val sent = call("POST", "/inquiries", INQUIRY)
		assertEquals(201, sent.statusCode(), sent.body())
		val id = mapper.readTree(sent.body())["id"].asString()

		assertEquals(200, call("GET", "/inquiries").statusCode())
		assertEquals(200, call("GET", "/inquiries/$id").statusCode())
		assertEquals(404, call("GET", "/inquiries/${UUID.randomUUID()}").statusCode(), "열린 경로도 남의 것 · 없는 것은 그대로 404")

		val me = call("GET", "/account")
		assertEquals(200, me.statusCode())
		assertEquals(true, mapper.readTree(me.body())["suspended"].asBoolean())
		assertEquals(false, mapper.readTree(me.body()).has("suspensionReason"), "사유는 앱에 보이지 않는다")
	}

	@Test
	fun `정지돼도 로그아웃할 수 있다`() {
		assertEquals(204, call("DELETE", "/auth/session").statusCode())
		assertEquals(401, call("GET", "/account").statusCode())
	}

	@Test
	fun `정지돼도 스스로 계정을 지울 수 있다`() {
		assertEquals(204, call("DELETE", "/account").statusCode())
		assertEquals(0, jdbc.queryForObject("select count(*) from accounts where id = ?", Int::class.java, account))
	}

	@Test
	fun `풀면 다시 다 되고 계정의 suspended 는 false 다`() {
		assertEquals(403, call("GET", "/profile").statusCode())

		suspend(false)

		assertEquals(404, call("GET", "/profile").statusCode(), "출생 정보가 없어 404(NO_PROFILE) — 막히지 않았다")
		assertEquals(false, mapper.readTree(call("GET", "/account").body())["suspended"].asBoolean())
	}

	@Test
	fun `정지 계정의 문의에도 시간당 제한이 그대로 걸린다`() {
		repeat(5) { assertEquals(201, call("POST", "/inquiries", INQUIRY).statusCode(), "${it + 1}번째") }
		assertEquals(429, call("POST", "/inquiries", INQUIRY).statusCode())
	}

	@Test
	fun `점검 중이면 정지보다 503 이 먼저다`() {
		jdbc.update("update app_maintenance set active = true, message = '점검 중'")
		try {
			// 앱 설정은 캐시라 바로 바뀌지 않을 수 있다 — 관리자 API 없이 바꾼 것이므로 몇 번 본다.
			val until = System.currentTimeMillis() + 5_000
			var status = call("GET", "/profile").statusCode()
			while (status != 503 && System.currentTimeMillis() < until) {
				Thread.sleep(100)
				status = call("GET", "/profile").statusCode()
			}
			assertEquals(503, status)
		} finally {
			jdbc.update("update app_maintenance set active = false, message = null")
		}
	}

	@Test
	fun `정지되지 않은 계정은 그대로다`() {
		suspend(false)
		assertEquals(204, call("GET", "/auth/session").statusCode())
		assertEquals(false, mapper.readTree(call("GET", "/account").body())["suspended"].asBoolean())
	}
}
