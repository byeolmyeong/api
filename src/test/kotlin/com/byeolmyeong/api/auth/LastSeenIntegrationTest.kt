package com.byeolmyeong.api.auth

import com.byeolmyeong.api.admin.AdminIntegrationTest
import com.byeolmyeong.api.admin.adminToken
import com.byeolmyeong.api.admin.resetAdminData
import com.byeolmyeong.api.admin.userAccount
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 마지막 접속 · 날마다 접속(결정 043 의 2). 사용자 세션으로 온 요청이 10분에 한 번만 쓴다.
@AdminIntegrationTest
class LastSeenIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var account: UUID
	private lateinit var token: String

	@BeforeEach
	fun clean() {
		// "10분 안" · "오늘" 을 DB 시계로 본다. 한국 자정 언저리(23:50 ~ 00:10)에는 날짜가 바뀌어 흔들리므로 건너뛴다.
		val minuteOfDay = jdbc.queryForObject("select extract(hour from now() at time zone 'Asia/Seoul') * 60 + extract(minute from now() at time zone 'Asia/Seoul')", Int::class.java)!!
		assumeFalse(minuteOfDay < 10 || minuteOfDay >= 23 * 60 + 50, "한국 자정 언저리")
		resetAdminData(jdbc)
		userAccount(jdbc, "seen-user").let { (id, t) -> account = id; token = t }
	}

	private fun lastSeen(): Timestamp? =
		jdbc.queryForObject("select last_seen_at from accounts where id = ?", Timestamp::class.java, account)

	private fun days(): List<Map<String, Any?>> = jdbc.queryForList(
		"select kst_date::text as day, first_hour_kst as hour from daily_active where account_id = ? order by kst_date",
		account,
	)

	private fun todayKst(): String = jdbc.queryForObject("select (now() at time zone 'Asia/Seoul')::date::text", String::class.java)!!

	private fun hourKst(): Int = jdbc.queryForObject("select extract(hour from now() at time zone 'Asia/Seoul')::int", Int::class.java)!!

	private fun request(): Int = http(port, "GET", "/auth/session", token = token).statusCode()

	@Test
	fun `처음 요청이면 마지막 접속을 쓰고 오늘 한국 날짜로 한 줄을 넣는다`() {
		assertNull(lastSeen())

		assertEquals(204, request())

		val seen = assertNotNull(lastSeen())
		assertTrue(seen.toInstant().isAfter(Instant.now().minusSeconds(60)), "마지막 접속이 지금이 아니다: $seen")
		assertEquals(listOf(mapOf<String, Any?>("day" to todayKst(), "hour" to hourKst())), days())
	}

	@Test
	fun `10분 안에 다시 오면 쓰지 않는다`() {
		jdbc.update("update accounts set last_seen_at = now() - interval '5 minutes' where id = ?", account)
		val before = lastSeen()

		assertEquals(204, request())
		assertEquals(204, request())

		assertEquals(before, lastSeen())
		assertEquals(emptyList(), days(), "10분 안이면 날마다 줄도 넣지 않는다(오늘 줄은 앞선 요청이 넣었다)")
	}

	@Test
	fun `10분이 지났으면 고쳐 쓰고 오늘 줄은 하나뿐이다`() {
		assertEquals(204, request())
		jdbc.update("update accounts set last_seen_at = now() - interval '11 minutes' where id = ?", account)

		assertEquals(204, request())

		assertTrue(lastSeen()!!.toInstant().isAfter(Instant.now().minusSeconds(60)))
		assertEquals(1, days().size)
	}

	@Test
	fun `날이 바뀌면 새 줄이 생기고 지난 줄은 그대로다`() {
		jdbc.update("update accounts set last_seen_at = now() - interval '1 day' where id = ?", account)
		jdbc.update(
			"insert into daily_active (kst_date, account_id, first_hour_kst) values ((now() at time zone 'Asia/Seoul')::date - 1, ?, 7)",
			account,
		)

		assertEquals(204, request())

		val rows = days()
		assertEquals(2, rows.size)
		assertEquals(7, rows[0]["hour"], "어제 줄을 고치지 않는다")
		assertEquals(todayKst(), rows[1]["day"])
	}

	@Test
	fun `관리자 요청과 세션 없는 요청은 세지 않는다`() {
		val admin = adminToken(port)
		assertEquals(200, http(port, "GET", "/admin/me", token = admin).statusCode())
		assertEquals(200, http(port, "GET", "/admin/stats", token = admin).statusCode())
		assertEquals(200, http(port, "GET", "/health").statusCode())
		assertEquals(401, http(port, "GET", "/auth/session", token = "no-such-token").statusCode())

		assertNull(lastSeen())
		assertEquals(0, jdbc.queryForObject("select count(*) from daily_active", Int::class.java))
	}

	@Test
	fun `계정을 지우면 날마다 줄도 함께 지워진다`() {
		assertEquals(204, request())

		jdbc.update("delete from accounts where id = ?", account)

		assertEquals(0, jdbc.queryForObject("select count(*) from daily_active where account_id = ?", Int::class.java, account))
	}
}
