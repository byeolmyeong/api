package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val OTHER_ID = "forgetful-admin"
private const val OLD_PASSWORD = "forgotten-old-password"
private const val NEW_PASSWORD = "reset-new-password-1"

/** 다른 관리자의 비밀번호 다시 정하기 `PUT /admin/admins/{id}/password`(결정 018). */
@ExtendWith(OutputCaptureExtension::class)
@AdminIntegrationTest
class AdminPasswordResetApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var token: String
	private lateinit var rootId: String
	private lateinit var other: UUID

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		token = adminToken(port)
		rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)!!
		other = createAdmin(port, token, OTHER_ID, OLD_PASSWORD)
		jdbc.update("delete from audit_logs")
	}

	private fun reset(id: Any, body: Any? = mapOf("password" to NEW_PASSWORD), token: String? = this.token): HttpResponse<String> =
		http(port, "PUT", "/admin/admins/$id/password", if (body is String) body else json(body), token)

	private fun assertError(status: Int, code: String, response: HttpResponse<String>, what: String = "") {
		assertEquals(status, response.statusCode(), what)
		assertEquals("""{"error":"$code"}""", response.body(), what)
	}

	private fun me(token: String): Int = http(port, "GET", "/admin/me", token = token).statusCode()

	@Test
	fun `다시 정하면 새 비밀번호로만 로그인되고 그 관리자의 세션이 모두 끝난다`() {
		val first = adminToken(port, OTHER_ID, OLD_PASSWORD)
		val second = adminToken(port, OTHER_ID, OLD_PASSWORD)

		val response = reset(other)
		assertEquals(204, response.statusCode(), response.body())
		assertEquals("", response.body())

		assertEquals(listOf(401, 401), listOf(me(first), me(second)), "그 관리자의 세션은 모두 끝난다")
		assertEquals(200, me(token), "다시 정해 준 관리자의 세션은 그대로다")
		assertEquals(401, login(port, OTHER_ID, OLD_PASSWORD).statusCode())
		assertEquals(200, login(port, OTHER_ID, NEW_PASSWORD).statusCode())
		assertEquals(200, login(port, ROOT_ID, ROOT_PASSWORD).statusCode(), "다른 관리자의 비밀번호는 그대로다")
		assertEquals(true, jdbc.queryForObject("select active from admin_accounts where id = ?", Boolean::class.java, other))
	}

	@Test
	fun `자기 자신에게는 쓸 수 없다`() {
		assertError(409, "USE_OWN_PASSWORD_CHANGE", reset(rootId))
		assertEquals(200, me(token))
		assertEquals(200, login(port, ROOT_ID, ROOT_PASSWORD).statusCode())
		assertEquals(401, login(port, ROOT_ID, NEW_PASSWORD).statusCode())
	}

	@Test
	fun `꺼진 관리자에게도 되지만 켜지지는 않는다`() {
		assertEquals(200, http(port, "PATCH", "/admin/admins/$other", """{"active":false}""", token).statusCode())

		assertEquals(204, reset(other).statusCode())
		assertEquals(false, jdbc.queryForObject("select active from admin_accounts where id = ?", Boolean::class.java, other))
		assertEquals(401, login(port, OTHER_ID, NEW_PASSWORD).statusCode(), "꺼진 채다")

		assertEquals(200, http(port, "PATCH", "/admin/admins/$other", """{"active":true}""", token).statusCode())
		assertEquals(200, login(port, OTHER_ID, NEW_PASSWORD).statusCode())
		assertEquals(401, login(port, OTHER_ID, OLD_PASSWORD).statusCode())
	}

	@Test
	fun `틀린 요청은 거절하고 아무것도 바꾸지 않는다`() {
		val session = adminToken(port, OTHER_ID, OLD_PASSWORD)
		jdbc.update("delete from audit_logs")

		for (bad in listOf("short-pw1", "", "가".repeat(25), "x".repeat(73))) assertError(400, "INVALID_PASSWORD", reset(other, mapOf("password" to bad)), "password=${bad.take(8)}")
		assertError(400, "INVALID_PASSWORD", reset(other, mapOf("password" to null)))
		assertError(400, "INVALID_PASSWORD", reset(other, mapOf<String, Any?>()))
		for (bad in listOf(1234567890, true, listOf(NEW_PASSWORD), mapOf("p" to NEW_PASSWORD))) assertError(400, "INVALID_REQUEST", reset(other, mapOf("password" to bad)), "password=$bad")
		for (raw in listOf("[]", "\"$NEW_PASSWORD\"", "{", "")) assertError(400, "INVALID_REQUEST", reset(other, raw), raw)

		assertError(404, "NOT_FOUND", reset(UUID.randomUUID()))
		assertError(404, "NOT_FOUND", reset("not-a-uuid"))
		assertError(404, "NOT_FOUND", reset(OTHER_ID), "아이디로는 찾지 않는다")
		// 자기 자신이어도 틀린 비밀번호가 먼저 걸린다.
		assertError(400, "INVALID_PASSWORD", reset(rootId, mapOf("password" to "short")))

		val (_, userToken) = userAccount(jdbc, "reset-user")
		for (bad in listOf(null, userToken, "no-such-token")) assertError(401, "UNAUTHORIZED", reset(other, token = bad))

		assertEquals(200, me(session), "거절된 요청은 세션을 끝내지 않는다")
		assertEquals(200, login(port, OTHER_ID, OLD_PASSWORD).statusCode())
		jdbc.update("delete from audit_logs where action = 'ADMIN_LOGIN'")
		assertEquals(0, jdbc.queryForObject("select count(*) from audit_logs", Int::class.java))
	}

	@Test
	fun `감사 기록에 누가 누구의 것을 다시 정했는지만 남고 비밀번호는 어디에도 없다`(output: CapturedOutput) {
		assertEquals(204, reset(other).statusCode())

		val row = jdbc.queryForList("select actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail::text as detail from audit_logs").single()
		assertEquals(
			listOf("ADMIN", rootId, ROOT_ID, "ADMIN_PASSWORD_RESET", "ADMIN", other.toString(), "SUCCESS"),
			listOf("actor_type", "actor_id", "actor_label", "action", "target_type", "target_id", "outcome").map { row[it] },
		)
		assertNull(row["detail"])
		val dump = jdbc.queryForList("select row_to_json(a)::text from audit_logs a", String::class.java).joinToString("\n")
		assertFalse(NEW_PASSWORD in dump || OLD_PASSWORD in dump)
		assertFalse(NEW_PASSWORD in output.all, "로그에 비밀번호가 없다")
		assertEquals(true, "event=admin_password_reset" in output.out)
		assertFalse(NEW_PASSWORD in jdbc.queryForObject("select password_hash from admin_accounts where id = ?", String::class.java, other)!!)
	}
}
