package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 테스트가 "지금" 을 정하는 시계. 서버의 `Clock` 빈 자리에 들어간다. */
class MutableClock(@Volatile var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
	override fun getZone(): ZoneId = zone
	override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
	override fun instant(): Instant = now
}

@TestConfiguration(proxyBeanMethods = false)
class MutableClockConfiguration {
	@Bean
	@Primary
	fun testClock(): MutableClock = MutableClock(Instant.parse("2026-11-20T03:00:00Z"))
}

/**
 * 현황 v2 와 접속 차트(결정 043 의 3 · 4). "오늘" 은 한국 날짜이고 서버의 `Clock` 으로 정한다 — 테스트는 그 시계를 바꿔 끼운다.
 * 행은 표에 직접 넣는다.
 */
@AdminIntegrationTest
@Import(MutableClockConfiguration::class)
class AdminStatsV2ApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var clock: MutableClock

	private lateinit var token: String

	@BeforeEach
	fun clean() {
		clock.now = Instant.parse("2026-11-20T03:00:00Z") // 한국 11월 20일 12:00
		resetAdminData(jdbc)
		token = adminToken(port)
	}

	private fun account(createdAt: String, push: Boolean = false, marketing: Boolean = false, suspended: Boolean = false, nickname: String? = null): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"""
			insert into accounts (id, provider, provider_user_id, created_at, email, nickname, push_consented_at, marketing_consented_at, suspended_at)
			values (?, 'TEST', ?, ?::timestamptz, ?, ?, case when ? then now() end, case when ? then now() end, case when ? then now() end)
			""".trimIndent(),
			id, "user-$id", createdAt, "hidden-$id@secret-domain.example", nickname, push, marketing, suspended,
		)
		return id
	}

	private fun stats(): JsonNode {
		val response = http(port, "GET", "/admin/stats", token = token)
		assertEquals(200, response.statusCode(), response.body())
		return response.json()
	}

	private fun active(range: String): JsonNode {
		val response = http(port, "GET", "/admin/stats/active?range=$range", token = token)
		assertEquals(200, response.statusCode(), response.body())
		return response.json()
	}

	/** 점의 label → value. */
	private fun JsonNode.points(): Map<String, Int> = this["points"].toList().associate { it["label"].asString() to it["value"].asInt() }

	@Test
	fun `현황 v2 칸은 한국 날짜의 오늘로 세고 개인 정보가 없다`() {
		// 한국 11월 20일 = UTC 11월 19일 15:00 부터. 이번 주(오늘 포함 7일) = 한국 11월 14일 = UTC 11월 13일 15:00 부터.
		val today = account("2026-11-19T15:00:00Z", push = true, marketing = true, nickname = "비밀닉네임")
		val yesterday = account("2026-11-19T14:59:59Z", push = true)
		val weekFirst = account("2026-11-13T15:00:00Z", push = true, marketing = true, suspended = true)
		val beforeWeek = account("2026-11-13T14:59:59Z")
		val old = account("2026-10-01T00:00:00Z", push = true)
		account("2026-09-01T00:00:00Z")

		val text = """{"headline":"비밀스러운해석글"}"""
		for ((owner, kind, hash) in listOf(Triple(today, "SUMMARY_V2", "h1"), Triple(today, "SUMMARY_V2", "h2"), Triple(yesterday, "SUMMARY_V2", "h1"), Triple(weekFirst, "SUMMARY", "h1"))) {
			jdbc.update("insert into readings (id, owner_id, kind, chart_hash, body, share_id) values (?, ?, ?, ?, ?::jsonb, ?)", UUID.randomUUID(), owner, kind, hash, text, "share-$owner-$kind-$hash")
		}
		fun detail(owner: UUID, kind: String, hash: String, status: String, updatedAt: String = "2026-11-18T00:00:00Z") = jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, body, updated_at) values (?, ?, ?, ?, ?, ?::jsonb, ?::timestamptz)",
			UUID.randomUUID(), owner, kind, hash, status, if (status == "DONE") text else null, updatedAt,
		)
		detail(today, "ME", "h1", "DONE"); detail(yesterday, "ME", "h1", "DONE"); detail(today, "TIMING", "h1", "DONE")
		detail(weekFirst, "ME", "h1", "WRITING")
		detail(yesterday, "TIMING", "h1", "FAILED", updatedAt = "2026-11-19T15:00:00Z") // 오늘 실패
		detail(weekFirst, "ME", "h2", "FAILED", updatedAt = "2026-11-19T14:59:59Z") // 어제 실패

		fun inquiry(owner: UUID, status: String, createdAt: String, answer: String? = null) = jdbc.update(
			"insert into inquiries (id, owner_id, category, body, reply_email, status, created_at, answer_body) values (?, ?, 'BUG', '비밀스러운문의글 입니다 확인', 'hidden@secret-domain.example', ?, ?::timestamptz, ?)",
			UUID.randomUUID(), owner, status, createdAt, answer,
		)
		inquiry(today, "NEW", "2026-11-18T00:00:00Z")
		inquiry(yesterday, "IN_PROGRESS", "2026-11-17T10:00:00.789Z") // 가장 오래 기다린 것
		inquiry(yesterday, "ANSWERED", "2026-11-10T00:00:00Z", answer = "비밀답글")
		inquiry(today, "DONE", "2026-11-01T00:00:00Z")

		val json = stats()
		assertEquals("2026-11-20T03:00:00Z", json["generatedAt"].asString(), "센 때도 같은 시계다")
		assertEquals(
			mapper.readTree(
				"""
				{
					"users": {"total": 6, "joinedToday": 1, "joined7d": 3},
					"nicknames": {"total": 2},
					"detailReports": {"me": 2, "timing": 1, "generating": 1},
					"failuresToday": {"nickname": null, "report": 1},
					"inquiryQueue": {"waiting": 2, "oldestWaitingAt": "2026-11-17T10:00:00Z"},
					"consents": {"pushRate": 0.667, "marketingRate": 0.333, "adReachable": 1},
					"todaySky": null,
					"pushToday": null
				}
				""",
			),
			mapper.createObjectNode().also { node -> V2_KEYS.forEach { node.set(it, json[it]) } },
		)
		assertTrue(V2_KEYS.all { json.has(it) }, "값이 없는 칸도 키가 있다")
		// 있던 칸은 그대로다.
		assertEquals(6, json["accounts"]["total"].asInt())
		assertEquals(4, json["readings"]["total"].asInt())

		for (hidden in listOf("비밀", "hidden", "secret-domain", today.toString(), beforeWeek.toString(), old.toString(), "share-", "h1")) {
			assertFalse(hidden in json.toString(), "응답에 '$hidden' 이 있다")
		}
	}

	@Test
	fun `계정이 없으면 비율은 0 이고 기다리는 문의가 없으면 가장 오래된 때는 null 이다`() {
		val json = stats()
		assertEquals(mapper.readTree("""{"pushRate": 0.0, "marketingRate": 0.0, "adReachable": 0}"""), json["consents"])
		assertEquals(mapper.readTree("""{"waiting": 0, "oldestWaitingAt": null}"""), json["inquiryQueue"])
		assertEquals(mapper.readTree("""{"total": 0, "joinedToday": 0, "joined7d": 0}"""), json["users"])
	}

	@Test
	fun `오늘 가입은 한국 자정에 바뀐다`() {
		account("2026-11-19T14:59:59Z")
		account("2026-11-19T15:00:00Z")

		clock.now = Instant.parse("2026-11-19T14:59:59Z") // 한국 11월 19일 23:59:59
		assertEquals(1, stats()["users"]["joinedToday"].asInt())
		clock.now = Instant.parse("2026-11-19T15:00:00Z") // 한국 11월 20일 00:00:00
		assertEquals(1, stats()["users"]["joinedToday"].asInt())
		clock.now = Instant.parse("2026-11-20T14:59:59Z")
		assertEquals(1, stats()["users"]["joinedToday"].asInt())
		clock.now = Instant.parse("2026-11-20T15:00:00Z") // 한국 11월 21일
		assertEquals(0, stats()["users"]["joinedToday"].asInt())
		assertEquals(2, stats()["users"]["joined7d"].asInt())
	}

	/** 날마다 접속 줄: 한국 날짜 · 그날 처음 접속한 시. */
	private fun seen(account: UUID, date: String, hour: Int) =
		jdbc.update("insert into daily_active (kst_date, account_id, first_hour_kst) values (?::date, ?, ?)", date, account, hour)

	private fun users(n: Int): List<UUID> = (1..n).map { account("2026-01-01T00:00:00Z") }

	@Test
	fun `오늘은 0시부터 23시까지 시간대별이고 빈 시는 0 이다`() {
		val u = users(5)
		seen(u[0], "2026-11-20", 0); seen(u[1], "2026-11-20", 0); seen(u[2], "2026-11-20", 9); seen(u[3], "2026-11-20", 23)
		seen(u[0], "2026-11-19", 22); seen(u[4], "2026-11-19", 3)
		seen(u[4], "2026-11-21", 1) // 내일 줄은 세지 않는다

		val json = active("today")
		assertEquals(setOf("range", "points", "total", "average", "previousTotal"), json.propertyNames().toSet())
		assertEquals("today", json["range"].asString())
		assertEquals((0..23).map { "%02d".format(it) }, json["points"].toList().map { it["label"].asString() })
		assertEquals(setOf("label", "value"), json["points"][0].propertyNames().toSet())
		val points = json.points()
		assertEquals(mapOf("00" to 2, "09" to 1, "23" to 1), points.filterValues { it != 0 })
		assertEquals(4, json["total"].asInt())
		assertEquals(0.2, json["average"].asDouble(), "24개 점의 평균 4/24 = 0.1666… 을 소수 첫째 자리로")
		assertEquals(2, json["previousTotal"].asInt(), "어제")
	}

	@Test
	fun `이번 주는 오늘 포함 7일 일별이고 앞 기간은 그 앞 7일이다`() {
		val u = users(7)
		seen(u[0], "2026-11-20", 0); seen(u[1], "2026-11-20", 0); seen(u[2], "2026-11-20", 9); seen(u[3], "2026-11-20", 23)
		seen(u[0], "2026-11-19", 22); seen(u[4], "2026-11-19", 3)
		seen(u[0], "2026-11-14", 5)
		// 앞 7일(11월 7일 ~ 13일): u1 두 번, u5 한 번 → 고유 2
		seen(u[1], "2026-11-13", 1); seen(u[1], "2026-11-10", 1); seen(u[5], "2026-11-07", 1)
		seen(u[6], "2026-11-06", 1) // 앞 기간보다 앞

		val json = active("week")
		assertEquals("week", json["range"].asString())
		assertEquals(
			linkedMapOf("2026-11-14" to 1, "2026-11-15" to 0, "2026-11-16" to 0, "2026-11-17" to 0, "2026-11-18" to 0, "2026-11-19" to 2, "2026-11-20" to 4),
			json.points(),
		)
		assertEquals(5, json["total"].asInt(), "기간 안 고유 접속자(u0 은 세 날이지만 한 명)")
		assertEquals(1.0, json["average"].asDouble())
		assertEquals(2, json["previousTotal"].asInt())
	}

	@Test
	fun `이번 달은 1일부터 오늘까지이고 앞 기간은 지난달 1일부터 같은 날까지다`() {
		val u = users(10)
		seen(u[0], "2026-11-20", 0); seen(u[1], "2026-11-20", 0); seen(u[2], "2026-11-20", 9); seen(u[3], "2026-11-20", 23)
		seen(u[0], "2026-11-19", 22); seen(u[4], "2026-11-19", 3)
		seen(u[0], "2026-11-14", 5); seen(u[1], "2026-11-13", 1); seen(u[1], "2026-11-10", 1); seen(u[5], "2026-11-07", 1); seen(u[6], "2026-11-06", 1)
		// 지난달: 10월 1일 ~ 20일
		seen(u[0], "2026-10-01", 1); seen(u[7], "2026-10-20", 1); seen(u[8], "2026-10-21", 1); seen(u[9], "2026-09-30", 1)

		val json = active("month")
		assertEquals("month", json["range"].asString())
		val points = json.points()
		assertEquals((1..20).map { LocalDate.of(2026, 11, it).toString() }, points.keys.toList())
		assertEquals(mapOf("2026-11-06" to 1, "2026-11-07" to 1, "2026-11-10" to 1, "2026-11-13" to 1, "2026-11-14" to 1, "2026-11-19" to 2, "2026-11-20" to 4), points.filterValues { it != 0 })
		assertEquals(7, json["total"].asInt())
		assertEquals(0.6, json["average"].asDouble(), "11/20 = 0.55 는 올린다")
		assertEquals(2, json["previousTotal"].asInt())
	}

	@Test
	fun `지난달이 짧으면 앞 기간은 지난달 말일까지다`() {
		val u = users(3)
		clock.now = Instant.parse("2026-03-31T03:00:00Z") // 한국 3월 31일
		seen(u[0], "2026-02-01", 1); seen(u[1], "2026-02-28", 1); seen(u[2], "2026-03-01", 1)

		val json = active("month")
		assertEquals(31, json["points"].size())
		assertEquals(1, json["total"].asInt())
		assertEquals(2, json["previousTotal"].asInt(), "2월 1일 ~ 28일")
	}

	@Test
	fun `오늘과 이번 주는 한국 자정(UTC 15시)에 넘어간다`() {
		val u = users(5)
		seen(u[0], "2026-11-20", 0); seen(u[1], "2026-11-20", 0); seen(u[2], "2026-11-20", 9); seen(u[3], "2026-11-20", 23)
		seen(u[0], "2026-11-19", 22); seen(u[4], "2026-11-19", 3)

		clock.now = Instant.parse("2026-11-19T14:59:59Z") // 한국 11월 19일 23:59:59
		val before = active("today")
		assertEquals(mapOf("03" to 1, "22" to 1), before.points().filterValues { it != 0 })
		assertEquals(listOf(2, 0), listOf(before["total"].asInt(), before["previousTotal"].asInt()))
		assertEquals("2026-11-19", active("week")["points"].last()["label"].asString())

		clock.now = Instant.parse("2026-11-19T15:00:00Z") // 한국 11월 20일 00:00:00
		val after = active("today")
		assertEquals(listOf(4, 2), listOf(after["total"].asInt(), after["previousTotal"].asInt()))
		assertEquals("2026-11-20", active("week")["points"].last()["label"].asString())
	}

	@Test
	fun `기간이 없거나 틀리면 400 이고 관리자만 부른다`() {
		for (query in listOf("", "?range=", "?range=TODAY", "?range=year", "?range=today,week")) {
			val response = http(port, "GET", "/admin/stats/active$query", token = token)
			assertEquals(400, response.statusCode(), query)
			assertEquals("""{"error":"INVALID_RANGE"}""", response.body(), query)
		}
		val (_, userToken) = userAccount(jdbc, "active-chart-user")
		for (bad in listOf(null, userToken)) {
			val response = http(port, "GET", "/admin/stats/active?range=today", token = bad)
			assertEquals(401, response.statusCode())
		}
	}

	companion object {
		private val V2_KEYS = listOf("users", "nicknames", "detailReports", "failuresToday", "inquiryQueue", "consents", "todaySky", "pushToday")
	}
}
