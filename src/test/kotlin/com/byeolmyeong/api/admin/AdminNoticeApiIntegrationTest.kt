package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import com.byeolmyeong.api.push.PushMessage
import com.byeolmyeong.api.push.RecordingExpoPush
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOTICE_KEYS = setOf("id", "category", "title", "body", "status", "publishedAt", "notify", "notifyStartedAt", "push", "inbox", "createdAt", "createdBy", "updatedAt", "updatedBy")

/**
 * 공지사항의 관리자 경로(결정 055 의 3 · 4): 쓰기 · 고치기 · 지우기 · 공개 · 내리기, "알림 보내기"를 켠 글은 공개할 때 한 번만
 * 알림함(모든 계정) · 푸시(푸시 동의 + 정지 아님)로 간다. Expo 는 가짜로 바꿔 끼운다.
 */
@AdminIntegrationTest
@Import(AdminNoticeApiIntegrationTest.FakeExpo::class)
class AdminNoticeApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeExpo {
		@Bean
		@Primary
		fun noticeExpoPush() = RecordingExpoPush()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var expo: RecordingExpoPush

	private lateinit var token: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		expo.sent.clear()
		token = adminToken(port)
	}

	private fun create(category: Any?, title: Any?, body: Any?, notify: Any? = null): HttpResponse<String> {
		val fields = mutableMapOf("category" to category, "title" to title, "body" to body)
		if (notify != null) fields["notify"] = notify
		return http(port, "POST", "/admin/notices", json(fields), token)
	}

	private fun created(title: String, category: String = "NOTICE", notify: Boolean = false): String =
		create(category, title, "$title 의 본문", notify).also { assertEquals(201, it.statusCode(), it.body()) }.json()["id"].asString()

	private fun put(id: String, fields: Map<String, Any?>): HttpResponse<String> = http(port, "PUT", "/admin/notices/$id", json(fields), token)

	private fun publish(id: String, notify: Any?): HttpResponse<String> = http(port, "POST", "/admin/notices/$id/publish", json(mapOf("notify" to notify)), token)

	private fun published(id: String, notify: Boolean): JsonNode = publish(id, notify).also { assertEquals(200, it.statusCode(), it.body()) }.json()

	private fun unpublish(id: String): HttpResponse<String> = http(port, "POST", "/admin/notices/$id/unpublish", null, token)

	private fun adminList(): List<JsonNode> = http(port, "GET", "/admin/notices", token = token).also { assertEquals(200, it.statusCode()) }.json().toList()

	private fun publicTitles(): List<String> = http(port, "GET", "/notices").json().toList().map { it["title"].asString() }

	/** 앱 사용자 계정 하나와 그 기기. 푸시 동의 · 정지는 고른다. */
	private fun user(name: String, consented: Boolean = true, suspended: Boolean = false, vararg devices: String): Pair<UUID, String> {
		val (id, session) = userAccount(jdbc, name)
		if (consented) jdbc.update("update accounts set push_consented_at = now() where id = ?", id)
		if (suspended) jdbc.update("update accounts set suspended_at = now() where id = ?", id)
		for (device in devices) jdbc.update("insert into push_devices (expo_token, account_id, platform, app_version) values (?, ?, 'IOS', '1.1.0')", device, id)
		return id to session
	}

	/** 푸시는 일꾼 스레드에서 간다. [count] 건이 모일 때까지 기다리고(5초까지) 조금 더 본다. */
	private fun awaitSent(count: Int): List<PushMessage> {
		val until = System.currentTimeMillis() + 5_000
		while (expo.sent.size < count && System.currentTimeMillis() < until) Thread.sleep(20)
		Thread.sleep(200)
		return expo.sent.toList()
	}

	private fun noticeRows(id: String): Int =
		jdbc.queryForObject("select count(*) from notifications where kind = 'NOTICE' and notice_id = ?", Int::class.java, UUID.fromString(id))!!

	@Test
	fun `공지를 만들면 임시 저장이고 앱에는 보이지 않는다`() {
		val before = Instant.now().minusSeconds(2)
		val response = create("NOTICE", "  점검 안내  ", "  12월 3일 새벽에 점검합니다.\n\n잠시 쓸 수 없어요.  ")
		assertEquals(201, response.statusCode())
		val notice = response.json()
		assertEquals(NOTICE_KEYS, notice.propertyNames().toSet())
		assertEquals("점검 안내", notice["title"].asString(), "앞뒤 공백을 뗀다")
		assertEquals("12월 3일 새벽에 점검합니다.\n\n잠시 쓸 수 없어요.", notice["body"].asString())
		assertEquals("NOTICE", notice["category"].asString())
		assertEquals("DRAFT", notice["status"].asString())
		assertTrue(notice["publishedAt"].isNull)
		assertEquals(false, notice["notify"].asBoolean(), "알림 보내기는 처음엔 꺼짐")
		assertTrue(notice["notifyStartedAt"].isNull)
		assertTrue(notice["push"].isNull, "알림을 보내지 않았으면 결과도 없다")
		assertTrue(notice["inbox"].isNull)
		assertTrue(Instant.parse(notice["createdAt"].asString()).isAfter(before))
		assertEquals(ROOT_ID, notice["createdBy"]["name"].asString())
		assertEquals(ROOT_ID, notice["updatedBy"]["name"].asString())

		val id = notice["id"].asString()
		val one = http(port, "GET", "/admin/notices/$id", token = token)
		assertEquals(200, one.statusCode())
		assertEquals(notice, one.json())
		assertEquals(listOf(id), adminList().map { it["id"].asString() })
		assertEquals(emptyList(), publicTitles(), "임시 저장은 앱에 보이지 않는다")
	}

	@Test
	fun `제목은 60자 본문은 3000자까지 · 갈래는 둘 · 타입이 틀리면 400 이다`() {
		fun assertRejected(code: String, category: Any?, title: Any?, body: Any?, notify: Any? = null) {
			val response = create(category, title, body, notify)
			assertEquals(400, response.statusCode(), "$code ← $category / ${title.toString().take(10)}")
			assertEquals("""{"error":"$code"}""", response.body())
		}
		assertRejected("INVALID_CATEGORY", "EVENT", "제목", "본문")
		assertRejected("INVALID_CATEGORY", null, "제목", "본문")
		assertRejected("INVALID_CATEGORY", "notice", "제목", "본문")
		assertRejected("INVALID_TITLE", "NOTICE", "   ", "본문")
		assertRejected("INVALID_TITLE", "NOTICE", null, "본문")
		assertRejected("INVALID_TITLE", "NOTICE", "가".repeat(61), "본문")
		assertRejected("INVALID_TITLE", "NOTICE", "제\u0000목", "본문")
		assertRejected("INVALID_BODY", "NOTICE", "제목", "")
		assertRejected("INVALID_BODY", "NOTICE", "제목", "나".repeat(3001))
		assertRejected("INVALID_REQUEST", "NOTICE", 3, "본문")
		assertRejected("INVALID_REQUEST", "NOTICE", "제목", "본문", "true")
		assertEquals(400, http(port, "POST", "/admin/notices", "[]", token).statusCode())

		assertEquals(201, create("UPDATE", "🌟".repeat(60), "😀".repeat(3000)).statusCode(), "코드 포인트로 센다")
		assertEquals(1, adminList().size)
	}

	@Test
	fun `업데이트 갈래는 알림 보내기를 켤 수 없다`() {
		val response = create("UPDATE", "새 기능", "궁합이 생겼어요", notify = true)
		assertEquals(400, response.statusCode())
		assertEquals("""{"error":"NOTIFY_NOT_ALLOWED"}""", response.body())

		val update = created("새 기능", category = "UPDATE")
		assertEquals("""{"error":"NOTIFY_NOT_ALLOWED"}""", put(update, mapOf("category" to "UPDATE", "title" to "새 기능", "body" to "본문", "notify" to true)).body())
		assertEquals("""{"error":"NOTIFY_NOT_ALLOWED"}""", publish(update, true).body())

		val notice = created("약관 안내", notify = true)
		assertEquals("""{"error":"NOTIFY_NOT_ALLOWED"}""", put(notice, mapOf("category" to "UPDATE", "title" to "약관 안내", "body" to "본문")).body(), "켜 둔 스위치 그대로 업데이트로 바꿀 수 없다")
		assertEquals(200, put(notice, mapOf("category" to "UPDATE", "title" to "약관 안내", "body" to "본문", "notify" to false)).statusCode())

		assertEquals(200, publish(update, false).statusCode())
		assertEquals(0, awaitSent(1).size)
		assertEquals(0, jdbc.queryForObject("select count(*) from notifications", Int::class.java))
	}

	@Test
	fun `고치면 보낸 키를 바꾸고 notify 를 빼면 그대로 둔다`() {
		val id = created("처음 제목", notify = true)
		val response = put(id, mapOf("category" to "NOTICE", "title" to "고친 제목", "body" to "고친 본문"))
		assertEquals(200, response.statusCode())
		val notice = response.json()
		assertEquals("고친 제목", notice["title"].asString())
		assertEquals("고친 본문", notice["body"].asString())
		assertEquals(true, notice["notify"].asBoolean(), "notify 를 빼면 그대로")
		assertEquals(false, put(id, mapOf("category" to "NOTICE", "title" to "고친 제목", "body" to "고친 본문", "notify" to false)).json()["notify"].asBoolean())

		assertEquals("""{"error":"INVALID_TITLE"}""", put(id, mapOf("category" to "NOTICE", "body" to "본문")).body(), "PUT 은 세 키가 다 있어야 한다")
		assertEquals(404, put(UUID.randomUUID().toString(), mapOf("category" to "NOTICE", "title" to "제목", "body" to "본문")).statusCode())
		assertEquals(404, put("not-a-uuid", mapOf("category" to "NOTICE", "title" to "제목", "body" to "본문")).statusCode())
	}

	@Test
	fun `공개하면 앱 목록에 공개 순서로 보이고 내리면 빠진다`() {
		val first = created("첫째")
		val second = created("둘째")
		created("임시 저장으로 남는 글")

		val notice = published(first, notify = false)
		assertEquals("PUBLISHED", notice["status"].asString())
		val publishedAt = Instant.parse(notice["publishedAt"].asString())
		published(second, notify = false)
		assertEquals(listOf("둘째", "첫째"), publicTitles(), "나중에 공개한 것이 위")

		assertEquals(publishedAt.toString(), published(first, notify = false)["publishedAt"].asString(), "이미 공개 중이면 공개 시각은 그대로")

		val down = unpublish(second)
		assertEquals(200, down.statusCode())
		assertEquals("DRAFT", down.json()["status"].asString())
		assertTrue(down.json()["publishedAt"].isNull)
		assertEquals(listOf("첫째"), publicTitles())

		assertEquals(listOf("둘째", "첫째"), published(second, notify = false).let { publicTitles() }, "다시 공개하면 그때가 공개 시각")
		assertEquals(3, adminList().size)
		assertEquals(404, publish(UUID.randomUUID().toString(), false).statusCode())
		assertEquals(404, unpublish(UUID.randomUUID().toString()).statusCode())
		assertEquals("""{"error":"INVALID_REQUEST"}""", publish(first, null).body(), "notify 는 꼭 있어야 한다")
		assertEquals("""{"error":"INVALID_REQUEST"}""", publish(first, "true").body())
	}

	@Test
	fun `알림을 켜고 공개하면 한 번만 모든 계정의 알림함과 푸시 동의한 기기에 간다`() {
		val (reader, session) = user("reader", consented = true, devices = arrayOf("ExponentPushToken[reader-phone]", "ExponentPushToken[reader-tablet]"))
		val (suspended, _) = user("suspended", consented = true, suspended = true, devices = arrayOf("ExponentPushToken[suspended-phone]"))
		val (silent, _) = user("silent", consented = false, devices = arrayOf("ExponentPushToken[silent-phone]"))
		val (web, _) = user("web", consented = true)
		val id = created("이용약관이 바뀌어요")

		val notice = published(id, notify = true)

		assertEquals(true, notice["notify"].asBoolean())
		assertNotNull(notice["notifyStartedAt"].stringValue(null))
		val sent = awaitSent(2)
		assertEquals(setOf("ExponentPushToken[reader-phone]", "ExponentPushToken[reader-tablet]"), sent.map { it.to }.toSet(), "푸시 동의 + 정지 아님 + 기기")
		assertEquals(setOf("이용약관이 바뀌어요"), sent.map { it.title }.toSet())
		assertEquals(setOf("공지사항에서 자세히 볼 수 있어요."), sent.map { it.body }.toSet())
		assertEquals(setOf(mapOf("type" to "NOTICE", "noticeId" to id)), sent.map { it.data }.toSet())

		val owners = jdbc.queryForList("select owner_id from notifications where kind = 'NOTICE' and notice_id = ?", UUID::class.java, UUID.fromString(id)).toSet()
		assertEquals(setOf(reader, suspended, silent, web), owners, "알림함 줄은 모든 계정에(푸시 동의와 상관없이)")

		val inbox = http(port, "GET", "/notifications", token = session).json()
		val item = inbox["items"].single()
		assertEquals("NOTICE", item["kind"].asString())
		assertEquals(id, item["noticeId"].asString())
		assertEquals(setOf("id", "kind", "createdAt", "read", "noticeId"), item.propertyNames().toSet())
		assertEquals(1, inbox["unread"].asInt())

		assertEquals(200, publish(id, true).statusCode())
		assertEquals(200, unpublish(id).statusCode())
		val again = published(id, notify = true)
		assertEquals(notice["notifyStartedAt"], again["notifyStartedAt"], "보내기를 시작한 때는 처음 그대로")
		assertEquals(2, awaitSent(3).size, "다시 공개해도 더 가지 않는다")
		assertEquals(4, noticeRows(id))
	}

	@Test
	fun `두 관리자가 동시에 공개해도 알림은 한 번이다`() {
		user("reader", devices = arrayOf("ExponentPushToken[reader-phone]"))
		val id = created("점검 안내")
		val pool = Executors.newFixedThreadPool(5)
		try {
			val codes = pool.invokeAll(List(5) { Callable { publish(id, true).statusCode() } }).map { it.get() }
			assertEquals(List(5) { 200 }, codes)
		} finally {
			pool.shutdown()
		}
		assertEquals(1, awaitSent(2).size)
		assertEquals(1, noticeRows(id))
		assertEquals(5, jdbc.queryForObject("select count(*) from audit_logs where action = 'NOTICE_PUBLISHED' and target_id = ?", Int::class.java, id))
		assertEquals(1, jdbc.queryForObject("select count(*) from audit_logs where action = 'NOTICE_PUBLISHED' and target_id = ? and detail ->> 'notifyStarted' = 'true'", Int::class.java, id))
	}

	@Test
	fun `알림 없이 공개한 글은 나중에 알림을 켜고 공개하면 그때 한 번 보낸다`() {
		user("reader", devices = arrayOf("ExponentPushToken[reader-phone]"))
		val id = created("운영 안내")
		val first = published(id, notify = false)
		assertEquals(0, awaitSent(1).size)
		assertEquals(0, noticeRows(id))

		val second = published(id, notify = true)
		assertEquals(first["publishedAt"], second["publishedAt"])
		assertEquals(1, awaitSent(1).size)
		assertEquals(1, noticeRows(id))
	}

	@Test
	fun `알림을 보낸 글의 스위치는 끌 수 없다`() {
		val id = created("약관 안내")
		published(id, notify = true)
		assertEquals(true, put(id, mapOf("category" to "NOTICE", "title" to "약관 안내", "body" to "본문", "notify" to false)).json()["notify"].asBoolean())
		assertEquals(true, published(id, notify = false)["notify"].asBoolean())
	}

	@Test
	fun `지우면 앱 목록과 알림함 줄에서 함께 사라진다`() {
		val (_, session) = user("reader")
		val id = created("지울 공지")
		published(id, notify = true)
		assertEquals(1, noticeRows(id))

		assertEquals(204, http(port, "DELETE", "/admin/notices/$id", token = token).statusCode())
		assertEquals(0, noticeRows(id), "공지를 지우면 알림함 줄도(cascade)")
		assertEquals(0, http(port, "GET", "/notifications", token = session).json()["items"].size())
		assertEquals(emptyList(), publicTitles())
		assertEquals(404, http(port, "DELETE", "/admin/notices/$id", token = token).statusCode())
		assertEquals(404, http(port, "GET", "/admin/notices/$id", token = token).statusCode())
	}

	@Test
	fun `내린 글의 알림함 줄은 남는다`() {
		user("reader")
		val id = created("내릴 공지")
		published(id, notify = true)
		unpublish(id)
		assertEquals(1, noticeRows(id))
	}

	@Test
	fun `관리자의 행동은 감사 기록에 남고 글은 남지 않는다`() {
		val id = created("감사 기록 공지")
		put(id, mapOf("category" to "NOTICE", "title" to "고친 감사 기록 공지", "body" to "고친 본문", "notify" to true))
		published(id, notify = true)
		unpublish(id)
		http(port, "DELETE", "/admin/notices/$id", token = token)

		val rows = jdbc.queryForList(
			"select action, actor_type, target_type, target_id, detail::text as detail from audit_logs where target_type = 'NOTICE' and target_id = ? order by occurred_at, id",
			id,
		)
		assertEquals(listOf("NOTICE_CREATED", "NOTICE_UPDATED", "NOTICE_PUBLISHED", "NOTICE_UNPUBLISHED", "NOTICE_DELETED"), rows.map { it["action"] })
		for (row in rows) {
			assertEquals("ADMIN", row["actor_type"])
			assertEquals(id, row["target_id"])
			assertTrue((row["detail"] as String?)?.contains("공지") != true, "글을 넣지 않는다")
		}
		assertEquals(mapOf("category" to "NOTICE", "notify" to false), mapper.readValue(rows[0]["detail"] as String, Map::class.java))
		assertNull(rows[1]["detail"])
		assertEquals(mapOf("notify" to true, "notifyStarted" to true), mapper.readValue(rows[2]["detail"] as String, Map::class.java))
	}

	@Test
	fun `보내기 전에 푸시를 받을 사람 수를 준다`() {
		fun audience() = http(port, "GET", "/admin/notices/audience", token = token).also { assertEquals(200, it.statusCode()) }.json()
		assertEquals(setOf("count"), audience().propertyNames().toSet())
		assertEquals(0, audience()["count"].asInt())

		user("reader", consented = true, devices = arrayOf("ExponentPushToken[reader-phone]", "ExponentPushToken[reader-tablet]"))
		user("second", consented = true, devices = arrayOf("ExponentPushToken[second-phone]"))
		user("suspended", consented = true, suspended = true, devices = arrayOf("ExponentPushToken[suspended-phone]"))
		user("silent", consented = false, devices = arrayOf("ExponentPushToken[silent-phone]"))
		user("web", consented = true)

		assertEquals(2, audience()["count"].asInt(), "푸시 동의 + 정지 아님 + 기기 — 마케팅 동의는 보지 않는다, 사람 수로 센다")
		assertEquals(401, http(port, "GET", "/admin/notices/audience").statusCode())
	}

	/** 알림 결과(푸시 · 알림함)가 공지 행에 적힐 때까지 기다린다(5초까지). */
	private fun awaitResult(id: String): JsonNode {
		val until = System.currentTimeMillis() + 5_000
		while (true) {
			val notice = http(port, "GET", "/admin/notices/$id", token = token).json()
			if ((!notice["push"].isNull && !notice["inbox"].isNull) || System.currentTimeMillis() > until) return notice
			Thread.sleep(50)
		}
	}

	@Test
	fun `보낸 결과(대상 기기 · 보냄 · 실패 · 보내지 않음, 알림함 넣은 수)를 공지에 적는다`() {
		user("reader", devices = arrayOf("ExponentPushToken[reader-phone]", "ExponentPushToken[reader-tablet]"))
		user("second", devices = arrayOf("ExponentPushToken[second-phone]"))
		user("web")
		expo.gone += "ExponentPushToken[reader-tablet]"
		val id = created("결과를 적는 공지")
		try {
			published(id, notify = true)
			val notice = awaitResult(id)
			assertEquals(setOf("devices", "sent", "failed", "skipped"), notice["push"].propertyNames().toSet())
			assertEquals(3, notice["push"]["devices"].asInt())
			assertEquals(2, notice["push"]["sent"].asInt())
			assertEquals(1, notice["push"]["failed"].asInt(), "지운 기기(DeviceNotRegistered)는 실패로 센다")
			assertEquals(0, notice["push"]["skipped"].asInt())
			assertEquals(setOf("added", "failed"), notice["inbox"].propertyNames().toSet())
			assertEquals(3, notice["inbox"]["added"].asInt(), "알림함은 모든 계정")
			assertEquals(false, notice["inbox"]["failed"].asBoolean())
			assertEquals(notice, adminList().single { it["id"].asString() == id }, "목록에도 같은 결과")
		} finally {
			expo.gone.clear()
		}
	}

	@Test
	fun `발송이 꺼져 보내지 않은 기기는 실패가 아니라 보내지 않음으로 센다`() {
		user("reader", devices = arrayOf("ExponentPushToken[reader-phone]"))
		val id = created("꺼진 발송")
		expo.skipAll = true
		try {
			published(id, notify = true)
			val push = awaitResult(id)["push"]
			assertEquals(listOf(1, 0, 0, 1), listOf(push["devices"].asInt(), push["sent"].asInt(), push["failed"].asInt(), push["skipped"].asInt()))
		} finally {
			expo.skipAll = false
		}
	}

	@Test
	fun `보내다 오류가 나면 대상 기기 모두를 실패로 적는다`() {
		user("reader", devices = arrayOf("ExponentPushToken[reader-phone]", "ExponentPushToken[reader-tablet]"))
		val id = created("터지는 발송")
		expo.explode = true
		try {
			published(id, notify = true)
			val push = awaitResult(id)["push"]
			assertEquals(listOf(2, 0, 2, 0), listOf(push["devices"].asInt(), push["sent"].asInt(), push["failed"].asInt(), push["skipped"].asInt()))
		} finally {
			expo.explode = false
		}
	}

	@Test
	fun `알림함 넣기가 실패하면 결과에 실패로 남고 푸시는 그대로 간다`() {
		user("reader", devices = arrayOf("ExponentPushToken[reader-phone]"))
		val id = created("알림함이 막힌 공지")
		jdbc.execute("create or replace function test_fail_notice_inbox() returns trigger language plpgsql as \$\$ begin raise exception 'test: inbox fails'; end \$\$")
		jdbc.execute("create trigger test_fail_notice_inbox before insert on notifications for each row when (new.kind = 'NOTICE') execute function test_fail_notice_inbox()")
		try {
			published(id, notify = true)
			val notice = awaitResult(id)
			assertEquals(0, notice["inbox"]["added"].asInt())
			assertEquals(true, notice["inbox"]["failed"].asBoolean(), "실패를 성공으로 보고하지 않는다")
			assertEquals(1, notice["push"]["sent"].asInt())
			assertEquals(0, noticeRows(id))
		} finally {
			jdbc.execute("drop trigger if exists test_fail_notice_inbox on notifications")
			jdbc.execute("drop function if exists test_fail_notice_inbox()")
		}
	}

	@Test
	fun `관리자 세션이 없으면 401 이다`() {
		val (_, session) = user("reader")
		for ((method, path) in listOf("GET" to "/admin/notices", "POST" to "/admin/notices", "POST" to "/admin/notices/${UUID.randomUUID()}/publish")) {
			assertEquals(401, http(port, method, path, "{}").statusCode(), "$method $path")
			assertEquals(401, http(port, method, path, "{}", session).statusCode(), "앱 사용자 토큰은 통하지 않는다")
		}
	}
}
