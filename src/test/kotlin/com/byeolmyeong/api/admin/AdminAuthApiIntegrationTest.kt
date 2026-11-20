package com.byeolmyeong.api.admin

import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val INVALID = """{"error":"INVALID_CREDENTIALS"}"""
private const val UNAUTHORIZED = """{"error":"UNAUTHORIZED"}"""

@AdminIntegrationTest
class AdminAuthApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var accounts: AdminAccountService

	@BeforeEach
	fun clean() = resetAdminData(jdbc)

	private fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

	// ── 첫 관리자 ──

	@Test
	fun `관리자가 없고 두 환경 변수가 있으면 뜰 때 첫 관리자를 만든다`() {
		val row = jdbc.queryForMap("select * from admin_accounts")
		assertEquals(ROOT_ID, row["login_id"])
		assertEquals(ROOT_ID, row["name"], "표시 이름은 아이디와 같다")
		assertEquals(true, row["active"])
		val hash = row["password_hash"] as String
		assertTrue(hash.startsWith("$2"), "bcrypt 해시로 저장한다")
		assertFalse(hash.contains(ROOT_PASSWORD))
	}

	@Test
	fun `관리자가 한 명이라도 있으면 환경 변수는 읽지 않는다`() {
		assertFalse(accounts.bootstrap("second-admin", "second-admin-password"))
		assertFalse(accounts.bootstrap(ROOT_ID, "another-password-123"), "같은 아이디여도 비밀번호를 덮어쓰지 않는다")

		assertEquals(1, count("select count(*) from admin_accounts"))
		assertEquals(401, login(port, "second-admin", "second-admin-password").statusCode())
		assertEquals(401, login(port, ROOT_ID, "another-password-123").statusCode())
		assertEquals(200, login(port, ROOT_ID, ROOT_PASSWORD).statusCode())
	}

	// ── 로그인 ──

	@Test
	fun `로그인하면 토큰과 끝나는 때와 관리자를 준다`() {
		val before = Instant.now()
		val response = login(port, ROOT_ID, ROOT_PASSWORD)

		assertEquals(200, response.statusCode())
		val json = response.json()
		assertEquals(setOf("token", "expiresAt", "admin"), json.propertyNames().toSet())
		assertEquals(setOf("id", "loginId", "name", "mustChangePassword"), json["admin"].propertyNames().toSet(), "비밀번호·해시는 응답에 없다")
		assertEquals(ROOT_ID, json["admin"]["loginId"].asString())
		assertEquals(jdbc.queryForObject("select id from admin_accounts", UUID::class.java).toString(), json["admin"]["id"].asString())

		val token = json["token"].asString()
		assertTrue(token.length >= 43, "무작위 32바이트")
		val session = jdbc.queryForMap("select * from admin_sessions")
		assertEquals(sha256(token), session["token_hash"], "DB 에는 토큰의 해시만 둔다")

		val expiresAt = Instant.parse(json["expiresAt"].asString())
		val twelveHours = Duration.between(before, expiresAt)
		assertTrue(twelveHours in Duration.ofHours(12).minusSeconds(5)..Duration.ofHours(12).plusSeconds(60), "로그인한 때부터 12시간: $twelveHours")
		assertTrue(json["expiresAt"].asString().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))
	}

	@Test
	fun `아이디는 대소문자와 앞뒤 공백을 가리지 않는다`() {
		assertEquals(200, login(port, " Root-Admin ", ROOT_PASSWORD).statusCode())
		assertEquals(401, login(port, ROOT_ID, " $ROOT_PASSWORD").statusCode(), "비밀번호는 글자 그대로 본다")
		assertEquals(401, login(port, ROOT_ID, ROOT_PASSWORD.uppercase()).statusCode())
	}

	@Test
	fun `없는 아이디와 틀린 비밀번호는 같은 답이다`() {
		val unknown = login(port, "no-such-admin", ROOT_PASSWORD)
		val wrong = login(port, ROOT_ID, "wrong-password-123")

		assertEquals(401, unknown.statusCode())
		assertEquals(401, wrong.statusCode())
		assertEquals(INVALID, unknown.body())
		assertEquals(INVALID, wrong.body())
		for (password in listOf("", "short", "가".repeat(100), "x".repeat(5000))) {
			val response = login(port, ROOT_ID, password)
			assertEquals(401, response.statusCode(), "길이 ${password.length}")
			assertEquals(INVALID, response.body())
		}
		assertEquals(401, login(port, "", ROOT_PASSWORD).statusCode())
		assertEquals(401, login(port, "x".repeat(5000), ROOT_PASSWORD).statusCode())
		assertEquals(0, count("select count(*) from admin_sessions"))
	}

	@Test
	fun `로그인 본문의 타입이 틀리면 500 이 아니라 400 이다`() {
		val bad = listOf(
			null, "", "{", "null", "[]", "\"$ROOT_ID\"", "1",
			"""{}""",
			"""{"loginId":"$ROOT_ID"}""",
			"""{"password":"$ROOT_PASSWORD"}""",
			"""{"loginId":null,"password":"$ROOT_PASSWORD"}""",
			"""{"loginId":1,"password":"$ROOT_PASSWORD"}""",
			"""{"loginId":["$ROOT_ID"],"password":"$ROOT_PASSWORD"}""",
			"""{"loginId":{"v":"$ROOT_ID"},"password":"$ROOT_PASSWORD"}""",
			"""{"loginId":"$ROOT_ID","password":12345678901}""",
			"""{"loginId":"$ROOT_ID","password":["$ROOT_PASSWORD"]}""",
			"""{"loginId":"$ROOT_ID","password":true}""",
			"""{"loginId":"$ROOT_ID","password":null}""",
		)
		for (body in bad) {
			val response = http(port, "POST", "/admin/auth/login", body)
			assertEquals(400, response.statusCode(), "본문: $body")
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body())
		}
		assertEquals(0, count("select count(*) from admin_sessions"))
	}

	// ── 세션 검사 ──

	@Test
	fun `관리자 세션이 없으면 admin 아래의 모든 경로가 401 이다`() {
		val paths = listOf(
			"GET" to "/admin", "GET" to "/admin/", "GET" to "/admin/me", "POST" to "/admin/auth/logout", "PUT" to "/admin/me/password",
			"GET" to "/admin/admins", "POST" to "/admin/admins", "PATCH" to "/admin/admins/${UUID.randomUUID()}",
			"GET" to "/admin/inquiries", "GET" to "/admin/inquiries/${UUID.randomUUID()}", "PATCH" to "/admin/inquiries/${UUID.randomUUID()}",
			"GET" to "/admin/stats",
			"GET" to "/admin/faqs", "POST" to "/admin/faqs", "PATCH" to "/admin/faqs/${UUID.randomUUID()}",
			"DELETE" to "/admin/faqs/${UUID.randomUUID()}", "PUT" to "/admin/faqs/order",
			"GET" to "/admin/no-such-path", "GET" to "/admin/auth/login/", "GET" to "/admin/auth/login/x", "GET" to "/admin/auth",
		)
		for ((method, path) in paths) {
			for (token in listOf(null, "no-such-token", "")) {
				val response = http(port, method, path, if (method == "GET" || method == "DELETE") null else "{}", token)
				assertEquals(401, response.statusCode(), "$method $path (token=$token)")
				assertEquals(UNAUTHORIZED, response.body())
			}
		}
	}

	@Test
	fun `앱 사용자의 토큰은 admin 에서 통하지 않는다`() {
		val userToken = devToken(port)
		assertEquals(404, http(port, "GET", "/profile", token = userToken).statusCode(), "사용자 토큰 자체는 살아 있다(프로필이 없어 404)")

		for ((method, path) in listOf("GET" to "/admin/me", "GET" to "/admin/admins", "GET" to "/admin/inquiries", "GET" to "/admin/stats", "GET" to "/admin/faqs", "POST" to "/admin/auth/logout")) {
			val response = http(port, method, path, token = userToken)
			assertEquals(401, response.statusCode(), "$method $path")
			assertEquals(UNAUTHORIZED, response.body())
		}
		assertEquals(404, http(port, "GET", "/profile", token = userToken).statusCode(), "관리자 로그아웃 경로가 사용자 세션을 지우지 않는다")
	}

	@Test
	fun `관리자의 토큰은 앱 사용자의 경로에서 통하지 않는다`() {
		devToken(port)
		val adminToken = adminToken(port)
		assertEquals(200, http(port, "GET", "/admin/me", token = adminToken).statusCode())

		val calls = listOf(
			Triple("GET", "/profile", null),
			Triple("POST", "/charts", """{"date":"1990-01-01","time":null}"""),
			Triple("POST", "/readings", null),
			Triple("POST", "/reports", null),
			Triple("GET", "/reports/me", null),
			Triple("GET", "/synastry", null),
			Triple("POST", "/directions", """{"topic":"LOVE"}"""),
			Triple("POST", "/inquiries", """{"category":"BUG","body":"관리자 토큰으로 보낸 문의입니다."}"""),
			Triple("DELETE", "/account", null),
		)
		for ((method, path, body) in calls) {
			val response = http(port, method, path, body, adminToken)
			assertEquals(401, response.statusCode(), "$method $path")
			assertEquals(UNAUTHORIZED, response.body())
		}
		assertEquals(1, count("select count(*) from accounts"), "계정 삭제가 실행되지 않았다")
		assertEquals(0, count("select count(*) from inquiries"))
	}

	@Test
	fun `me 는 로그인한 관리자를 준다`() {
		val response = http(port, "GET", "/admin/me", token = adminToken(port))
		assertEquals(200, response.statusCode())
		assertEquals(setOf("id", "loginId", "name", "mustChangePassword"), response.json().propertyNames().toSet())
		assertEquals(ROOT_ID, response.json()["loginId"].asString())
	}

	@Test
	fun `끝나는 때가 지난 세션은 401 이고 다음 로그인 때 지워진다`() {
		val token = adminToken(port)
		assertEquals(200, http(port, "GET", "/admin/me", token = token).statusCode())

		jdbc.update("update admin_sessions set expires_at = now() - interval '1 second'")
		val expired = http(port, "GET", "/admin/me", token = token)
		assertEquals(401, expired.statusCode())
		assertEquals(UNAUTHORIZED, expired.body())

		val fresh = adminToken(port)
		assertEquals(listOf(sha256(fresh)), jdbc.queryForList("select token_hash from admin_sessions", String::class.java))
	}

	@Test
	fun `세션은 쓰는 동안 늘어나지 않는다`() {
		val token = adminToken(port)
		val before = jdbc.queryForObject("select expires_at from admin_sessions", OffsetDateTime::class.java)
		repeat(3) { assertEquals(200, http(port, "GET", "/admin/me", token = token).statusCode()) }
		assertEquals(before, jdbc.queryForObject("select expires_at from admin_sessions", OffsetDateTime::class.java))
	}

	@Test
	fun `로그아웃하면 그 세션만 지워진다`() {
		val first = adminToken(port)
		val second = adminToken(port)

		val response = http(port, "POST", "/admin/auth/logout", token = first)
		assertEquals(204, response.statusCode())
		assertEquals("", response.body())

		assertEquals(401, http(port, "GET", "/admin/me", token = first).statusCode())
		assertEquals(401, http(port, "POST", "/admin/auth/logout", token = first).statusCode())
		assertEquals(200, http(port, "GET", "/admin/me", token = second).statusCode())
		assertEquals(listOf(sha256(second)), jdbc.queryForList("select token_hash from admin_sessions", String::class.java))
	}

	// ── 관리자 관리 ──

	@Test
	fun `관리자 목록은 먼저 만든 관리자부터이고 해시는 없다`() {
		val token = adminToken(port)
		createAdmin(port, token, "ops.kim-2", "ops-kim-password", jdbc)

		val list = http(port, "GET", "/admin/admins", token = token)
		assertEquals(200, list.statusCode())
		assertEquals(listOf(ROOT_ID, "ops.kim-2"), list.json().toList().map { it["loginId"].asString() }, "먼저 만든 관리자부터")
		assertEquals(setOf("id", "loginId", "name", "active", "createdAt", "lastLoginAt"), list.json()[0].propertyNames().toSet())
		assertFalse(list.body().contains("$2"), "해시는 응답에 없다")

		val theirs = adminToken(port, "OPS.kim-2", "ops-kim-password")
		assertEquals("ops.kim-2", http(port, "GET", "/admin/me", token = theirs).json()["name"].asString(), "이름은 아이디 그대로다")
	}

	@Test
	fun `이미 있는 아이디로는 만들 수 없다`() {
		val token = adminToken(port)
		createAdmin(port, token, "ops-kim", "ops-kim-password", jdbc)

		for (id in listOf("ops-kim", "OPS-KIM", ROOT_ID)) {
			val response = http(port, "POST", "/admin/admins", json(mapOf("loginId" to id, "name" to "다른 이름", "password" to "another-password")), token)
			assertEquals(409, response.statusCode(), id)
			assertEquals("""{"error":"LOGIN_ID_TAKEN"}""", response.body())
		}
		assertEquals(2, count("select count(*) from admin_accounts"))
		assertEquals(401, login(port, "ops-kim", "another-password").statusCode(), "비밀번호를 덮어쓰지 않는다")
	}

	@Test
	fun `관리자를 끄면 그 관리자의 세션이 지워지고 로그인도 안 된다 다시 켜면 된다`() {
		val root = adminToken(port)
		val id = createAdmin(port, root, "ops-kim", "ops-kim-password", jdbc)
		val theirs = adminToken(port, "ops-kim", "ops-kim-password")
		adminToken(port, "ops-kim", "ops-kim-password")
		assertEquals(200, http(port, "GET", "/admin/me", token = theirs).statusCode())

		val off = http(port, "PATCH", "/admin/admins/$id", """{"active":false}""", root)
		assertEquals(200, off.statusCode())
		assertEquals(false, off.json()["active"].asBoolean())
		assertEquals("ops-kim", off.json()["loginId"].asString())

		assertEquals(401, http(port, "GET", "/admin/me", token = theirs).statusCode(), "꺼진 관리자의 토큰은 통하지 않는다")
		assertEquals(0, count("select count(*) from admin_sessions where admin_id = ?", id), "세션을 모두 지운다")
		val denied = login(port, "ops-kim", "ops-kim-password")
		assertEquals(401, denied.statusCode())
		assertEquals(INVALID, denied.body(), "꺼진 계정도 없는 아이디와 같은 답이다")
		assertEquals(200, http(port, "GET", "/admin/me", token = root).statusCode(), "다른 관리자의 세션은 그대로다")

		assertEquals(200, http(port, "PATCH", "/admin/admins/$id", """{"active":false}""", root).statusCode(), "이미 꺼진 관리자를 또 꺼도 된다")

		val on = http(port, "PATCH", "/admin/admins/$id", """{"active":true}""", root)
		assertEquals(200, on.statusCode())
		assertEquals(true, on.json()["active"].asBoolean())
		assertEquals(401, http(port, "GET", "/admin/me", token = theirs).statusCode(), "예전 토큰은 되살아나지 않는다")
		assertEquals(200, login(port, "ops-kim", "ops-kim-password").statusCode())
	}

	@Test
	fun `꺼진 관리자의 세션이 DB 에 남아 있어도 통하지 않는다`() {
		val root = adminToken(port)
		val id = createAdmin(port, root, "ops-kim", "ops-kim-password", jdbc)
		val theirs = adminToken(port, "ops-kim", "ops-kim-password")

		jdbc.update("update admin_accounts set active = false where id = ?", id)

		assertEquals(1, count("select count(*) from admin_sessions where admin_id = ?", id))
		assertEquals(401, http(port, "GET", "/admin/me", token = theirs).statusCode())
	}

	@Test
	fun `자기 자신은 끌 수 없다`() {
		val root = adminToken(port)
		val rootId = http(port, "GET", "/admin/me", token = root).json()["id"].asString()
		createAdmin(port, root, "ops-kim", "ops-kim-password", jdbc)

		val response = http(port, "PATCH", "/admin/admins/$rootId", """{"active":false}""", root)
		assertEquals(409, response.statusCode())
		assertEquals("""{"error":"CANNOT_DEACTIVATE_SELF"}""", response.body())
		assertEquals(2, count("select count(*) from admin_accounts where active"))
		assertEquals(200, http(port, "GET", "/admin/me", token = root).statusCode())
		assertEquals(200, http(port, "PATCH", "/admin/admins/$rootId", """{"active":true}""", root).statusCode(), "자기를 켜는 것은 아무 일도 아니다")
	}

	@Test
	fun `마지막 남은 활성 관리자는 끌 수 없다`() {
		val root = adminToken(port)
		val rootId = UUID.fromString(http(port, "GET", "/admin/me", token = root).json()["id"].asString())
		val otherId = createAdmin(port, root, "ops-kim", "ops-kim-password", jdbc)
		// 두 관리자가 동시에 서로를 끄는 경우: 한쪽이 먼저 꺼진 뒤에 다른 쪽의 요청이 닿는다.
		jdbc.update("update admin_accounts set active = false where id = ?", otherId)

		val error = assertFailsWith<AdminRejectedException> { accounts.setActive(otherId, rootId, false) }
		assertEquals(409, error.status)
		assertEquals("LAST_ACTIVE_ADMIN", error.code)
		assertEquals(listOf(rootId), jdbc.queryForList("select id from admin_accounts where active", UUID::class.java))
		assertEquals(200, http(port, "GET", "/admin/me", token = root).statusCode())
	}

	@Test
	fun `없는 관리자와 틀린 본문`() {
		val root = adminToken(port)
		val id = createAdmin(port, root, "ops-kim", "ops-kim-password", jdbc)

		for (path in listOf("/admin/admins/${UUID.randomUUID()}", "/admin/admins/not-a-uuid", "/admin/admins/1")) {
			val response = http(port, "PATCH", path, """{"active":false}""", root)
			assertEquals(404, response.statusCode(), path)
			assertEquals("""{"error":"NOT_FOUND"}""", response.body())
		}
		for (body in listOf(null, "{}", "[]", "null", """{"active":"false"}""", """{"active":0}""", """{"active":null}""", """{"active":[false]}""")) {
			val response = http(port, "PATCH", "/admin/admins/$id", body, root)
			assertEquals(400, response.statusCode(), "본문: $body")
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body())
		}
		assertEquals(2, count("select count(*) from admin_accounts where active"))
	}

	// ── 내 비밀번호 ──

	@Test
	fun `내 비밀번호를 바꾸면 예전 비밀번호와 내 다른 세션은 끝난다`() {
		val root = adminToken(port)
		createAdmin(port, root, "ops-kim", "ops-kim-password", jdbc)
		val current = adminToken(port, "ops-kim", "ops-kim-password")
		val other = adminToken(port, "ops-kim", "ops-kim-password")
		fun change(currentPassword: Any?, newPassword: Any?, token: String = current) =
			http(port, "PUT", "/admin/me/password", json(mapOf("currentPassword" to currentPassword, "newPassword" to newPassword)), token)

		val wrong = change("wrong-password-123", "brand-new-password")
		assertEquals(400, wrong.statusCode())
		assertEquals("""{"error":"INVALID_CURRENT_PASSWORD"}""", wrong.body())
		for (bad in listOf("short", "x".repeat(73))) {
			val response = change("ops-kim-password", bad)
			assertEquals(400, response.statusCode())
			assertEquals("""{"error":"INVALID_PASSWORD"}""", response.body())
		}
		for (body in listOf(null, "{}", "[]", """{"currentPassword":1,"newPassword":"brand-new-password"}""", """{"currentPassword":"ops-kim-password","newPassword":null}""")) {
			val response = http(port, "PUT", "/admin/me/password", body, current)
			assertEquals(400, response.statusCode(), "본문: $body")
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body())
		}
		assertEquals(200, login(port, "ops-kim", "ops-kim-password").statusCode(), "거절된 요청은 아무것도 바꾸지 않는다")
		jdbc.update("delete from admin_sessions where token_hash not in (?, ?, ?)", sha256(root), sha256(current), sha256(other))

		val changed = change("ops-kim-password", "brand-new-password")
		assertEquals(204, changed.statusCode())
		assertEquals("", changed.body())

		assertEquals(401, login(port, "ops-kim", "ops-kim-password").statusCode())
		assertEquals(200, login(port, "ops-kim", "brand-new-password").statusCode())
		assertEquals(200, http(port, "GET", "/admin/me", token = current).statusCode(), "바꾼 세션은 그대로다")
		assertEquals(401, http(port, "GET", "/admin/me", token = other).statusCode(), "내 다른 세션은 끝난다")
		assertEquals(200, http(port, "GET", "/admin/me", token = root).statusCode(), "다른 관리자의 세션은 그대로다")
		assertEquals(200, login(port, ROOT_ID, ROOT_PASSWORD).statusCode(), "다른 관리자의 비밀번호는 그대로다")
	}

	// ── 로그·응답 ──

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `비밀번호와 토큰과 해시는 로그에도 응답에도 없다`(output: CapturedOutput) {
		val secretPassword = "never-log-this-password"
		val secretNew = "never-log-this-either"
		val secretWrong = "never-log-wrong-guess"
		val root = adminToken(port)

		createAdmin(port, root, "ops-kim", secretPassword, jdbc)
		val responses = mutableListOf(
			http(port, "POST", "/admin/admins", json(mapOf("loginId" to "ops-kim", "password" to secretPassword)), root), // 409
			http(port, "POST", "/admin/admins", json(mapOf("loginId" to "bad id", "password" to secretPassword)), root), // 400
			http(port, "POST", "/admin/admins", """{"loginId":["ops-lee"],"password":"$secretPassword"}""", root), // 타입
			http(port, "POST", "/admin/admins", """{"loginId":"ops-lee","password":"$secretPassword"""", root), // 깨진 JSON
			login(port, "ops-kim", secretWrong),
			login(port, "no-such-admin", secretWrong),
			http(port, "POST", "/admin/auth/login", """{"loginId":"ops-kim","password":["$secretWrong"]}"""),
		)
		val loggedIn = login(port, "ops-kim", secretPassword)
		val token = loggedIn.json()["token"].asString()
		responses += http(port, "GET", "/admin/me", token = token)
		responses += http(port, "PUT", "/admin/me/password", json(mapOf("currentPassword" to secretWrong, "newPassword" to secretNew)), token)
		responses += http(port, "PUT", "/admin/me/password", json(mapOf("currentPassword" to secretPassword, "newPassword" to secretNew)), token)
		responses += http(port, "GET", "/admin/admins", token = token)
		responses += http(port, "GET", "/admin/me", token = "$token-wrong")
		responses += http(port, "POST", "/admin/auth/logout", token = token)
		accounts.bootstrap("boot-admin", secretPassword)
		assertEquals(listOf(409, 400, 400, 400, 401, 401, 400, 200, 400, 204, 200, 401, 204), responses.map { it.statusCode() })

		val hashes = jdbc.queryForList("select password_hash from admin_accounts", String::class.java).filterNotNull()
		val answered = responses.joinToString("\n") { it.body() }
		val secrets = listOf(secretPassword, secretNew, secretWrong, ROOT_PASSWORD, token, root, sha256(token), sha256(root)) + hashes
		for (secret in secrets) {
			assertFalse(output.all.contains(secret), "로그에 비밀값이 있다: ${secret.take(6)}…")
			assertFalse(answered.contains(secret), "응답에 비밀값이 있다: ${secret.take(6)}…")
		}
		assertFalse(output.all.contains("ops-kim"), "로그에 아이디를 남기지 않는다")
		assertTrue(output.all.contains("event=admin_login_succeeded"))
		assertTrue(output.all.contains("event=admin_login_failed"))
		assertNotEquals(root, token)
	}
}
