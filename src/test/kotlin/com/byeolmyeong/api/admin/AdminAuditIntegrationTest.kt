package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val SECOND_ID = "second-admin"
private const val SECOND_PASSWORD = "second-admin-password"
private const val MEMO = "메모비밀글-환불해 달라고 하심"
private const val INQUIRY_BODY = "문의비밀글-결제가 두 번 됐어요"
private const val REPLY_EMAIL = "secret-reply@example.com"
private const val QUESTION = "질문비밀글-어떻게 지우나요"
private const val ANSWER = "답비밀글-설정에서 지웁니다"

/** 관리자의 행동이 감사 기록에 한 줄씩 남는다(결정 015). 실제 경로를 부르고 `audit_logs` 를 본다. */
@AdminIntegrationTest
class AdminAuditIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var accounts: AdminAccountService

	private lateinit var token: String
	private lateinit var rootId: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		token = adminToken(port)
		rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)!!
		jdbc.update("delete from audit_logs")
	}

	private fun rows(): List<Map<String, Any?>> = jdbc.queryForList(
		"select actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail::text as detail from audit_logs order by occurred_at, action",
	)

	/** 행이 하나뿐이고 [action] 이며 행위자가 첫 관리자다. */
	private fun single(action: String, outcome: String = "SUCCESS"): Map<String, Any?> {
		val row = rows().single()
		assertEquals(action, row["action"])
		assertEquals(outcome, row["outcome"])
		assertEquals(listOf("ADMIN", rootId, ROOT_ID), listOf(row["actor_type"], row["actor_id"], row["actor_label"]))
		return row
	}

	private fun seedInquiry(): UUID {
		val (owner, _) = userAccount(jdbc, "audit-user")
		val id = UUID.randomUUID()
		jdbc.update("insert into inquiries (id, owner_id, category, body, reply_email) values (?, ?, 'BUG', ?, ?)", id, owner, INQUIRY_BODY, REPLY_EMAIL)
		return id
	}

	private fun createFaq(published: Boolean = false): String {
		val response = http(port, "POST", "/admin/faqs", json(mapOf("question" to QUESTION, "answer" to ANSWER, "published" to published)), token)
		assertEquals(201, response.statusCode())
		return response.json()["id"].asString()
	}

	@Test
	fun `로그인 성공은 그 관리자의 행으로 남는다`() {
		assertEquals(200, login(port, ROOT_ID, ROOT_PASSWORD).statusCode())

		val row = single("ADMIN_LOGIN")
		assertNull(row["target_type"])
		assertNull(row["detail"])
	}

	@Test
	fun `로그인 실패는 누구인지 없이 실패했다는 것만 남는다`() {
		assertEquals(401, login(port, ROOT_ID, "wrong-password-typed").statusCode())
		assertEquals(401, login(port, "no-such-admin", ROOT_PASSWORD).statusCode())

		val rows = rows()
		assertEquals(2, rows.size)
		for (row in rows) {
			assertEquals(listOf("ADMIN_LOGIN", "FAILURE", "SYSTEM"), listOf(row["action"], row["outcome"], row["actor_type"]))
			assertEquals(listOf(null, null, null, null, null), listOf(row["actor_id"], row["actor_label"], row["target_type"], row["target_id"], row["detail"]))
		}
	}

	@Test
	fun `틀린 요청과 제한에 걸린 로그인은 남지 않는다`() {
		assertEquals(400, login(port, 1, 2).statusCode())
		assertEquals(0, rows().size)
	}

	@Test
	fun `로그아웃이 남는다`() {
		assertEquals(204, http(port, "POST", "/admin/auth/logout", token = token).statusCode())
		single("ADMIN_LOGOUT")
	}

	@Test
	fun `비밀번호 변경은 성공만 남고 값은 없다`() {
		val created = createAdmin(port, token, SECOND_ID, SECOND_PASSWORD, jdbc)
		val second = adminToken(port, SECOND_ID, SECOND_PASSWORD)
		jdbc.update("delete from audit_logs")

		val body = mapOf("currentPassword" to "wrong-current-password", "newPassword" to "brand-new-password-1")
		assertEquals(400, http(port, "PUT", "/admin/me/password", json(body), second).statusCode())
		assertEquals(0, rows().size)

		assertEquals(204, http(port, "PUT", "/admin/me/password", json(body + ("currentPassword" to SECOND_PASSWORD)), second).statusCode())
		val row = rows().single()
		assertEquals(listOf("ADMIN_PASSWORD_CHANGED", "SUCCESS", "ADMIN", created.toString(), SECOND_ID), listOf(row["action"], row["outcome"], row["actor_type"], row["actor_id"], row["actor_label"]))
		assertNull(row["detail"])
	}

	@Test
	fun `관리자 만들기·끄기·켜기가 대상과 함께 남는다`() {
		val created = createAdmin(port, token, SECOND_ID, SECOND_PASSWORD, jdbc)
		var row = single("ADMIN_CREATED")
		assertEquals(listOf("ADMIN", created.toString(), null), listOf(row["target_type"], row["target_id"], row["detail"]))

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/admins/$created", """{"active":false}""", token).statusCode())
		row = single("ADMIN_DEACTIVATED")
		assertEquals(listOf("ADMIN", created.toString()), listOf(row["target_type"], row["target_id"]))

		// 이미 꺼진 관리자를 또 끄는 것은 바뀐 것이 없어 남지 않는다.
		assertEquals(200, http(port, "PATCH", "/admin/admins/$created", """{"active":false}""", token).statusCode())
		assertEquals(1, rows().size)

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/admins/$created", """{"active":true}""", token).statusCode())
		row = single("ADMIN_REACTIVATED")
		assertEquals(listOf("ADMIN", created.toString()), listOf(row["target_type"], row["target_id"]))
	}

	@Test
	fun `거절된 관리자 요청은 남지 않는다`() {
		assertEquals(409, http(port, "PATCH", "/admin/admins/$rootId", """{"active":false}""", token).statusCode())
		assertEquals(400, http(port, "POST", "/admin/admins", """{"loginId":"x"}""", token).statusCode())
		assertEquals(404, http(port, "GET", "/admin/inquiries/${UUID.randomUUID()}", token = token).statusCode())
		assertEquals(404, http(port, "DELETE", "/admin/faqs/${UUID.randomUUID()}", token = token).statusCode())
		assertEquals(0, rows().size)
	}

	@Test
	fun `첫 관리자를 만든 것은 SYSTEM 의 행이다`() {
		jdbc.update("delete from admin_sessions")
		jdbc.update("delete from admin_accounts")

		assertEquals(true, accounts.bootstrap(ROOT_ID, ROOT_PASSWORD))
		val newRoot = jdbc.queryForObject("select id::text from admin_accounts", String::class.java)
		val row = rows().single()
		assertEquals(listOf("ADMIN_BOOTSTRAPPED", "SUCCESS", "SYSTEM", null, null), listOf(row["action"], row["outcome"], row["actor_type"], row["actor_id"], row["actor_label"]))
		assertEquals(listOf("ADMIN", newRoot, null), listOf(row["target_type"], row["target_id"], row["detail"]))

		// 관리자가 있으면 만들지 않고 남기지도 않는다.
		assertEquals(false, accounts.bootstrap("another-root", ROOT_PASSWORD))
		assertEquals(1, rows().size)
	}

	@Test
	fun `문의는 한 건 보기만 남고 목록은 남지 않는다`() {
		val inquiry = seedInquiry()
		assertEquals(200, http(port, "GET", "/admin/inquiries", token = token).statusCode())
		assertEquals(0, rows().size)

		assertEquals(200, http(port, "GET", "/admin/inquiries/$inquiry", token = token).statusCode())
		val row = single("INQUIRY_VIEWED")
		assertEquals(listOf("INQUIRY", inquiry.toString(), null), listOf(row["target_type"], row["target_id"], row["detail"]))
	}

	@Test
	fun `문의 처리는 상태와 메모를 바꿨는지만 남는다`() {
		val inquiry = seedInquiry()
		assertEquals(200, http(port, "PATCH", "/admin/inquiries/$inquiry", json(mapOf("status" to "DONE", "memo" to MEMO)), token).statusCode())
		var row = single("INQUIRY_UPDATED")
		assertEquals(listOf("INQUIRY", inquiry.toString()), listOf(row["target_type"], row["target_id"]))
		assertEquals("""{"status": "DONE", "memoChanged": true}""", row["detail"])

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/inquiries/$inquiry", json(mapOf("memo" to "$MEMO 2")), token).statusCode())
		row = single("INQUIRY_UPDATED")
		assertEquals("""{"memoChanged": true}""", row["detail"])

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/inquiries/$inquiry", """{"status":"IN_PROGRESS"}""", token).statusCode())
		assertEquals("""{"status": "IN_PROGRESS", "memoChanged": false}""", single("INQUIRY_UPDATED")["detail"])
	}

	@Test
	fun `문의 답은 처음인지만 남고 메모와 함께면 두 줄, 임시 저장은 바꿨다는 표시만 남는다`() {
		val inquiry = seedInquiry()
		assertEquals(200, http(port, "PATCH", "/admin/inquiries/$inquiry", json(mapOf("answerDraft" to "임시답비밀글")), token).statusCode())
		assertEquals("""{"memoChanged": false, "draftChanged": true}""", single("INQUIRY_UPDATED")["detail"])

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/inquiries/$inquiry", json(mapOf("answer" to "답글비밀글")), token).statusCode())
		val row = single("INQUIRY_ANSWERED")
		assertEquals(listOf("INQUIRY", inquiry.toString(), """{"first": true}"""), listOf(row["target_type"], row["target_id"], row["detail"]))

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/inquiries/$inquiry", json(mapOf("answer" to "답글비밀글 고침", "memo" to MEMO)), token).statusCode())
		assertEquals(
			listOf("INQUIRY_ANSWERED" to """{"first": false}""", "INQUIRY_UPDATED" to """{"memoChanged": true}"""),
			rows().map { it["action"] to it["detail"] },
		)
		assertFalse(jdbc.queryForList("select detail::text from audit_logs", String::class.java).any { it?.contains("비밀글") == true }, "글은 남지 않는다")
	}

	@Test
	fun `FAQ 만들기·고치기·공개·내리기·지우기·순서가 남는다`() {
		val faq = createFaq()
		var row = single("FAQ_CREATED")
		assertEquals(listOf("FAQ", faq, """{"published": false}"""), listOf(row["target_type"], row["target_id"], row["detail"]))

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/faqs/$faq", json(mapOf("answer" to "$ANSWER 고침")), token).statusCode())
		row = single("FAQ_UPDATED")
		assertEquals(listOf("FAQ", faq, null), listOf(row["target_type"], row["target_id"], row["detail"]))

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/faqs/$faq", """{"published":true}""", token).statusCode())
		row = single("FAQ_PUBLISHED")
		assertEquals(listOf("FAQ", faq, null), listOf(row["target_type"], row["target_id"], row["detail"]))

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/faqs/$faq", """{"published":false}""", token).statusCode())
		single("FAQ_UNPUBLISHED")

		// 글과 공개를 한 번에 바꾸면 두 줄이다.
		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PATCH", "/admin/faqs/$faq", json(mapOf("question" to "$QUESTION 고침", "published" to true)), token).statusCode())
		assertEquals(listOf("FAQ_PUBLISHED", "FAQ_UPDATED"), rows().map { it["action"].toString() }.sorted())

		jdbc.update("delete from audit_logs")
		val other = createFaq(published = true)
		assertEquals("""{"published": true}""", single("FAQ_CREATED")["detail"])

		jdbc.update("delete from audit_logs")
		assertEquals(200, http(port, "PUT", "/admin/faqs/order", json(mapOf("ids" to listOf(other, faq))), token).statusCode())
		row = single("FAQ_REORDERED")
		assertEquals(listOf(null, null, """{"count": 2}"""), listOf(row["target_type"], row["target_id"], row["detail"]))

		jdbc.update("delete from audit_logs")
		assertEquals(400, http(port, "PUT", "/admin/faqs/order", json(mapOf("ids" to listOf(other))), token).statusCode())
		assertEquals(0, rows().size, "거절된 순서 바꾸기(되돌려진 트랜잭션)는 남지 않는다")

		assertEquals(204, http(port, "DELETE", "/admin/faqs/$faq", token = token).statusCode())
		row = single("FAQ_DELETED")
		assertEquals(listOf("FAQ", faq, null), listOf(row["target_type"], row["target_id"], row["detail"]))
	}

	@Test
	fun `관리자가 넣은 글·비밀번호·토큰·이름·이메일은 어느 행에도 없다`() {
		val inquiry = seedInquiry()
		val created = createAdmin(port, token, SECOND_ID, SECOND_PASSWORD, jdbc)
		val second = adminToken(port, SECOND_ID, SECOND_PASSWORD)
		login(port, ROOT_ID, "wrong-password-typed")
		login(port, "typed-password-as-id", "x")
		http(port, "PUT", "/admin/me/password", json(mapOf("currentPassword" to SECOND_PASSWORD, "newPassword" to "brand-new-password-1")), second)
		http(port, "GET", "/admin/inquiries/$inquiry", token = token)
		http(port, "PATCH", "/admin/inquiries/$inquiry", json(mapOf("status" to "DONE", "memo" to MEMO)), token)
		val faq = createFaq(published = true)
		http(port, "PATCH", "/admin/faqs/$faq", json(mapOf("answer" to "$ANSWER 고침")), token)
		http(port, "POST", "/admin/auth/logout", token = second)
		http(port, "PATCH", "/admin/admins/$created", """{"active":false}""", token)

		val dump = jdbc.queryForList("select row_to_json(a)::text from audit_logs a", String::class.java).joinToString("\n")
		assertEquals(11, dump.lines().size)
		val secrets = listOf(
			MEMO, "메모비밀글", INQUIRY_BODY, "문의비밀글", REPLY_EMAIL, "@", QUESTION, "질문비밀글", ANSWER, "답비밀글", "둘째관리자이름",
			ROOT_PASSWORD, SECOND_PASSWORD, "brand-new-password-1", "wrong-password-typed", "typed-password-as-id", token, second, sha256(token),
		)
		for (secret in secrets) assertFalse(secret in dump, "감사 기록에 있으면 안 되는 값: ${secret.take(6)}…")
	}
}
