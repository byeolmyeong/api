package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EMAIL = "starlight.kim@example.com"

/** 관리자 사용자 관리(결정 043 의 1 · 044): 목록 · 상세 · 정지 · 해제 · 탈퇴 대행. 이메일 원문 · 출생 정보 값은 어떤 응답에도 없다. */
@ExtendWith(OutputCaptureExtension::class)
@AdminIntegrationTest
class AdminUsersApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var token: String
	private lateinit var rootId: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		jdbc.update("delete from audit_logs")
		token = adminToken(port)
		rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)!!
	}

	/** 계정 하나. [joinedDaysAgo] 일 전에 가입한 것으로 둔다. */
	private fun user(
		nickname: String?, provider: String = "KAKAO", email: String? = null, joinedDaysAgo: Int = 0, avatar: Int? = 3,
	): Pair<UUID, String> {
		val (id, session) = userAccount(jdbc, "user-${UUID.randomUUID()}")
		jdbc.update(
			"update accounts set nickname = ?, provider = ?, email = ?, avatar = ?, created_at = now() - make_interval(days => ?) where id = ?",
			nickname, provider, email, avatar, joinedDaysAgo, id,
		)
		return id to session
	}

	private fun get(path: String): HttpResponse<String> = http(port, "GET", path, token = token)

	private fun post(path: String, body: Any?): HttpResponse<String> = http(port, "POST", path, json(body), token)

	private fun assertError(status: Int, code: String, response: HttpResponse<String>, what: String = "") {
		assertEquals(status, response.statusCode(), "$what ${response.body()}")
		assertEquals("""{"error":"$code"}""", response.body(), what)
	}

	private fun ids(response: HttpResponse<String>): List<String> = response.json()["items"].toList().map { it["nickname"].asString() }

	@Test
	fun `목록은 가입이 늦은 것부터이고 한 줄의 모양이 정해져 있다`() {
		val (old, _) = user("오래된이", joinedDaysAgo = 10)
		user("새로온이", provider = "APPLE", email = EMAIL)
		jdbc.update("update accounts set last_seen_at = now() where id = ?", old)
		jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, 'ME', 'h', 'DONE', '{}'::jsonb), (?, ?, 'TIMING', 'h', 'WRITING', null)",
			UUID.randomUUID(), old, UUID.randomUUID(), old,
		)

		val response = get("/admin/users")

		assertEquals(200, response.statusCode(), response.body())
		val page = response.json()
		assertEquals(setOf("items", "page", "size", "total"), page.propertyNames().toSet())
		assertEquals(listOf(0, 20, 2), listOf(page["page"].asInt(), page["size"].asInt(), page["total"].asInt()))
		assertEquals(listOf("새로온이", "오래된이"), ids(response))
		val row = page["items"][1]
		assertEquals(
			setOf("id", "nickname", "profileNo", "emailMasked", "provider", "joinedAt", "lastSeenAt", "reports", "status"),
			row.propertyNames().toSet(),
		)
		assertEquals(old.toString(), row["id"].asString())
		assertEquals(3, row["profileNo"].asInt())
		assertEquals("KAKAO", row["provider"].asString())
		assertEquals("ACTIVE", row["status"].asString())
		assertEquals(listOf("DONE", "GENERATING"), listOf(row["reports"]["me"].asString(), row["reports"]["timing"].asString()))
		assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(row["lastSeenAt"].asString()))
		assertEquals("star****@example.com", page["items"][0]["emailMasked"].asString(), "앞 4글자 + ****@도메인")
		assertTrue(row["emailMasked"].isNull, "이메일이 없으면 null")
		assertEquals(listOf("NONE", "NONE"), listOf(page["items"][0]["reports"]["me"].asString(), page["items"][0]["reports"]["timing"].asString()))
		assertFalse(EMAIL in response.body(), "이메일 원문은 없다")
	}

	@Test
	fun `찾기 · 상태 · 로그인 수단 · 가입일로 거르고 쪽을 나눈다`() {
		val (suspended, _) = user("정지된김", provider = "GOOGLE", email = "Zeta.Park@mail.com", joinedDaysAgo = 3)
		user("활성이", provider = "KAKAO", email = "alpha@mail.com", joinedDaysAgo = 1)
		user("또다른김", provider = "APPLE", joinedDaysAgo = 40)
		jdbc.update("update accounts set suspended_at = now() where id = ?", suspended)

		assertEquals(listOf("정지된김", "또다른김"), ids(get("/admin/users?q=%EA%B9%80")), "닉네임 일부(김)")
		assertEquals(listOf("정지된김"), ids(get("/admin/users?q=ZETA")), "이메일 앞부분, 대소문자 무시")
		assertEquals(emptyList(), ids(get("/admin/users?q=park")), "이메일은 앞부분만 본다")
		assertEquals(listOf("정지된김"), ids(get("/admin/users?status=SUSPENDED")))
		assertEquals(listOf("활성이", "또다른김"), ids(get("/admin/users?status=ACTIVE")))
		assertEquals(listOf("또다른김"), ids(get("/admin/users?provider=APPLE")))
		assertEquals(listOf("활성이", "정지된김"), ids(get("/admin/users?joinedFrom=${kstDaysAgo(7)}&joinedTo=${kstDaysAgo(0)}")))
		assertEquals(listOf("정지된김"), ids(get("/admin/users?joinedFrom=${kstDaysAgo(3)}&joinedTo=${kstDaysAgo(3)}")), "끝 날짜를 포함한다")
		assertEquals(3, get("/admin/users?q=&status=&provider=").json()["total"].asInt(), "빈 값은 없는 것")

		val first = get("/admin/users?size=10&page=0").json()
		assertEquals(3, first["items"].size())
		assertEquals(0, get("/admin/users?size=10&page=1").json()["items"].size())
		assertEquals(3, get("/admin/users?size=10&page=1").json()["total"].asInt())
	}

	private fun kstDaysAgo(days: Int): String =
		jdbc.queryForObject("select ((now() at time zone 'Asia/Seoul')::date - ?)::text", String::class.java, days)!!

	@Test
	fun `틀린 조건은 400 이다`() {
		assertError(400, "INVALID_STATUS", get("/admin/users?status=active"))
		assertError(400, "INVALID_PROVIDER", get("/admin/users?provider=DEV"))
		for (bad in listOf("size=15", "size=0", "size=200", "page=-1", "page=a")) assertError(400, "INVALID_PAGE", get("/admin/users?$bad"), bad)
		for (bad in listOf("joinedFrom=2026-13-01", "joinedTo=20261101", "joinedFrom=2026-11-20&joinedTo=2026-11-01", "joinedFrom=2025-01-01&joinedTo=2026-11-20")) {
			assertError(400, "INVALID_DATE_RANGE", get("/admin/users?$bad"), bad)
		}
	}

	@Test
	fun `상세는 동의 · 출생 정보 있음 · 생성 내역 · 문의 · 최근 활동을 주고 값은 주지 않는다`() {
		val (id, session) = user("상세보기", email = EMAIL)
		jdbc.update(
			"update accounts set terms_version = '2026-11-20', privacy_version = '2026-11-20', push_consented_at = now(), marketing_consented_at = null where id = ?",
			id,
		)
		jdbc.update("insert into profiles (account_id, birth_date, birth_time, place_id) values (?, '1990-03-15', '14:30', 1)", id)
		jdbc.update("insert into readings (id, owner_id, kind, chart_hash, body, share_id) values (?, ?, 'SUMMARY_V2', 'h', '{}'::jsonb, ?)", UUID.randomUUID(), id, UUID.randomUUID().toString())
		jdbc.update("insert into detail_reports (id, owner_id, kind, chart_hash, status) values (?, ?, 'TIMING', 'h', 'FAILED')", UUID.randomUUID(), id)
		val inquiry = UUID.randomUUID()
		jdbc.update("insert into inquiries (id, owner_id, category, body) values (?, ?, 'BUG', '앱이 멈춰요 열 글자 넘게')", inquiry, id)
		assertEquals(200, http(port, "GET", "/account", token = session).statusCode())
		jdbc.update(
			"insert into audit_logs (id, actor_type, actor_id, actor_label, action, outcome) values (?, 'USER', ?, 'ref', 'PROFILE_SAVED', 'SUCCESS')",
			UUID.randomUUID(), id.toString(),
		)

		val response = get("/admin/users/$id")

		assertEquals(200, response.statusCode(), response.body())
		val user = response.json()
		for (key in listOf("consents", "birth", "generations", "inquiries", "recent", "suspension")) assertTrue(user.has(key), key)
		assertEquals(listOf("2026-11-20", "2026-11-20"), listOf(user["consents"]["termsVersion"].asString(), user["consents"]["privacyVersion"].asString()))
		assertFalse(user["consents"]["pushAt"].isNull)
		assertTrue(user["consents"]["marketingAt"].isNull)
		assertEquals(mapOf("registered" to true, "hasTime" to true, "hasPlace" to true), user["birth"].properties().toList().associate { it.key to it.value.asBoolean() })
		assertEquals(
			mapOf("NICKNAME" to "DONE", "REPORT_TIMING" to "FAILED", "REPORT_ME" to "NONE"),
			user["generations"].toList().associate { it["kind"].asString() to it["status"].asString() },
		)
		assertEquals(listOf(inquiry.toString()), user["inquiries"].toList().map { it["id"].asString() })
		assertEquals(setOf("id", "category", "status", "createdAt"), user["inquiries"][0].propertyNames().toSet())
		assertEquals(listOf("PROFILE_SAVED"), user["recent"].toList().map { it["action"].asString() })
		assertTrue(user["suspension"].isNull)
		for (secret in listOf(EMAIL, "1990", "14:30", "03-15")) assertFalse(secret in response.body(), "응답에 있으면 안 되는 값: $secret")
	}

	@Test
	fun `살아 있다는 표시가 2분 넘게 끊긴 쓰는 중은 FAILED 로 보인다 — 재생성할 수 있게`() {
		val (id, _) = user("멈춘풀이")
		jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, active_at) values (?, ?, 'ME', 'h', 'WRITING', now() - interval '3 minutes'), (?, ?, 'TIMING', 'h', 'WRITING', now())",
			UUID.randomUUID(), id, UUID.randomUUID(), id,
		)

		val row = get("/admin/users").json()["items"][0]
		assertEquals(listOf("FAILED", "GENERATING"), listOf(row["reports"]["me"].asString(), row["reports"]["timing"].asString()))
		val generations = get("/admin/users/$id").json()["generations"].toList().associate { it["kind"].asString() to it["status"].asString() }
		assertEquals("FAILED", generations["REPORT_ME"])
		assertEquals("GENERATING", generations["REPORT_TIMING"])
	}

	@Test
	fun `상세에 오늘의 하늘 알림과 등록 기기가 보이고 토큰은 없다`() {
		val (id, _) = user("기기있음")
		jdbc.update("update accounts set push_consented_at = now(), today_sky_time = '07:30' where id = ?", id)
		jdbc.update("insert into push_devices (expo_token, account_id, platform, app_version) values ('ExponentPushToken[secret-token-1]', ?, 'IOS', '1.0.0')", id)

		val response = get("/admin/users/$id")
		val user = response.json()

		assertEquals(mapOf("on" to "true", "time" to "07:30"), user["todaySky"].properties().toList().associate { it.key to it.value.asString() })
		val device = user["devices"].toList().single()
		assertEquals(setOf("platform", "appVersion", "lastSeenAt"), device.propertyNames().toSet())
		assertEquals(listOf("IOS", "1.0.0"), listOf(device["platform"].asString(), device["appVersion"].asString()))
		assertFalse("secret-token" in response.body(), "기기 토큰은 응답에 없다")
	}

	@Test
	fun `없는 사용자는 404 이다`() {
		assertError(404, "NOT_FOUND", get("/admin/users/${UUID.randomUUID()}"))
		assertError(404, "NOT_FOUND", get("/admin/users/not-a-uuid"))
		assertError(404, "NOT_FOUND", post("/admin/users/${UUID.randomUUID()}/suspend", mapOf("reason" to "사유")))
	}

	@Test
	fun `정지하면 앱이 막히고 상세에 정지 카드가 보이며 감사 기록이 남는다 — 해제하면 다시 된다`() {
		val (id, session) = user("정지대상")

		val suspended = post("/admin/users/$id/suspend", mapOf("reason" to "  광고 글 반복  "))
		assertEquals(200, suspended.statusCode(), suspended.body())
		assertEquals("SUSPENDED", suspended.json()["status"].asString())
		val card = suspended.json()["suspension"]
		assertEquals("광고 글 반복", card["reason"].asString())
		assertEquals(listOf(rootId, ROOT_ID), listOf(card["by"]["id"].asString(), card["by"]["name"].asString()))
		assertEquals(403, http(port, "GET", "/profile", token = session).statusCode())
		assertError(409, "ALREADY_SUSPENDED", post("/admin/users/$id/suspend", mapOf("reason" to "또")))

		val lifted = post("/admin/users/$id/unsuspend", mapOf("reason" to "오해였음"))
		assertEquals(200, lifted.statusCode())
		assertEquals("ACTIVE", lifted.json()["status"].asString())
		assertTrue(lifted.json()["suspension"].isNull)
		assertEquals(404, http(port, "GET", "/profile", token = session).statusCode(), "막히지 않는다(출생 정보가 없어 404)")
		assertError(409, "NOT_SUSPENDED", post("/admin/users/$id/unsuspend", mapOf("reason" to "또")))

		val audit = jdbc.queryForList("select action, target_type, target_id, detail::text as detail from audit_logs where actor_type = 'ADMIN' and action like 'USER_%' order by occurred_at")
		assertEquals(
			listOf(
				mapOf<String, Any?>("action" to "USER_SUSPENDED", "target_type" to "USER", "target_id" to id.toString(), "detail" to null),
				mapOf<String, Any?>("action" to "USER_UNSUSPENDED", "target_type" to "USER", "target_id" to id.toString(), "detail" to null),
			),
			audit,
		)
		assertEquals(
			listOf("SUSPEND" to "광고 글 반복", "UNSUSPEND" to "오해였음"),
			jdbc.queryForList("select action, reason from account_suspensions where account_id = ? order by created_at", id).map { it["action"] to it["reason"] },
			"사유는 감사 기록이 아니라 정지 내역에 남는다",
		)
	}

	@Test
	fun `정지 · 해제 사유는 1~200자이고 틀리면 아무것도 바꾸지 않는다`() {
		val (id, _) = user("사유검사")
		for (bad in listOf(null, "", "   ", "가".repeat(201))) assertError(400, "INVALID_REASON", post("/admin/users/$id/suspend", mapOf("reason" to bad)), "$bad")
		assertError(400, "INVALID_REQUEST", post("/admin/users/$id/suspend", mapOf("reason" to 1)))
		assertNull(jdbc.queryForObject("select suspended_at from accounts where id = ?", java.sql.Timestamp::class.java, id))
		assertEquals(200, post("/admin/users/$id/suspend", mapOf("reason" to "가".repeat(200))).statusCode())
	}

	@Test
	fun `탈퇴 대행은 닉네임이 맞아야 하고 앱의 계정 삭제와 같이 지운다`(output: CapturedOutput) {
		val (id, session) = user("지울사람", email = EMAIL)
		jdbc.update("insert into profiles (account_id, birth_date, birth_time, place_id) values (?, '1990-03-15', null, null)", id)
		jdbc.update("insert into inquiries (id, owner_id, category, body) values (?, ?, 'ACCOUNT', '탈퇴하고 싶어요 열 글자 넘게')", UUID.randomUUID(), id)

		assertError(400, "CONFIRM_MISMATCH", http(port, "DELETE", "/admin/users/$id", json(mapOf("reason" to "문의로 요청", "confirmNickname" to "지울 사람")), token))
		assertError(400, "CONFIRM_MISMATCH", http(port, "DELETE", "/admin/users/$id", json(mapOf("reason" to "문의로 요청", "confirmNickname" to " ")), token), "닉네임이 있으면 비워서는 못 지운다")
		assertError(400, "INVALID_REASON", http(port, "DELETE", "/admin/users/$id", json(mapOf("reason" to "", "confirmNickname" to "지울사람")), token))
		assertEquals(1, jdbc.queryForObject("select count(*) from accounts where id = ?", Int::class.java, id))

		val deleted = http(port, "DELETE", "/admin/users/$id", json(mapOf("reason" to "문의로 요청", "confirmNickname" to "지울사람")), token)

		assertEquals(204, deleted.statusCode(), deleted.body())
		for (table in listOf("accounts" to "id", "profiles" to "account_id", "inquiries" to "owner_id", "sessions" to "account_id")) {
			assertEquals(0, jdbc.queryForObject("select count(*) from ${table.first} where ${table.second} = ?", Int::class.java, id), table.first)
		}
		assertEquals(401, http(port, "GET", "/account", token = session).statusCode())
		assertEquals(
			listOf("USER_DELETED_BY_ADMIN"),
			jdbc.queryForList("select action from audit_logs where target_id = ? or actor_id = ?", String::class.java, id.toString(), id.toString()),
			"관리자가 지운 것만 남는다(사용자가 지운 것처럼 남기지 않는다)",
		)
		assertError(404, "NOT_FOUND", http(port, "DELETE", "/admin/users/$id", json(mapOf("reason" to "다시", "confirmNickname" to "지울사람")), token))
		assertFalse("문의로 요청" in output.all, "사유는 로그에 없다")
	}

	@Test
	fun `가입 중 그만둔(닉네임이 없는) 계정은 확인 닉네임을 비워서 지운다`() {
		val (id, _) = user(null)
		fun delete(body: Map<String, Any?>) = http(port, "DELETE", "/admin/users/$id", json(body), token)

		assertError(400, "CONFIRM_MISMATCH", delete(mapOf("reason" to "메일로 요청", "confirmNickname" to "누군가")), "닉네임이 없는데 무언가 적었다")
		assertError(400, "CONFIRM_MISMATCH", delete(mapOf("reason" to "메일로 요청")), "확인 칸이 없다")
		assertEquals(1, jdbc.queryForObject("select count(*) from accounts where id = ?", Int::class.java, id))

		val deleted = delete(mapOf("reason" to "메일로 요청", "confirmNickname" to " "))

		assertEquals(204, deleted.statusCode(), deleted.body())
		assertEquals(0, jdbc.queryForObject("select count(*) from accounts where id = ?", Int::class.java, id))
		assertEquals(
			listOf("USER_DELETED_BY_ADMIN"),
			jdbc.queryForList("select action from audit_logs where target_id = ?", String::class.java, id.toString()),
		)
	}
}
