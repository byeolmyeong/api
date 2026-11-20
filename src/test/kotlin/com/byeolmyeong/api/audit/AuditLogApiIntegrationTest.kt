package com.byeolmyeong.api.audit

import com.byeolmyeong.api.admin.ADMIN_ORIGIN
import com.byeolmyeong.api.admin.APP_ORIGIN
import com.byeolmyeong.api.admin.AdminIntegrationTest
import com.byeolmyeong.api.admin.ROOT_ID
import com.byeolmyeong.api.admin.adminToken
import com.byeolmyeong.api.admin.json
import com.byeolmyeong.api.admin.resetAdminData
import com.byeolmyeong.api.admin.userAccount
import com.byeolmyeong.api.http
import com.byeolmyeong.api.web.ownerRef
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ITEM_KEYS = setOf("id", "occurredAt", "actorType", "actor", "action", "targetType", "targetId", "outcome", "detail")

/** 감사 기록 조회 `GET /admin/audit-logs`(결정 015): 새것부터·쪽 나눔·거르기·오류·관리자만. 행은 표에 직접 넣는다. */
@AdminIntegrationTest
class AuditLogApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var token: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		token = adminToken(port)
		jdbc.update("delete from audit_logs")
	}

	/** 한 줄을 직접 넣는다. [at] 은 UTC 시각 글자다. */
	private fun seed(
		at: String,
		action: String,
		actorType: String = "ADMIN",
		actorId: String? = UUID.randomUUID().toString(),
		label: String? = "some-admin",
		outcome: String = "SUCCESS",
		targetType: String? = null,
		targetId: String? = null,
		detail: String? = null,
	): String {
		val id = UUID.randomUUID()
		jdbc.update(
			"""
			insert into audit_logs (id, occurred_at, actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail)
			values (?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
			""".trimIndent(),
			id, at, actorType, actorId, label, action, targetType, targetId, outcome, detail,
		)
		return id.toString()
	}

	private fun list(query: String = "", token: String? = this.token): HttpResponse<String> = http(port, "GET", "/admin/audit-logs$query", token = token)

	private fun ids(response: HttpResponse<String>): List<String> {
		assertEquals(200, response.statusCode(), response.body())
		return response.json()["items"].toList().map { it["id"].asString() }
	}

	private fun assertError(code: String, query: String) {
		val response = list(query)
		assertEquals(400, response.statusCode(), query)
		assertEquals("""{"error":"$code"}""", response.body(), query)
	}

	@Test
	fun `새것부터 주고 모양이 정해져 있다`() {
		val adminId = UUID.randomUUID().toString()
		val accountId = UUID.randomUUID()
		val inquiry = UUID.randomUUID().toString()
		val old = seed("2026-11-10T01:00:00Z", "ADMIN_LOGIN", actorType = "SYSTEM", actorId = null, label = null, outcome = "FAILURE")
		val newest = seed("2026-11-12T04:05:06.789Z", "INQUIRY_UPDATED", actorId = adminId, label = "root-admin", targetType = "INQUIRY", targetId = inquiry, detail = """{"status":"DONE","memoChanged":true}""")
		val middle = seed("2026-11-11T01:00:00Z", "READING_GENERATED", actorType = "USER", actorId = accountId.toString(), label = ownerRef(accountId))

		val response = list()
		assertEquals(listOf(newest, middle, old), ids(response))
		val json = response.json()
		assertEquals(setOf("items", "page", "size", "total"), json.propertyNames().toSet())
		assertEquals(listOf(0, 20, 3), listOf(json["page"].asInt(), json["size"].asInt(), json["total"].asInt()))

		val first = json["items"][0]
		assertEquals(ITEM_KEYS, first.propertyNames().toSet())
		assertEquals("2026-11-12T04:05:06Z", first["occurredAt"].asString())
		assertEquals(listOf("ADMIN", "INQUIRY_UPDATED", "INQUIRY", inquiry, "SUCCESS"), listOf("actorType", "action", "targetType", "targetId", "outcome").map { first[it].asString() })
		assertEquals(setOf("ref", "label"), first["actor"].propertyNames().toSet())
		assertEquals(listOf("root-admin", "root-admin"), listOf(first["actor"]["ref"].asString(), first["actor"]["label"].asString()))
		assertEquals(json(mapOf("status" to "DONE", "memoChanged" to true)).length, first["detail"].toString().length)
		assertEquals("DONE", first["detail"]["status"].asString())
		assertTrue(first["detail"]["memoChanged"].asBoolean())

		val user = json["items"][1]
		assertEquals(ITEM_KEYS, user.propertyNames().toSet(), "값이 없는 키도 null 로 온다")
		assertEquals(listOf(ownerRef(accountId), ownerRef(accountId)), listOf(user["actor"]["ref"].asString(), user["actor"]["label"].asString()))
		for (key in listOf("targetType", "targetId", "detail")) assertTrue(user[key].isNull, key)

		val system = json["items"][2]
		assertEquals(listOf("SYSTEM", "FAILURE"), listOf(system["actorType"].asString(), system["outcome"].asString()))
		assertTrue(system["actor"].isNull)

		assertFalse(accountId.toString() in response.body(), "계정 id 는 내보내지 않는다")
		assertFalse(adminId in response.body())
	}

	@Test
	fun `같은 때의 행도 순서가 정해져 있고 쪽으로 나뉜다`() {
		val seeded = (1..5).map { seed("2026-11-10T0$it:00:00Z", "FAQ_UPDATED") }.reversed()

		assertEquals(seeded.take(2), ids(list("?size=2")))
		assertEquals(seeded.drop(2).take(2), ids(list("?page=1&size=2")))
		assertEquals(seeded.drop(4), ids(list("?page=2&size=2")))
		val beyond = list("?page=3&size=2")
		assertEquals(emptyList(), ids(beyond))
		assertEquals(listOf(3, 2, 5), listOf("page", "size", "total").map { beyond.json()[it].asInt() })

		jdbc.update("update audit_logs set occurred_at = '2026-11-10T00:00:00Z'")
		val all = ids(list())
		assertEquals(all, ids(list()), "같은 때면 id 순서로 언제나 같다")
		assertEquals(all.sortedDescending(), all)
		assertEquals(5, ids(list("?size=100")).size)
	}

	@Test
	fun `행위자 종류·행동·행위자로 거른다`() {
		val account = UUID.randomUUID()
		val adminLogin = seed("2026-11-10T01:00:00Z", "ADMIN_LOGIN", label = "root-admin")
		val otherLogin = seed("2026-11-10T02:00:00Z", "ADMIN_LOGIN", label = "second-admin")
		val failed = seed("2026-11-10T03:00:00Z", "ADMIN_LOGIN", actorType = "SYSTEM", actorId = null, label = null, outcome = "FAILURE")
		val reading = seed("2026-11-10T04:00:00Z", "READING_GENERATED", actorType = "USER", actorId = account.toString(), label = ownerRef(account))
		val share = seed("2026-11-10T05:00:00Z", "SHARE_CREATED", actorType = "USER", actorId = account.toString(), label = ownerRef(account))
		val faq = seed("2026-11-10T06:00:00Z", "FAQ_CREATED", label = "root-admin")

		assertEquals(listOf(faq, otherLogin, adminLogin), ids(list("?actorType=ADMIN")))
		assertEquals(listOf(share, reading), ids(list("?actorType=USER")))
		assertEquals(listOf(failed), ids(list("?actorType=SYSTEM")))
		assertEquals(listOf(failed, otherLogin, adminLogin), ids(list("?action=ADMIN_LOGIN")))
		assertEquals(listOf(otherLogin, adminLogin), ids(list("?action=ADMIN_LOGIN&actorType=ADMIN")))
		assertEquals(listOf(faq, adminLogin), ids(list("?actorRef=root-admin")))
		assertEquals(listOf(faq, adminLogin), ids(list("?actorRef=%20Root-Admin%20")), "앞뒤 공백과 대소문자를 가리지 않는다")
		assertEquals(listOf(share, reading), ids(list("?actorRef=${ownerRef(account)}")))
		assertEquals(listOf(reading), ids(list("?actorRef=${ownerRef(account)}&action=READING_GENERATED")))
		assertEquals(emptyList(), ids(list("?actorRef=nobody")))
		assertEquals(emptyList(), ids(list("?actorRef=$account")), "계정 id 로는 찾을 수 없다")
		assertEquals(emptyList(), ids(list("?action=APP_CONFIG_UPDATED")), "아직 없는 행동은 빈 목록이다")
		assertEquals(1, list("?actorType=SYSTEM").json()["total"].asInt())

		// 빈 값은 없는 것으로 본다.
		assertEquals(6, ids(list("?actorType=&action=&from=&to=&actorRef=&page=&size=")).size)
	}

	@Test
	fun `q 는 관리자 아이디 일부나 사용자 닉네임 일부로 찾는다`() {
		fun nicknamed(name: String, nickname: String): UUID =
			userAccount(jdbc, name).first.also { jdbc.update("update accounts set nickname = ? where id = ?", nickname, it) }
		val luna = nicknamed("q-luna", "별빛Luna")
		val moon = nicknamed("q-moon", "달님")
		val gone = nicknamed("q-gone", "지울이")
		fun userRow(at: String, account: UUID) = seed(at, "READING_GENERATED", actorType = "USER", actorId = account.toString(), label = ownerRef(account))
		val root = seed("2026-11-10T01:00:00Z", "ADMIN_LOGIN", label = "root-admin")
		val second = seed("2026-11-10T02:00:00Z", "FAQ_CREATED", label = "second-admin")
		val lunaRow = userRow("2026-11-10T03:00:00Z", luna)
		val moonRow = userRow("2026-11-10T04:00:00Z", moon)
		val goneRow = userRow("2026-11-10T05:00:00Z", gone)
		seed("2026-11-10T06:00:00Z", "ADMIN_LOGIN", actorType = "SYSTEM", actorId = null, label = null, outcome = "FAILURE")

		assertEquals(listOf(second, root), ids(list("?q=admin")), "관리자 아이디 일부")
		assertEquals(listOf(second), ids(list("?q=SECOND")), "대소문자를 가리지 않는다")
		assertEquals(listOf(lunaRow), ids(list("?q=luna")), "닉네임 일부 · 대소문자를 가리지 않는다")
		assertEquals(listOf(lunaRow), ids(list("?q=%EB%B3%84%EB%B9%9B")), "한글 닉네임 일부(별빛)")
		assertEquals(listOf(moonRow), ids(list("?q=%EB%8B%98")), "님")
		assertEquals(listOf(second), ids(list("?q=admin&action=FAQ_CREATED")), "다른 조건과 함께 모두 맞는 것만")
		assertEquals(emptyList(), ids(list("?q=${ownerRef(luna)}")), "사용자의 ownerRef 로는 찾지 않는다(그건 actorRef)")
		assertEquals(emptyList(), ids(list("?q=$luna")), "계정 id 로는 찾을 수 없다")
		for (wildcard in listOf("%25", "_", "%5C")) assertEquals(emptyList(), ids(list("?q=$wildcard")), "$wildcard 는 글자 그대로다")
		assertEquals(6, ids(list("?q=")).size, "빈 값은 없는 것으로 본다")
		assertEquals(6, ids(list("?q=%20%20")).size, "공백뿐인 값도 없는 것으로 본다")
		assertEquals(listOf(second), ids(list("?q=%20second%20")), "앞뒤 공백을 뗀다")

		assertEquals(listOf(goneRow), ids(list("?q=%EC%A7%80%EC%9A%B8")), "지울이")
		jdbc.update("delete from accounts where id = ?", gone)
		assertEquals(emptyList(), ids(list("?q=%EC%A7%80%EC%9A%B8")), "지워진 계정은 닉네임으로 찾을 수 없다")
		assertEquals(listOf(goneRow), ids(list("?actorRef=${ownerRef(gone)}")), "행은 남아 ownerRef 로는 찾는다")
	}

	@Test
	fun `관리자 v2 행동 코드로도 거른다`() {
		val suspended = seed("2026-11-10T01:00:00Z", "USER_SUSPENDED", targetType = "USER", targetId = UUID.randomUUID().toString())
		for (action in listOf("USER_UNSUSPENDED", "USER_DELETED_BY_ADMIN", "GENERATION_RETRIED", "PUSH_EVENT_SENT", "ADMIN_PASSWORD_REISSUED")) {
			assertEquals(emptyList(), ids(list("?action=$action")), action)
		}
		assertEquals(listOf(suspended), ids(list("?action=USER_SUSPENDED")))
	}

	@Test
	fun `날짜는 한국 시간의 하루이고 두 끝을 포함한다`() {
		// 한국 시간 11월 10일은 UTC 11월 9일 15:00 부터 11월 10일 15:00 전까지다.
		val before = seed("2026-11-09T14:59:59Z", "FAQ_UPDATED")
		val firstOf10 = seed("2026-11-09T15:00:00Z", "FAQ_UPDATED")
		val lastOf10 = seed("2026-11-10T14:59:59.999Z", "FAQ_UPDATED")
		val firstOf11 = seed("2026-11-10T15:00:00Z", "FAQ_UPDATED")
		val of12 = seed("2026-11-12T00:00:00Z", "FAQ_UPDATED")

		assertEquals(listOf(lastOf10, firstOf10), ids(list("?from=2026-11-10&to=2026-11-10")))
		assertEquals(listOf(of12, firstOf11, lastOf10, firstOf10), ids(list("?from=2026-11-10")))
		assertEquals(listOf(lastOf10, firstOf10, before), ids(list("?to=2026-11-10")))
		assertEquals(listOf(firstOf11, lastOf10, firstOf10), ids(list("?from=2026-11-10&to=2026-11-11")))
		assertEquals(emptyList(), ids(list("?from=2026-11-11&to=2026-11-10")), "거꾸로 된 기간은 빈 목록이다")
		assertEquals(2, list("?from=2026-11-10&to=2026-11-10&size=1").json()["total"].asInt())
	}

	@Test
	fun `틀린 조건은 400 이다`() {
		seed("2026-11-10T01:00:00Z", "ADMIN_LOGIN")
		for (query in listOf("?actorType=admin", "?actorType=OWNER", "?actorType=ADMIN,USER")) assertError("INVALID_ACTOR_TYPE", query)
		for (query in listOf("?action=admin_login", "?action=AUDIT_LOG_VIEWED", "?action=LOGIN", "?action=%27%20or%201=1")) assertError("INVALID_ACTION", query)
		for (query in listOf("?from=2026-11-1", "?from=20261110", "?to=2026-02-30", "?from=2026-11-10T00:00:00Z", "?to=yesterday", "?from=2026-11-10&to=11-12")) assertError("INVALID_DATE", query)
		for (query in listOf("?page=-1", "?page=abc", "?page=1.5", "?size=0", "?size=101", "?size=many", "?page=99999999999")) assertError("INVALID_PAGE", query)
		// 여러 개가 틀리면 이 순서로 처음 걸린 것 하나다.
		assertError("INVALID_ACTOR_TYPE", "?actorType=x&action=y&from=z&page=-1")
		assertError("INVALID_ACTION", "?action=y&from=z&page=-1")
		assertError("INVALID_DATE", "?from=z&page=-1")
	}

	@Test
	fun `관리자만 읽고 읽는 것은 기록되지 않으며 고치거나 지우는 길이 없다`() {
		val (_, userToken) = userAccount(jdbc, "audit-reader")
		val row = seed("2026-11-10T01:00:00Z", "ADMIN_LOGIN")

		for (bad in listOf(null, userToken, "no-such-token")) {
			val response = list(token = bad)
			assertEquals(401, response.statusCode())
			assertEquals("""{"error":"UNAUTHORIZED"}""", response.body())
		}
		assertEquals(listOf(row), ids(list()))
		assertEquals(listOf(row), ids(list()), "조회는 행을 만들지 않는다")

		for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
			assertTrue(http(port, method, "/admin/audit-logs", "{}", token).statusCode() in listOf(404, 405), method)
			assertTrue(http(port, method, "/admin/audit-logs/$row", "{}", token).statusCode() in listOf(404, 405), method)
		}
		assertEquals(1, jdbc.queryForObject("select count(*) from audit_logs", Int::class.java))
	}

	@Test
	fun `실제 행동이 목록에 보이고 사용자는 문의의 ownerRef 와 같은 말로 가리킨다`() {
		val (accountId, userToken) = userAccount(jdbc, "audit-actor")
		assertEquals(201, http(port, "POST", "/inquiries", json(mapOf("category" to "BUG", "body" to "문의 글을 열 자 넘게 씁니다")), userToken).statusCode())
		adminToken(port)
		val inquiry = http(port, "GET", "/admin/inquiries", token = token).json()["items"][0]

		val items = list().json()["items"].toList()
		assertEquals(listOf("ADMIN_LOGIN", "INQUIRY_SUBMITTED"), items.map { it["action"].asString() })
		assertEquals(ROOT_ID, items[0]["actor"]["ref"].asString())
		assertEquals(inquiry["ownerRef"].asString(), items[1]["actor"]["ref"].asString())
		assertEquals(inquiry["id"].asString(), items[1]["targetId"].asString())
		assertEquals("BUG", items[1]["detail"]["category"].asString())
		assertEquals(listOf(items[1]["id"].asString()), ids(list("?actorRef=${inquiry["ownerRef"].asString()}")))
		assertFalse(accountId.toString() in list().body())
	}

	@Test
	fun `관리 화면의 출처만 부를 수 있다`() {
		val allowed = http(port, "GET", "/admin/audit-logs", null, token, "Origin", ADMIN_ORIGIN)
		assertEquals(200, allowed.statusCode())
		assertEquals(ADMIN_ORIGIN, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
		assertEquals(403, http(port, "GET", "/admin/audit-logs", null, token, "Origin", APP_ORIGIN).statusCode())
	}

	private fun HttpResponse<String>.json() = com.byeolmyeong.api.admin.mapper.readTree(body())
}
