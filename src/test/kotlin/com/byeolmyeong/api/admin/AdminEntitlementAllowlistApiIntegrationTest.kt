package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PATH = "/admin/entitlement-allowlist"

/** 결제 없이 여는 계정(결정 056 의 9 · 앱 결정 101 의 5-2): 넣기 · 보기 · 빼기와 감사 기록. 받기에서 쓰이는 것은 `EntitlementCheckIntegrationTest`. */
@AdminIntegrationTest
class AdminEntitlementAllowlistApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var token: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		jdbc.update("delete from audit_logs")
		token = adminToken(port)
	}

	private fun add(body: String?, token: String = this.token): HttpResponse<String> = http(port, "POST", PATH, body, token)

	private fun list(): HttpResponse<String> = http(port, "GET", PATH, token = token)

	private fun audits(action: String): List<Map<String, Any?>> =
		jdbc.queryForList("select actor_type, actor_label, target_type, target_id, detail::text as detail from audit_logs where action = ?", action)

	@Test
	fun `넣으면 앞뒤 공백을 떼고 소문자로 두며 새것부터 보이고 감사 기록에는 이메일이 없다`() {
		assertEquals(200 to "[]", list().let { it.statusCode() to it.body() })

		val first = add(json(mapOf("email" to "  Team.Member@Example.COM ")))
		val second = add(json(mapOf("email" to "abc123@privaterelay.appleid.com")))

		assertEquals(201, first.statusCode(), first.body())
		val entry = first.json()
		assertEquals(listOf("id", "email", "createdAt", "createdBy"), entry.propertyNames().toList())
		assertEquals("team.member@example.com", entry["email"].asString())
		assertTrue(Instant.parse(entry["createdAt"].asString()) <= Instant.now())
		assertEquals(ROOT_ID, entry["createdBy"]["name"].asString())
		assertEquals(201, second.statusCode())

		val listed = list().json().toList()
		assertEquals(listOf("abc123@privaterelay.appleid.com", "team.member@example.com"), listed.map { it["email"].asString() })
		assertEquals(listOf("team.member@example.com", "abc123@privaterelay.appleid.com"), jdbc.queryForList("select email from entitlement_allowlist order by created_at", String::class.java))

		val added = audits("ENTITLEMENT_ALLOWLIST_ADDED")
		assertEquals(2, added.size)
		assertEquals("ADMIN" to ROOT_ID, added[0]["actor_type"] to added[0]["actor_label"])
		assertEquals("ENTITLEMENT_ALLOWLIST", added[0]["target_type"])
		assertEquals(setOf(entry["id"].asString(), second.json()["id"].asString()), added.map { it["target_id"] }.toSet())
		assertFalse(jdbc.queryForList("select coalesce(detail::text, '') || coalesce(target_id, '') from audit_logs", String::class.java).any { it!!.contains("@") })
	}

	@Test
	fun `같은 이메일은 대소문자가 달라도 409 ALREADY_LISTED 다`() {
		assertEquals(201, add(json(mapOf("email" to "owner@example.com"))).statusCode())

		val again = add(json(mapOf("email" to "OWNER@example.com")))

		assertEquals(409 to """{"error":"ALREADY_LISTED"}""", again.statusCode() to again.body())
		assertEquals(1, audits("ENTITLEMENT_ALLOWLIST_ADDED").size)
	}

	@Test
	fun `이메일 꼴이 아니면 400 INVALID_EMAIL 이고 타입이 틀리면 INVALID_REQUEST 다`() {
		val invalid = listOf(null, "", "   ", "no-at-sign", "two@at@example.com", "a b@example.com", "@example.com", "someone@", "a@" + "b".repeat(253))
		for (email in invalid) {
			val response = add(json(mapOf("email" to email)))
			assertEquals(400 to """{"error":"INVALID_EMAIL"}""", response.statusCode() to response.body(), "$email")
		}
		assertEquals(400 to """{"error":"INVALID_EMAIL"}""", add("{}").let { it.statusCode() to it.body() })
		assertEquals(400 to """{"error":"INVALID_REQUEST"}""", add(json(mapOf("email" to 12))).let { it.statusCode() to it.body() })
		assertEquals(400 to """{"error":"INVALID_REQUEST"}""", add("[]").let { it.statusCode() to it.body() })
		assertEquals(0, jdbc.queryForObject("select count(*) from entitlement_allowlist", Int::class.java))
	}

	@Test
	fun `빼면 204 이고 감사 기록이 남으며 없는 것은 404 다`() {
		val id = add(json(mapOf("email" to "tester@example.com"))).json()["id"].asString()

		assertEquals(204, http(port, "DELETE", "$PATH/$id", token = token).statusCode())
		assertEquals("[]", list().body())
		assertEquals(listOf<Any?>(id), audits("ENTITLEMENT_ALLOWLIST_REMOVED").map { it["target_id"] })

		assertEquals(404 to """{"error":"NOT_FOUND"}""", http(port, "DELETE", "$PATH/$id", token = token).let { it.statusCode() to it.body() })
		assertEquals(404, http(port, "DELETE", "$PATH/${UUID.randomUUID()}", token = token).statusCode())
		assertEquals(404, http(port, "DELETE", "$PATH/not-a-uuid", token = token).statusCode())
		assertEquals(1, audits("ENTITLEMENT_ALLOWLIST_REMOVED").size)
	}

	@Test
	fun `관리자 세션이 없거나 앱 사용자 토큰이면 401 이다`() {
		val (_, userToken) = userAccount(jdbc, "allowlist-user")

		assertEquals(401, http(port, "GET", PATH).statusCode())
		assertEquals(401, http(port, "GET", PATH, token = userToken).statusCode())
		assertEquals(401, add(json(mapOf("email" to "x@example.com")), token = userToken).statusCode())
	}
}
