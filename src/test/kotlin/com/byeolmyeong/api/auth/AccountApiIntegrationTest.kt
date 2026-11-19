package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.freshNickname
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

/** 결정 025 · 026: 계정의 닉네임·이메일·공급자·수신 동의(마케팅 · 푸시)·프로필을 보고(GET) 닉네임·동의·프로필을 바꾼다(PATCH). 소유자는 세션에서만. */
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

	private fun ownerId(): String = jdbc.queryForObject("select id::text from accounts where provider = 'DEV'", String::class.java)!!

	@Test
	fun `계정 정보를 준다 — 옛 계정(닉네임·이메일·프로필 없음)은 null 이다`() {
		val response = get(devToken(port))
		assertEquals(200, response.statusCode())
		val node = json(response.body())
		assertEquals(
			listOf("nickname", "email", "provider", "marketingConsent", "marketingConsentAt", "pushConsent", "pushConsentAt", "avatar"),
			node.propertyNames().toList(),
		)
		assertTrue(node["nickname"].isNull)
		assertTrue(node["email"].isNull)
		assertEquals("DEV", node["provider"].asString())
		assertEquals(false, node["marketingConsent"].asBoolean())
		assertTrue(node["marketingConsentAt"].isNull)
		assertEquals(false, node["pushConsent"].asBoolean())
		assertTrue(node["pushConsentAt"].isNull)
		assertTrue(node["avatar"].isNull)
	}

	@Test
	fun `푸시 동의를 켜면 일시가 생기고, 같은 값이면 그대로, 끄면 사라진다 — 바뀔 때만 감사 기록(결정 026)`() {
		val token = devToken(port)
		val on = json(patch(token, """{"pushConsent":true}""").body())
		assertEquals(true, on["pushConsent"].asBoolean())
		val at = assertNotNull(on["pushConsentAt"].asString())
		val same = json(patch(token, """{"pushConsent":true}""").body())
		assertEquals(at, same["pushConsentAt"].asString(), "같은 값이면 일시를 바꾸지 않는다")
		assertEquals(at, json(get(token).body())["pushConsentAt"].asString())
		val off = json(patch(token, """{"pushConsent":false}""").body())
		assertEquals(false, off["pushConsent"].asBoolean())
		assertTrue(off["pushConsentAt"].isNull)
		assertEquals(false, json(patch(token, """{"pushConsent":false}""").body())["pushConsent"].asBoolean())
		assertEquals(false, off["marketingConsent"].asBoolean(), "마케팅 동의는 따로다")

		// 감사 기록은 계정을 지워도 남는다(결정 015) — 이 테스트의 계정 것만 본다.
		val details = jdbc.queryForList(
			"select detail::text from audit_logs where action = 'PUSH_CONSENT_CHANGED' and actor_id = ? order by occurred_at",
			String::class.java, ownerId(),
		)
		assertEquals(listOf("""{"consent": "true"}""", """{"consent": "false"}"""), details)
	}

	@Test
	fun `프로필을 1~8 로 바꾸고, 범위 밖이면 400 INVALID_AVATAR 이고 같이 보낸 칸도 바꾸지 않는다(결정 026)`() {
		val token = devToken(port)
		assertEquals(5, json(patch(token, """{"avatar":5}""").body())["avatar"].asInt())
		val nickname = freshNickname()
		for (bad in listOf(0, 9, -1)) {
			val response = patch(token, """{"avatar":$bad,"pushConsent":true,"nickname":"$nickname"}""")
			assertEquals(400, response.statusCode(), "avatar=$bad")
			assertEquals("""{"error":"INVALID_AVATAR"}""", response.body())
		}
		val now = json(get(token).body())
		assertEquals(5, now["avatar"].asInt())
		assertEquals(false, now["pushConsent"].asBoolean())
		assertTrue(now["nickname"].isNull)
		assertEquals(5, json(patch(token, """{"avatar":null}""").body())["avatar"].asInt(), "null 은 보내지 않은 것이다")
		assertEquals(1, json(patch(token, """{"avatar":1}""").body())["avatar"].asInt())
		assertEquals(8, json(patch(token, """{"avatar":8}""").body())["avatar"].asInt())
	}

	@Test
	fun `다른 사람이 쓰는 닉네임으로는 바꿀 수 없다 — 영문 대소문자도 같은 이름이고, 내 닉네임의 대소문자만 바꾸는 건 된다`() {
		jdbc.update("insert into accounts (id, provider, provider_user_id, nickname) values (gen_random_uuid(), 'GOOGLE', 'other', 'Star')")
		val token = devToken(port)
		val taken = patch(token, """{"nickname":"star","marketingConsent":true}""")
		assertEquals(409, taken.statusCode())
		assertEquals("""{"error":"NICKNAME_TAKEN"}""", taken.body())
		assertEquals(false, json(get(token).body())["marketingConsent"].asBoolean(), "409 면 같이 보낸 동의도 바꾸지 않는다")
		assertEquals(200, patch(token, """{"nickname":"Moon"}""").statusCode())
		assertEquals("MOON", json(patch(token, """{"nickname":"MOON"}""").body())["nickname"].asString())
	}

	@Test
	fun `닉네임이 409 면 같이 보낸 푸시 동의 · 프로필도 바꾸지 않고 감사 기록도 없다(결정 026)`() {
		val taken = freshNickname()
		jdbc.update("insert into accounts (id, provider, provider_user_id, nickname) values (gen_random_uuid(), 'GOOGLE', 'other', ?)", taken)
		val token = devToken(port)
		assertEquals(200, patch(token, """{"avatar":2}""").statusCode())

		val response = patch(token, """{"nickname":"$taken","pushConsent":true,"avatar":7}""")
		assertEquals(409, response.statusCode())
		assertEquals("""{"error":"NICKNAME_TAKEN"}""", response.body())
		val now = json(get(token).body())
		assertEquals(2, now["avatar"].asInt())
		assertEquals(false, now["pushConsent"].asBoolean())
		assertTrue(now["pushConsentAt"].isNull)
		val pushAudits = jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'PUSH_CONSENT_CHANGED' and actor_id = ?", Int::class.java, ownerId(),
		)
		assertEquals(0, pushAudits)
	}

	@Test
	fun `값은 정확한 JSON 타입만 받는다 — 틀린 타입은 400 INVALID_REQUEST 이고 아무것도 바꾸지 않는다`() {
		val token = devToken(port)
		for (body in listOf(
			"""{"avatar":4.9}""", """{"avatar":"3"}""",
			"""{"pushConsent":1}""", """{"pushConsent":"true"}""",
			"""{"marketingConsent":1}""", """{"marketingConsent":"true"}""",
		)) {
			val response = patch(token, body)
			assertEquals(400, response.statusCode(), body)
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body(), body)
		}
		val now = json(get(token).body())
		assertTrue(now["avatar"].isNull)
		assertEquals(false, now["pushConsent"].asBoolean())
		assertEquals(false, now["marketingConsent"].asBoolean())

		val ok = json(patch(token, """{"avatar":3,"pushConsent":true,"marketingConsent":false}""").body())
		assertEquals(3, ok["avatar"].asInt())
		assertEquals(true, ok["pushConsent"].asBoolean())
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
		assertEquals(401, http(port, "PATCH", "/account", """{"pushConsent":true,"avatar":1}""").statusCode())
	}

	@Test
	fun `감사 기록에 닉네임이 들어가지 않는다`() {
		val token = devToken(port)
		patch(token, """{"nickname":"비밀닉","marketingConsent":true}""")
		val all = jdbc.queryForList("select coalesce(detail::text, '') from audit_logs", String::class.java).joinToString()
		assertFalse(all.contains("비밀닉"))
	}
}
