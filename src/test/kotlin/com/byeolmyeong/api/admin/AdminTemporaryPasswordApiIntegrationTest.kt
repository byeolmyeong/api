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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val NEW_ID = "ops-park"
private const val CHOSEN = "chosen-password-1"
private val TEMPORARY = Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}")
private const val CHANGE_REQUIRED = """{"error":"PASSWORD_CHANGE_REQUIRED"}"""

/** 임시 비밀번호로 관리자 추가 · 재발급 · 첫 로그인 변경 · 마지막 로그인(결정 043 의 5). */
@ExtendWith(OutputCaptureExtension::class)
@AdminIntegrationTest
class AdminTemporaryPasswordApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var root: String
	private lateinit var rootId: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		root = adminToken(port)
		rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)!!
		jdbc.update("delete from audit_logs")
	}

	private fun add(loginId: Any? = NEW_ID): HttpResponse<String> = http(port, "POST", "/admin/admins", json(mapOf("loginId" to loginId)), root)

	private fun reissue(id: Any, token: String = root): HttpResponse<String> = http(port, "POST", "/admin/admins/$id/password-reset", token = token)

	private fun change(token: String, current: String, new: String): HttpResponse<String> =
		http(port, "PUT", "/admin/me/password", json(mapOf("currentPassword" to current, "newPassword" to new)), token)

	private fun assertError(status: Int, code: String, response: HttpResponse<String>, what: String = "") {
		assertEquals(status, response.statusCode(), what)
		assertEquals("""{"error":"$code"}""", response.body(), what)
	}

	@Test
	fun `아이디만 받아 만들고 임시 비밀번호는 만든 응답에서 한 번만 보인다`() {
		val created = add(" Ops.Park ")

		assertEquals(201, created.statusCode(), created.body())
		val body = created.json()
		assertEquals(setOf("admin", "temporaryPassword"), body.propertyNames().toSet())
		val temporary = body["temporaryPassword"].asString()
		assertTrue(TEMPORARY.matches(temporary), "xxxx-xxxx-xxxx 꼴이 아니다")
		val admin = body["admin"]
		assertEquals(setOf("id", "loginId", "name", "active", "createdAt", "lastLoginAt"), admin.propertyNames().toSet())
		assertEquals(listOf("ops.park", "ops.park", "true", "null"), listOf(admin["loginId"].asString(), admin["name"].asString(), admin["active"].toString(), admin["lastLoginAt"].toString()), "이름은 아이디 그대로")

		val list = http(port, "GET", "/admin/admins", token = root)
		assertFalse(temporary in list.body(), "목록에는 임시 비밀번호가 없다")
		assertNotEquals(temporary, add("ops-choi").json()["temporaryPassword"].asString(), "사람마다 다르다")
		val hash = jdbc.queryForObject("select password_hash from admin_accounts where login_id = 'ops.park'", String::class.java)!!
		assertTrue(hash.startsWith("$2") && temporary !in hash)
	}

	@Test
	fun `이름과 비밀번호를 보내도 읽지 않는다`() {
		val created = http(port, "POST", "/admin/admins", json(mapOf("loginId" to NEW_ID, "name" to "운영 박", "password" to CHOSEN)), root)

		assertEquals(201, created.statusCode())
		assertEquals(NEW_ID, created.json()["admin"]["name"].asString())
		assertEquals(401, login(port, NEW_ID, CHOSEN).statusCode(), "보낸 비밀번호로는 들어갈 수 없다")
	}

	@Test
	fun `아이디를 검증하고 틀리면 아무도 만들지 않는다`() {
		for (id in listOf(null, "", "ab", "a".repeat(33), "has space", "한글아이디", "-dash", "a@b")) assertError(400, "INVALID_LOGIN_ID", add(id), "$id")
		for (raw in listOf("[]", "null", "{", """{"loginId":1}""", """{"loginId":["ops-park"]}""")) {
			assertError(400, "INVALID_REQUEST", http(port, "POST", "/admin/admins", raw, root), raw)
		}
		assertEquals(201, add().statusCode())
		assertError(409, "LOGIN_ID_TAKEN", add("OPS-PARK"))
		assertError(409, "LOGIN_ID_TAKEN", add(ROOT_ID))
		assertEquals(2, jdbc.queryForObject("select count(*) from admin_accounts", Int::class.java))
	}

	@Test
	fun `임시 비밀번호로 들어오면 바꾸기 전에는 바꾸기 · 로그아웃 · 나 말고 모두 403 이다`() {
		val temporary = add().json()["temporaryPassword"].asString()

		val loggedIn = login(port, NEW_ID, temporary)
		assertEquals(200, loggedIn.statusCode())
		assertEquals(true, loggedIn.json()["admin"]["mustChangePassword"].asBoolean())
		val token = loggedIn.json()["token"].asString()

		val me = http(port, "GET", "/admin/me", token = token)
		assertEquals(200, me.statusCode())
		assertEquals(setOf("id", "loginId", "name", "mustChangePassword"), me.json().propertyNames().toSet())
		assertEquals(true, me.json()["mustChangePassword"].asBoolean())
		for ((method, path) in listOf("GET" to "/admin/inquiries", "GET" to "/admin/admins", "GET" to "/admin/stats", "POST" to "/admin/faqs", "POST" to "/admin/admins", "GET" to "/admin/anything")) {
			val response = http(port, method, path, "{}", token)
			assertEquals(403, response.statusCode(), "$method $path")
			assertEquals(CHANGE_REQUIRED, response.body(), "$method $path")
		}

		assertError(400, "SAME_AS_TEMPORARY", change(token, temporary, temporary))
		assertError(400, "INVALID_CURRENT_PASSWORD", change(token, "wrong-temporary", CHOSEN))
		assertEquals(403, http(port, "GET", "/admin/inquiries", token = token).statusCode(), "거절된 변경은 풀지 않는다")

		assertEquals(204, change(token, temporary, CHOSEN).statusCode())
		assertEquals(200, http(port, "GET", "/admin/inquiries", token = token).statusCode(), "바꾸면 풀린다")
		assertEquals(false, http(port, "GET", "/admin/me", token = token).json()["mustChangePassword"].asBoolean())
		assertEquals(401, login(port, NEW_ID, temporary).statusCode())
		assertEquals(false, login(port, NEW_ID, CHOSEN).json()["admin"]["mustChangePassword"].asBoolean())
		assertEquals(false, login(port, ROOT_ID, ROOT_PASSWORD).json()["admin"]["mustChangePassword"].asBoolean(), "첫 관리자는 환경 변수로 만들어 바꾸라고 하지 않는다")
	}

	@Test
	fun `바꾸기 전에도 로그아웃은 된다`() {
		val temporary = add().json()["temporaryPassword"].asString()
		val token = adminToken(port, NEW_ID, temporary)

		assertEquals(204, http(port, "POST", "/admin/auth/logout", token = token).statusCode())
		assertEquals(401, http(port, "GET", "/admin/me", token = token).statusCode())
	}

	@Test
	fun `재발급하면 옛 비밀번호와 그 관리자의 세션이 모두 끝나고 새 임시 비밀번호로 다시 바꾸게 한다`() {
		val id = createAdmin(port, root, NEW_ID, CHOSEN, jdbc)
		val first = adminToken(port, NEW_ID, CHOSEN)
		val second = adminToken(port, NEW_ID, CHOSEN)
		jdbc.update("delete from audit_logs")

		val response = reissue(id)
		assertEquals(200, response.statusCode(), response.body())
		assertEquals(setOf("temporaryPassword"), response.json().propertyNames().toSet())
		val temporary = response.json()["temporaryPassword"].asString()
		assertTrue(TEMPORARY.matches(temporary))

		assertEquals(listOf(401, 401), listOf(first, second).map { http(port, "GET", "/admin/me", token = it).statusCode() })
		assertEquals(200, http(port, "GET", "/admin/me", token = root).statusCode(), "재발급한 관리자의 세션은 그대로다")
		assertEquals(401, login(port, NEW_ID, CHOSEN).statusCode())
		val again = login(port, NEW_ID, temporary)
		assertEquals(true, again.json()["admin"]["mustChangePassword"].asBoolean())
		assertEquals(403, http(port, "GET", "/admin/inquiries", token = again.json()["token"].asString()).statusCode())

		val audit = jdbc.queryForList("select action, actor_id, target_type, target_id, detail::text as detail from audit_logs where action = 'ADMIN_PASSWORD_REISSUED'")
		assertEquals(listOf(mapOf<String, Any?>("action" to "ADMIN_PASSWORD_REISSUED", "actor_id" to rootId, "target_type" to "ADMIN", "target_id" to id.toString(), "detail" to null)), audit)
	}

	@Test
	fun `꺼진 관리자에게도 재발급되지만 켜지지는 않는다`() {
		val id = createAdmin(port, root, NEW_ID, CHOSEN, jdbc)
		assertEquals(200, http(port, "PATCH", "/admin/admins/$id", """{"active":false}""", root).statusCode())

		val temporary = reissue(id).json()["temporaryPassword"].asString()

		assertEquals(false, jdbc.queryForObject("select active from admin_accounts where id = ?", Boolean::class.java, id))
		assertEquals(401, login(port, NEW_ID, temporary).statusCode(), "꺼진 채다")
		assertEquals(200, http(port, "PATCH", "/admin/admins/$id", """{"active":true}""", root).statusCode())
		assertEquals(true, login(port, NEW_ID, temporary).json()["admin"]["mustChangePassword"].asBoolean())
	}

	@Test
	fun `새 비밀번호 규칙의 경계 — 10자 · 72바이트 · 한글 24자는 되고 그 밖은 400 이다`() {
		for (ok in listOf("1234567890", "x".repeat(72), "가".repeat(24))) {
			val id = add("ops-${ok.length}-${ok.first().code}").json()
			val temporary = id["temporaryPassword"].asString()
			val loginId = id["admin"]["loginId"].asString()
			val token = adminToken(port, loginId, temporary)
			assertEquals(204, change(token, temporary, ok).statusCode(), "${ok.length}자")
			assertEquals(200, login(port, loginId, ok).statusCode())
		}
		val temporary = add().json()["temporaryPassword"].asString()
		val token = adminToken(port, NEW_ID, temporary)
		for (bad in listOf("123456789", "x".repeat(73), "가".repeat(25))) assertError(400, "INVALID_PASSWORD", change(token, temporary, bad), "${bad.length}자")
	}

	@Test
	fun `지금 비밀번호를 여러 번 틀려도 막히지 않는다`() {
		val temporary = add().json()["temporaryPassword"].asString()
		val token = adminToken(port, NEW_ID, temporary)

		repeat(8) { assertError(400, "INVALID_CURRENT_PASSWORD", change(token, "wrong-temporary-$it", CHOSEN), "${it + 1}번째") }
		assertEquals(204, change(token, temporary, CHOSEN).statusCode())
	}

	@Test
	fun `자기 자신 · 없는 관리자에게는 재발급하지 않는다`() {
		assertError(409, "SELF_NOT_ALLOWED", reissue(rootId))
		assertError(404, "NOT_FOUND", reissue(UUID.randomUUID()))
		assertError(404, "NOT_FOUND", reissue("not-a-uuid"))
		assertEquals(200, http(port, "GET", "/admin/me", token = root).statusCode())
		assertEquals(200, login(port, ROOT_ID, ROOT_PASSWORD).statusCode())
		assertEquals(0, jdbc.queryForObject("select count(*) from audit_logs where action = 'ADMIN_PASSWORD_REISSUED'", Int::class.java))
	}

	@Test
	fun `로그인하면 목록에 마지막 로그인이 보인다`() {
		val id = createAdmin(port, root, NEW_ID, CHOSEN, jdbc)
		fun lastLoginOf(): String = http(port, "GET", "/admin/admins", token = root).json().first { it["id"].asString() == id.toString() }["lastLoginAt"].toString()
		assertEquals("null", lastLoginOf())

		assertEquals(401, login(port, NEW_ID, "wrong-password-1").statusCode())
		assertEquals("null", lastLoginOf(), "실패한 로그인은 적지 않는다")

		adminToken(port, NEW_ID, CHOSEN)
		assertTrue(Regex("\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\"").matches(lastLoginOf()))
	}

	@Test
	fun `임시 비밀번호는 로그와 감사 기록에 없다`(output: CapturedOutput) {
		val created = add().json()
		val temporary = created["temporaryPassword"].asString()
		val id = created["admin"]["id"].asString()
		val reissued = reissue(id).json()["temporaryPassword"].asString()
		val token = adminToken(port, NEW_ID, reissued)
		assertEquals(204, change(token, reissued, CHOSEN).statusCode())

		val dump = jdbc.queryForList("select row_to_json(a)::text from audit_logs a", String::class.java).joinToString("\n")
		for (secret in listOf(temporary, reissued, CHOSEN)) {
			assertFalse(secret in output.all, "로그에 비밀번호가 있다")
			assertFalse(secret in dump, "감사 기록에 비밀번호가 있다")
		}
		assertTrue("event=admin_password_reissued" in output.all)
	}
}
