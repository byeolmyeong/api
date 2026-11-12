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
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val INQUIRY_KEYS = setOf("id", "category", "body", "replyEmail", "createdAt", "status", "memo", "handledBy", "handledAt", "ownerRef")

@AdminIntegrationTest
class AdminInquiryApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var token: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		token = adminToken(port)
	}

	/** 문의 한 건을 DB 에 직접 넣는다. [minutesAgo] 가 클수록 오래된 문의다. */
	private fun seed(owner: UUID, minutesAgo: Int, category: String = "BUG", status: String = "NEW", body: String = "문의 글 $minutesAgo 분 전에 보냄", email: String? = null): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into inquiries (id, owner_id, category, body, reply_email, status, created_at) values (?, ?, ?, ?, ?, ?, now() - make_interval(mins => ?))",
			id, owner, category, body, email, status, minutesAgo,
		)
		return id
	}

	private fun list(query: String = "", token: String = this.token): HttpResponse<String> = http(port, "GET", "/admin/inquiries$query", token = token)

	private fun ids(response: HttpResponse<String>): List<String> = response.json()["items"].toList().map { it["id"].asString() }

	private fun patch(id: Any, body: String?, token: String = this.token): HttpResponse<String> = http(port, "PATCH", "/admin/inquiries/$id", body, token)

	@Test
	fun `문의 목록은 새것부터이고 모양이 정해져 있다`() {
		val (owner, _) = userAccount(jdbc, "user-a")
		jdbc.update("insert into profiles (account_id, birth_date, birth_time, place_id) values (?, '1991-02-03', '04:05', 1841277)", owner)
		val old = seed(owner, 30, "ACCOUNT", email = "reply@example.com")
		val newest = seed(owner, 1, "BUG")
		val middle = seed(owner, 10, "OTHER")

		val response = list()
		assertEquals(200, response.statusCode())
		val json = response.json()
		assertEquals(setOf("items", "page", "size", "total"), json.propertyNames().toSet())
		assertEquals(listOf(0, 20, 3), listOf(json["page"].asInt(), json["size"].asInt(), json["total"].asInt()))
		assertEquals(listOf(newest, middle, old).map { it.toString() }, ids(response))

		val item = json["items"][2]
		assertEquals(INQUIRY_KEYS, item.propertyNames().toSet(), "정해진 것만 준다(계정 id·출생 정보 없음)")
		assertEquals("ACCOUNT", item["category"].asString())
		assertEquals("문의 글 30 분 전에 보냄", item["body"].asString())
		assertEquals("reply@example.com", item["replyEmail"].asString())
		assertEquals("NEW", item["status"].asString())
		assertTrue(item["createdAt"].asString().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))
		assertTrue(Instant.parse(item["createdAt"].asString()).isBefore(Instant.now().minusSeconds(29 * 60)))
		for (key in listOf("memo", "handledBy", "handledAt")) assertTrue(item[key].isNull, "$key 는 키가 있고 null 이다")
		assertTrue(json["items"][0]["replyEmail"].isNull)

		for (hidden in listOf(owner.toString(), "1991", "02-03", "04:05", "1841277", "owner_id", "ownerId")) {
			assertFalse(response.body().contains(hidden), "응답에 '$hidden' 이 있다")
		}
	}

	@Test
	fun `ownerRef 는 같은 계정이면 같고 계정 id 가 아니다`() {
		val (a, _) = userAccount(jdbc, "user-a")
		val (b, _) = userAccount(jdbc, "user-b")
		seed(a, 3); seed(a, 2); seed(b, 1)

		val refs = list().json()["items"].toList().map { it["ownerRef"].asString() }
		assertEquals(refs[1], refs[2])
		assertNotEquals(refs[0], refs[1])
		assertTrue(refs.all { it.matches(Regex("[0-9a-f]{12}")) })
		assertFalse(a.toString().replace("-", "").startsWith(refs[1]))
	}

	@Test
	fun `상태와 종류로 거른다`() {
		val (owner, _) = userAccount(jdbc, "user-a")
		val newBug = seed(owner, 1, "BUG", "NEW")
		val doneBug = seed(owner, 2, "BUG", "DONE")
		val progressAccount = seed(owner, 3, "ACCOUNT", "IN_PROGRESS")
		val newAccount = seed(owner, 4, "ACCOUNT", "NEW")

		fun assertList(query: String, vararg expected: UUID) {
			val response = list(query)
			assertEquals(200, response.statusCode(), query)
			assertEquals(expected.map { it.toString() }, ids(response), query)
			assertEquals(expected.size, response.json()["total"].asInt(), query)
		}
		assertList("?status=NEW", newBug, newAccount)
		assertList("?status=IN_PROGRESS", progressAccount)
		assertList("?status=DONE", doneBug)
		assertList("?category=BUG", newBug, doneBug)
		assertList("?category=ACCOUNT&status=NEW", newAccount)
		assertList("?category=READING")
		assertList("?category=NO_SUCH_CATEGORY")
		assertList("?status=&category=", newBug, doneBug, progressAccount, newAccount)

		for (query in listOf("?status=new", "?status=CLOSED", "?status=NEW,DONE", "?status=NEW&status=DONE", "?status=%27%3B%20drop%20table%20inquiries%3B--")) {
			val response = list(query)
			assertEquals(400, response.statusCode(), query)
			assertEquals("""{"error":"INVALID_STATUS"}""", response.body())
		}
		assertEquals(200, list("?category=%27%3B%20drop%20table%20inquiries%3B--").statusCode())
		assertEquals(4, jdbc.queryForObject("select count(*) from inquiries", Int::class.java))
	}

	@Test
	fun `쪽으로 나눠 준다`() {
		val (owner, _) = userAccount(jdbc, "user-a")
		val all = (1..5).map { seed(owner, it, status = if (it == 5) "DONE" else "NEW").toString() }

		fun page(query: String): JsonNode = list(query).also { assertEquals(200, it.statusCode(), query) }.json()
		assertEquals(all.subList(0, 2), page("?size=2")["items"].toList().map { it["id"].asString() })
		assertEquals(all.subList(2, 4), page("?size=2&page=1")["items"].toList().map { it["id"].asString() })
		assertEquals(all.subList(4, 5), page("?size=2&page=2")["items"].toList().map { it["id"].asString() })
		val last = page("?size=2&page=2")
		assertEquals(listOf(2, 2, 5), listOf(last["page"].asInt(), last["size"].asInt(), last["total"].asInt()))
		val beyond = page("?size=2&page=3")
		assertEquals(0, beyond["items"].size(), "마지막 쪽을 넘으면 빈 목록이다")
		assertEquals(5, beyond["total"].asInt())
		assertEquals(0, page("?page=2147483647&size=100")["items"].size())
		assertEquals(5, page("?size=100")["items"].size())
		assertEquals(1, page("?size=1&page=0")["items"].size())
		val filtered = page("?status=NEW&size=3&page=1")
		assertEquals(listOf(all[3]), filtered["items"].toList().map { it["id"].asString() })
		assertEquals(4, filtered["total"].asInt(), "total 은 거른 뒤의 전체 수다")

		for (query in listOf("?page=-1", "?page=abc", "?page=1.5", "?size=0", "?size=101", "?size=-5", "?size=many", "?page=99999999999", "?size=2&size=3")) {
			val response = list(query)
			assertEquals(400, response.statusCode(), query)
			assertEquals("""{"error":"INVALID_PAGE"}""", response.body())
		}
	}

	@Test
	fun `문의 한 건을 본다`() {
		val (owner, _) = userAccount(jdbc, "user-a")
		val id = seed(owner, 5, "SYNASTRY", body = "첫 줄\n둘째 줄 \"따옴표\"", email = "reply@example.com")

		val response = http(port, "GET", "/admin/inquiries/$id", token = token)
		assertEquals(200, response.statusCode())
		assertEquals(INQUIRY_KEYS, response.json().propertyNames().toSet())
		assertEquals("첫 줄\n둘째 줄 \"따옴표\"", response.json()["body"].asString())
		assertEquals(list().json()["items"][0], response.json(), "목록의 원소와 같은 모양·같은 값이다")

		for (path in listOf("/admin/inquiries/${UUID.randomUUID()}", "/admin/inquiries/not-a-uuid", "/admin/inquiries/1")) {
			val missing = http(port, "GET", path, token = token)
			assertEquals(404, missing.statusCode(), path)
			assertEquals("""{"error":"NOT_FOUND"}""", missing.body())
		}
	}

	@Test
	fun `상태와 메모를 바꾸면 바꾼 관리자와 그때를 적는다`() {
		val (owner, _) = userAccount(jdbc, "user-a")
		val id = seed(owner, 5)
		val rootId = http(port, "GET", "/admin/me", token = token).json()["id"].asString()
		val before = Instant.now().minusSeconds(2)

		val response = patch(id, """{"status":"IN_PROGRESS","memo":"  메일로 답함.\n환불 안내  ","handledBy":"${UUID.randomUUID()}","body":"고친 글"}""")
		assertEquals(200, response.statusCode())
		val json = response.json()
		assertEquals(INQUIRY_KEYS, json.propertyNames().toSet())
		assertEquals("IN_PROGRESS", json["status"].asString())
		assertEquals("메일로 답함.\n환불 안내", json["memo"].asString(), "앞뒤 공백을 뗀다")
		assertEquals(setOf("id", "name"), json["handledBy"].propertyNames().toSet())
		assertEquals(rootId, json["handledBy"]["id"].asString(), "처리한 관리자는 세션에서 얻는다(본문의 값을 믿지 않는다)")
		assertEquals(ROOT_ID, json["handledBy"]["name"].asString())
		assertTrue(Instant.parse(json["handledAt"].asString()).isAfter(before))
		assertEquals("문의 글 5 분 전에 보냄", json["body"].asString(), "문의 글은 고칠 수 없다")

		val row = jdbc.queryForMap("select * from inquiries where id = ?", id)
		assertEquals(listOf("IN_PROGRESS", "메일로 답함.\n환불 안내", UUID.fromString(rootId)), listOf(row["status"], row["admin_memo"], row["handled_by"]))
		assertEquals(json, http(port, "GET", "/admin/inquiries/$id", token = token).json())

		// 다른 관리자가 상태만 바꾼다: 메모는 그대로, 처리한 관리자는 그 사람이 된다.
		createAdmin(port, token, "ops-kim", "ops-kim-password", "운영 김")
		val theirs = adminToken(port, "ops-kim", "ops-kim-password")
		val second = patch(id, """{"status":"DONE"}""", theirs).json()
		assertEquals("DONE", second["status"].asString())
		assertEquals("메일로 답함.\n환불 안내", second["memo"].asString())
		assertEquals("운영 김", second["handledBy"]["name"].asString())

		// 메모만 바꾼다: 상태는 그대로. 어느 쪽으로든 되돌릴 수 있다.
		val third = patch(id, """{"memo":"다시 확인 필요"}""").json()
		assertEquals(listOf("DONE", "다시 확인 필요", ROOT_ID), listOf(third["status"].asString(), third["memo"].asString(), third["handledBy"]["name"].asString()))
		assertEquals("NEW", patch(id, """{"status":"NEW"}""").json()["status"].asString())
		assertEquals("DONE", patch(id, """{"status":"DONE"}""").json()["status"].asString())
		assertEquals("IN_PROGRESS", patch(id, """{"status":"IN_PROGRESS"}""").json()["status"].asString())

		// 메모를 비운다
		for (clear in listOf("""{"memo":null}""", """{"memo":""}""", """{"memo":"   "}""")) {
			patch(id, """{"memo":"지울 메모"}""")
			val cleared = patch(id, clear)
			assertEquals(200, cleared.statusCode(), clear)
			assertTrue(cleared.json()["memo"].isNull, clear)
			assertEquals("IN_PROGRESS", cleared.json()["status"].asString())
		}
	}

	@Test
	fun `틀린 상태·메모·본문은 400 이고 아무것도 바꾸지 않는다`() {
		val (owner, _) = userAccount(jdbc, "user-a")
		val id = seed(owner, 5)

		fun assertRejected(code: String, body: String?) {
			val response = patch(id, body)
			assertEquals(400, response.statusCode(), "본문: ${body?.take(60)}")
			assertEquals("""{"error":"$code"}""", response.body())
		}
		for (body in listOf(null, "", "{", "null", "[]", "\"DONE\"", "{}", """{"other":1}""", """{"status":1}""", """{"status":["DONE"]}""", """{"status":true}""", """{"memo":1}""", """{"memo":["메모"]}""", """{"memo":{"v":"메모"}}""", """{"status":"DONE","memo":false}""")) {
			assertRejected("INVALID_REQUEST", body)
		}
		for (body in listOf("""{"status":"CLOSED"}""", """{"status":"done"}""", """{"status":""}""", """{"status":null}""", """{"status":null,"memo":"메모"}""")) {
			assertRejected("INVALID_STATUS", body)
		}
		assertRejected("INVALID_MEMO", json(mapOf("memo" to "가".repeat(2001))))
		assertRejected("INVALID_MEMO", json(mapOf("status" to "DONE", "memo" to "😀".repeat(2001))))
		assertRejected("INVALID_MEMO", """{"memo":"메모 \u0000 끝"}""")

		val row = jdbc.queryForMap("select status, admin_memo, handled_by, handled_at from inquiries where id = ?", id)
		assertEquals(listOf("NEW", null, null, null), row.values.toList())

		assertEquals(200, patch(id, json(mapOf("memo" to "가".repeat(2000)))).statusCode())
		assertEquals(200, patch(id, json(mapOf("memo" to "😀".repeat(2000)))).statusCode(), "글자 수는 코드 포인트로 센다")

		for (path in listOf(UUID.randomUUID().toString(), "not-a-uuid")) {
			val missing = patch(path, """{"status":"DONE"}""")
			assertEquals(404, missing.statusCode())
			assertEquals("""{"error":"NOT_FOUND"}""", missing.body())
		}
	}

	@Test
	fun `계정을 지우면 처리한 문의까지 함께 사라진다`() {
		val userToken = devToken(port)
		assertEquals(201, http(port, "POST", "/inquiries", """{"category":"BUG","body":"앱에서 보낸 문의입니다. 확인해 주세요."}""", userToken).statusCode())
		val (other, _) = userAccount(jdbc, "user-b")
		val kept = seed(other, 1)
		val mine = ids(list()).first { it != kept.toString() }
		assertEquals("NEW", list().json()["items"].toList().first { it["id"].asString() == mine }["status"].asString(), "앱이 보낸 문의는 NEW 로 들어온다")
		assertEquals(200, patch(mine, """{"status":"DONE","memo":"답함"}""").statusCode())

		assertEquals(204, http(port, "DELETE", "/account", token = userToken).statusCode())

		assertEquals(listOf(kept.toString()), ids(list()))
		assertEquals(404, http(port, "GET", "/admin/inquiries/$mine", token = token).statusCode())
		assertEquals(1, jdbc.queryForObject("select count(*) from admin_accounts", Int::class.java), "관리자는 그대로다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `문의 글과 메모와 이메일은 로그에 없다`(output: CapturedOutput) {
		val (owner, _) = userAccount(jdbc, "user-a")
		val id = seed(owner, 1, body = "아무도몰라야하는문의글 입니다", email = "hidden-reply@secret-domain.example")

		list(); http(port, "GET", "/admin/inquiries/$id", token = token)
		assertEquals(200, patch(id, """{"status":"DONE","memo":"남기면안되는메모"}""").statusCode())
		assertEquals(400, patch(id, """{"status":"남기면안되는상태","memo":"남기면안되는메모"}""").statusCode())
		assertEquals(400, patch(id, """{"memo":["남기면안되는메모"]}""").statusCode())
		assertEquals(400, patch(id, """{"memo":"남기면안되는메모"""").statusCode())
		assertEquals(400, list("?status=남기면안되는상태").statusCode())

		for (secret in listOf("아무도몰라야하는문의글", "남기면안되는", "hidden-reply", "secret-domain")) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=admin_inquiry_updated status=DONE"))
	}
}
