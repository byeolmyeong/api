package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 결정 025: 계정의 닉네임·이메일·공급자·마케팅 수신 동의를 보고(GET) 닉네임·동의를 바꾼다(PATCH). 소유자는 세션에서만. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class AccountApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val mapper = JsonMapper()

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
	}

	private fun json(body: String): JsonNode = mapper.readTree(body)
	private fun get(token: String) = http(port, "GET", "/account", token = token)
	private fun patch(token: String, body: String) = http(port, "PATCH", "/account", body, token)

	@Test
	fun `계정 정보를 준다 — 옛 계정(닉네임·이메일 없음)은 null 이다`() {
		val response = get(devToken(port))
		assertEquals(200, response.statusCode())
		val node = json(response.body())
		assertEquals(listOf("nickname", "email", "provider", "marketingConsent", "marketingConsentAt"), node.propertyNames().toList())
		assertTrue(node["nickname"].isNull)
		assertTrue(node["email"].isNull)
		assertEquals("DEV", node["provider"].asString())
		assertEquals(false, node["marketingConsent"].asBoolean())
		assertTrue(node["marketingConsentAt"].isNull)
	}

	@Test
	fun `다른 사람이 쓰는 닉네임으로는 바꿀 수 없다 — 영문 대소문자도 같은 이름이고, 내 닉네임의 대소문자만 바꾸는 건 된다`() {
		jdbc.update("insert into accounts (id, provider, provider_user_id, nickname) values (gen_random_uuid(), 'GOOGLE', 'other', 'Star')")
		val token = devToken(port)
		val taken = patch(token, """{"nickname":"star"}""")
		assertEquals(409, taken.statusCode())
		assertEquals("""{"error":"NICKNAME_TAKEN"}""", taken.body())
		assertEquals(200, patch(token, """{"nickname":"Moon"}""").statusCode())
		assertEquals("MOON", json(patch(token, """{"nickname":"MOON"}""").body())["nickname"].asString())
	}

	@Test
	fun `탈퇴하면 그 닉네임을 다른 사람이 쓸 수 있다`() {
		val first = devToken(port)
		patch(first, """{"nickname":"Moon"}""")
		assertEquals(204, http(port, "DELETE", "/account", token = first).statusCode())
		assertEquals(200, patch(devToken(port), """{"nickname":"Moon"}""").statusCode())
	}

	@Test
	fun `닉네임을 바꾸면 앞뒤 공백을 떼어 두고, 틀리면 400 이고 그대로다`() {
		val token = devToken(port)
		val changed = patch(token, """{"nickname":"  별이  "}""")
		assertEquals(200, changed.statusCode())
		assertEquals("별이", json(changed.body())["nickname"].asString())
		for (bad in listOf("\"\"", "\"   \"", "\"별\"", "\"${"가".repeat(11)}\"", "\"별이1\"", "\"별 이\"", "\"별\\n이\"")) {
			val response = patch(token, """{"nickname":$bad}""")
			assertEquals(400, response.statusCode(), bad)
			assertEquals("""{"error":"INVALID_NICKNAME"}""", response.body())
		}
		assertEquals("별이", json(get(token).body())["nickname"].asString())
	}

	@Test
	fun `동의를 켜면 일시가 생기고, 같은 값이면 그대로, 끄면 사라진다 — 바뀔 때만 감사 기록`() {
		val token = devToken(port)
		val on = json(patch(token, """{"marketingConsent":true}""").body())
		assertEquals(true, on["marketingConsent"].asBoolean())
		val at = assertNotNull(on["marketingConsentAt"].asString())
		assertEquals(at, json(patch(token, """{"marketingConsent":true}""").body())["marketingConsentAt"].asString(), "같은 값이면 일시를 바꾸지 않는다")
		val off = json(patch(token, """{"marketingConsent":false}""").body())
		assertEquals(false, off["marketingConsent"].asBoolean())
		assertTrue(off["marketingConsentAt"].isNull)

		// 감사 기록은 계정을 지워도 남는다(결정 015) — 이 테스트의 계정 것만 본다.
		val owner = jdbc.queryForObject("select id::text from accounts where provider = 'DEV'", String::class.java)
		val details = jdbc.queryForList(
			"select detail::text from audit_logs where action = 'MARKETING_CONSENT_CHANGED' and actor_id = ? order by occurred_at",
			String::class.java, owner,
		)
		assertEquals(listOf("""{"consent": "true"}""", """{"consent": "false"}"""), details)
	}

	@Test
	fun `빈 본문이면 아무것도 바꾸지 않고 지금 값을 준다`() {
		val token = devToken(port)
		patch(token, """{"nickname":"별이","marketingConsent":true}""")
		val before = get(token).body()
		val response = patch(token, "{}")
		assertEquals(200, response.statusCode())
		assertEquals(before, response.body())
	}

	@Test
	fun `탈퇴하면 닉네임·이메일·동의가 계정과 함께 지워진다`() {
		val token = devToken(port)
		patch(token, """{"nickname":"별이","marketingConsent":true}""")
		jdbc.update("update accounts set email = 'dev@example.com' where provider = 'DEV'")
		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())
		assertEquals(0, jdbc.queryForObject("select count(*) from accounts where nickname = '별이' or email = 'dev@example.com' or marketing_consented_at is not null", Int::class.java))
	}

	@Test
	fun `세션이 없으면 401 이다`() {
		assertEquals(401, http(port, "GET", "/account").statusCode())
		assertEquals(401, http(port, "PATCH", "/account", """{"nickname":"별이"}""").statusCode())
	}

	@Test
	fun `감사 기록에 닉네임이 들어가지 않는다`() {
		val token = devToken(port)
		patch(token, """{"nickname":"비밀닉","marketingConsent":true}""")
		val all = jdbc.queryForList("select coalesce(detail::text, '') from audit_logs", String::class.java).joinToString()
		assertFalse(all.contains("비밀닉"))
	}
}
