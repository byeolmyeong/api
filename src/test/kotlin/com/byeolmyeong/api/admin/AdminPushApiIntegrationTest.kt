package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode

private val SEOUL = ZoneId.of("Asia/Seoul")

/** 테스트가 옮기는 시계(한국 시각으로 둔다). */
class MovableClock(var instant: Instant) : Clock() {
	override fun getZone(): ZoneId = SEOUL
	override fun withZone(zone: ZoneId): Clock = this
	override fun instant(): Instant = instant
}

/** admin 이 낸 발송 요청(결정 043 의 6)을 모은다 — 실제 보내기는 push(S⑩)가 듣는다. */
class PushRequests {
	val events = CopyOnWriteArrayList<PushEventRequested>()

	@EventListener
	fun on(event: PushEventRequested) {
		events += event
	}
}

/** 광고 푸시 발송(결정 043 의 6): 대상 수 · 보내기(하루 한 번 · 야간 금지) · 보낼 수 있는지 · 발송 내역. */
@AdminIntegrationTest
@Import(AdminPushApiIntegrationTest.FakeConfig::class)
class AdminPushApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun movableClock() = MovableClock(Instant.now())

		@Bean
		fun pushRequests() = PushRequests()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var clock: MovableClock

	@Autowired
	private lateinit var requests: PushRequests

	@Autowired
	private lateinit var publisher: ApplicationEventPublisher

	private lateinit var token: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		jdbc.update("delete from push_events")
		jdbc.update("delete from audit_logs")
		requests.events.clear()
		at(2026, 11, 20, 14, 0)
		token = adminToken(port)
	}

	/** 한국 시각으로 시계를 옮긴다. */
	private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) {
		clock.instant = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SEOUL).toInstant()
	}

	private fun send(title: String = "새 기능: 이번 달 눈여겨볼 날", body: String = "시기 탭에서 확인해 보세요.", target: String = "TIMING_MONTH"): HttpResponse<String> =
		http(port, "POST", "/admin/push/events", json(mapOf("title" to title, "body" to body, "target" to target)), token)

	private fun get(path: String): HttpResponse<String> = http(port, "GET", path, token = token)

	private fun assertError(status: Int, code: String, response: HttpResponse<String>, what: String = "") {
		assertEquals(status, response.statusCode(), "$what ${response.body()}")
		assertEquals("""{"error":"$code"}""", response.body(), what)
	}

	/** push 가 보내고 PushEventSent 로 성공 수를 채울 때까지 기다려 맨 위 내역을 돌려준다. */
	private fun awaitSent(): JsonNode {
		val deadline = System.currentTimeMillis() + 5_000
		while (true) {
			val row = get("/admin/push/events").json()["items"][0]
			if (!row["succeeded"].isNull) return row
			check(System.currentTimeMillis() < deadline) { "push 가 보낸 결과를 알리지 않는다" }
			Thread.sleep(50)
		}
	}

	@Test
	fun `기기 표가 아직 없으면(푸시 묶음 전) 대상 수는 0 이다`() {
		val response = get("/admin/push/audience")

		assertEquals(200, response.statusCode(), response.body())
		assertEquals("""{"count":0}""", response.body())
	}

	@Test
	fun `보내면 202 로 답하고 광고 표기와 수신 거부 줄을 붙여 발송을 요청하며 내역 · 감사 기록에 남는다`() {
		val response = send()

		assertEquals(202, response.statusCode(), response.body())
		val sent = response.json()
		assertEquals(setOf("id", "recipients"), sent.propertyNames().toSet())
		assertEquals(0, sent["recipients"].asInt())
		val id = UUID.fromString(sent["id"].asString())

		val event = requests.events.single()
		assertEquals(id, event.eventId)
		assertEquals("(광고) 새 기능: 이번 달 눈여겨볼 날", event.title)
		assertEquals("시기 탭에서 확인해 보세요.\n수신 거부: 마이 › 알림", event.body)
		assertEquals("TIMING_MONTH", event.target)

		val history = get("/admin/push/events").json()
		assertEquals(1, history["total"].asInt())
		val row = history["items"][0]
		assertEquals(setOf("id", "sentAt", "title", "recipients", "succeeded", "failed", "sentBy"), row.propertyNames().toSet())
		assertEquals("새 기능: 이번 달 눈여겨볼 날", row["title"].asString(), "내역에는 관리자가 쓴 제목 그대로")
		assertEquals("2026-11-20T05:00:00Z", row["sentAt"].asString())
		assertEquals(ROOT_ID, row["sentBy"].asString())
		// push(S⑩)가 커밋 뒤 대상(지금 0명)에게 보내고 PushEventSent 로 알리면 성공 · 실패 수가 채워진다(그 전에는 null — 보내는 중).
		val done = awaitSent()
		assertEquals(listOf(0, 0), listOf(done["succeeded"].asInt(), done["failed"].asInt()), "대상이 없으면 0 · 0 으로 끝난다")

		val audit = jdbc.queryForMap("select actor_type, target_type, target_id from audit_logs where action = 'PUSH_EVENT_SENT'")
		assertEquals("ADMIN", audit["actor_type"])
		assertEquals("PUSH_EVENT", audit["target_type"])
		assertEquals(id.toString(), audit["target_id"])
	}

	@Test
	fun `push 가 다 보냈다고 알리면 내역의 성공 · 실패 수가 채워진다`() {
		val id = UUID.fromString(send().json()["id"].asString())
		awaitSent() // 실제 push 가 대상 0명으로 먼저 알린 뒤에 내야 늦게 온 쪽이 덮어쓰지 않는다

		publisher.publishEvent(PushEventSent(id, 3, 2, 1))

		val row = get("/admin/push/events").json()["items"][0]
		assertEquals(3, row["recipients"].asInt())
		assertEquals(2, row["succeeded"].asInt())
		assertEquals(1, row["failed"].asInt())
	}

	@Test
	fun `하루 한 번 — 관리자 누구든 그날 이미 보냈으면 409 DAILY_LIMIT 이고 상태가 까닭과 다음 가능 시각을 준다`() {
		assertEquals(202, send().statusCode())
		at(2026, 11, 20, 18, 30)

		assertError(409, "DAILY_LIMIT", send(title = "두 번째"))
		assertEquals(1, requests.events.size, "두 번째는 요청하지 않는다")

		val status = get("/admin/push/status")
		assertEquals(200, status.statusCode(), status.body())
		assertEquals(
			"""{"canSend":false,"reason":"DAILY_LIMIT","sentTodayAt":"2026-11-20T05:00:00Z","sentTodayBy":"$ROOT_ID","nextAvailableAt":"2026-11-20T23:00:00Z"}""",
			status.body(),
		)

		at(2026, 11, 21, 9, 0)
		assertEquals(202, send(title = "다음 날").statusCode(), "다음 날은 다시 보낼 수 있다")
	}

	@Test
	fun `야간(한국 21시 ~ 8시)에는 409 QUIET_HOURS 이고 상태의 다음 가능 시각은 그다음 8시다`() {
		at(2026, 11, 20, 21, 0)
		assertError(409, "QUIET_HOURS", send(), "21:00")
		assertEquals(
			"""{"canSend":false,"reason":"QUIET_HOURS","sentTodayAt":null,"sentTodayBy":null,"nextAvailableAt":"2026-11-20T23:00:00Z"}""",
			get("/admin/push/status").body(),
		)

		at(2026, 11, 21, 7, 59)
		assertError(409, "QUIET_HOURS", send(), "07:59")
		assertEquals("2026-11-20T23:00:00Z", get("/admin/push/status").json()["nextAvailableAt"].asString(), "새벽이면 그날 8시")

		at(2026, 11, 21, 8, 0)
		assertEquals("""{"canSend":true,"reason":null,"sentTodayAt":null,"sentTodayBy":null,"nextAvailableAt":null}""", get("/admin/push/status").body())
		assertEquals(202, send().statusCode(), "08:00 부터 된다")
		assertTrue(requests.events.size == 1)
	}

	@Test
	fun `제목 1~40자 · 본문 1~120자(코드 포인트) · 정해 둔 이동 화면만 받는다`() {
		assertError(400, "INVALID_TITLE", send(title = "   "))
		assertError(400, "INVALID_TITLE", send(title = "가".repeat(41)))
		assertError(400, "INVALID_BODY", send(body = ""))
		assertError(400, "INVALID_BODY", send(body = "나".repeat(121)))
		assertError(400, "INVALID_TARGET", send(target = "SETTINGS"))
		assertError(400, "INVALID_REQUEST", http(port, "POST", "/admin/push/events", """["x"]""", token))
		assertTrue(requests.events.isEmpty())

		assertEquals(202, send(title = "🌙".repeat(40), body = "별".repeat(120), target = "HOME").statusCode(), "경계값은 된다")
	}

	@Test
	fun `발송 내역은 새것부터 쪽으로 나뉘고 쪽 값이 틀리면 400 INVALID_PAGE`() {
		for ((day, title) in listOf(18 to "첫째", 19 to "둘째", 20 to "셋째")) {
			at(2026, 11, day, 12, 0)
			assertEquals(202, send(title = title).statusCode())
		}

		val page = get("/admin/push/events?page=0&size=2").json()
		assertEquals(listOf("셋째", "둘째"), page["items"].toList().map { it["title"].asString() })
		assertEquals(3, page["total"].asInt())
		assertEquals(listOf("첫째"), get("/admin/push/events?page=1&size=2").json()["items"].toList().map { it["title"].asString() })
		assertError(400, "INVALID_PAGE", get("/admin/push/events?size=0"))
		assertError(400, "INVALID_PAGE", get("/admin/push/events?page=-1"))
	}
}
