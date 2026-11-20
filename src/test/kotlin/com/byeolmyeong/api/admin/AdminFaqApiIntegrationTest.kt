package com.byeolmyeong.api.admin

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val FAQ_KEYS = setOf("id", "question", "answer", "sortOrder", "published", "updatedAt", "updatedBy")

/** FAQ 편집(`/admin/faqs`)과 공개 조회(`GET /faqs`)를 함께 본다. */
@AdminIntegrationTest
class AdminFaqApiIntegrationTest {

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

	private fun create(question: Any?, answer: Any?, published: Any? = null, token: String = this.token): HttpResponse<String> {
		val body = mutableMapOf("question" to question, "answer" to answer)
		if (published != null) body["published"] = published
		return http(port, "POST", "/admin/faqs", json(body), token)
	}

	private fun created(question: String, published: Boolean = true): String =
		create(question, "$question 의 답", published).also { assertEquals(201, it.statusCode()) }.json()["id"].asString()

	private fun adminList(): List<JsonNode> = http(port, "GET", "/admin/faqs", token = token).also { assertEquals(200, it.statusCode()) }.json().toList()

	private fun publicList(): List<JsonNode> = http(port, "GET", "/faqs").also { assertEquals(200, it.statusCode()) }.json().toList()

	private fun questions(list: List<JsonNode>): List<String> = list.map { it["question"].asString() }

	private fun patch(id: Any, body: String?, token: String = this.token): HttpResponse<String> = http(port, "PATCH", "/admin/faqs/$id", body, token)

	private fun reorder(body: String?): HttpResponse<String> = http(port, "PUT", "/admin/faqs/order", body, token)

	@Test
	fun `FAQ 가 없으면 공개 목록은 빈 배열이다`() {
		val response = http(port, "GET", "/faqs")
		assertEquals(200, response.statusCode())
		assertEquals("[]", response.body())
		assertEquals("[]", http(port, "GET", "/admin/faqs", token = token).body())
	}

	@Test
	fun `FAQ 를 만들면 끝에 붙고 공개 전에는 공개 목록에 없다`() {
		val before = Instant.now().minusSeconds(2)
		val response = create("  결제는 어떻게 하나요?  ", "  지금은 무료입니다.\n\n나중에 안내드릴게요.  ")
		assertEquals(201, response.statusCode())
		val faq = response.json()
		assertEquals(FAQ_KEYS, faq.propertyNames().toSet())
		assertEquals("결제는 어떻게 하나요?", faq["question"].asString(), "앞뒤 공백을 뗀다")
		assertEquals("지금은 무료입니다.\n\n나중에 안내드릴게요.", faq["answer"].asString())
		assertEquals(false, faq["published"].asBoolean(), "처음에는 공개하지 않는다")
		assertTrue(Instant.parse(faq["updatedAt"].asString()).isAfter(before))
		assertEquals(setOf("id", "name"), faq["updatedBy"].propertyNames().toSet())
		assertEquals(ROOT_ID, faq["updatedBy"]["name"].asString())
		UUID.fromString(faq["id"].asString())

		created("둘째 질문", published = true)
		created("셋째 질문", published = false)

		val list = adminList()
		assertEquals(listOf("결제는 어떻게 하나요?", "둘째 질문", "셋째 질문"), questions(list), "만든 순서대로 끝에 붙는다")
		assertEquals(FAQ_KEYS, list[0].propertyNames().toSet())
		val orders = list.map { it["sortOrder"].asInt() }
		assertEquals(orders.sorted(), orders)
		assertEquals(3, orders.toSet().size)
		assertEquals(listOf("둘째 질문"), questions(publicList()))
	}

	@Test
	fun `공개 목록은 공개한 것만 순서대로, id 와 질문과 답만 준다`() {
		created("하나"); created("둘", published = false); created("셋")

		val response = http(port, "GET", "/faqs")
		assertEquals(200, response.statusCode())
		val list = response.json().toList()
		assertEquals(listOf("하나", "셋"), questions(list))
		for (item in list) assertEquals(setOf("id", "question", "answer"), item.propertyNames().toSet(), "순서·공개 여부·고친 관리자는 내보내지 않는다")
		assertEquals("하나 의 답", list[0]["answer"].asString())
		assertEquals(false, response.body().contains(ROOT_ID))
		assertEquals("public, max-age=300", response.headers().firstValue("Cache-Control").orElse(null))

		assertEquals(listOf("하나", "셋"), questions(http(port, "GET", "/faqs", token = devToken(port)).json().toList()), "세션이 있어도 같다")
		assertEquals(200, http(port, "GET", "/faqs", token = "no-such-token").statusCode(), "세션을 보지 않는다")
	}

	@Test
	fun `공개 경로는 GET 뿐이고 그 밖의 faqs 경로는 열리지 않는다`() {
		val id = created("하나")
		val calls = listOf(
			Triple("POST", "/faqs", """{"question":"몰래 넣은 질문","answer":"몰래 넣은 답"}"""),
			Triple("PUT", "/faqs", """{"ids":[]}"""),
			Triple("PATCH", "/faqs", """{"published":false}"""),
			Triple("DELETE", "/faqs", null),
			Triple("GET", "/faqs/$id", null),
			Triple("PATCH", "/faqs/$id", """{"published":false}"""),
			Triple("DELETE", "/faqs/$id", null),
			Triple("PUT", "/faqs/order", """{"ids":[]}"""),
			Triple("GET", "/faqs/", null),
		)
		for ((method, path, body) in calls) {
			for (auth in listOf(null, token)) {
				val response = http(port, method, path, body, auth)
				assertTrue(response.statusCode() in listOf(401, 404, 405), "$method $path → ${response.statusCode()}")
			}
		}
		assertEquals(listOf("하나"), questions(publicList()))
		assertEquals(1, jdbc.queryForObject("select count(*) from faqs", Int::class.java))
	}

	@Test
	fun `질문은 200자 답은 2000자까지다 (코드 포인트)`() {
		fun assertRejected(code: String, question: Any?, answer: Any?, published: Any? = null) {
			val response = create(question, answer, published)
			assertEquals(400, response.statusCode(), "$code: ${question.toString().take(20)}")
			assertEquals("""{"error":"$code"}""", response.body())
		}
		for (question in listOf(null, "", "   ", "가".repeat(201), "😀".repeat(201), "질문\u0000")) assertRejected("INVALID_QUESTION", question, "답")
		for (answer in listOf(null, "", " \n ", "가".repeat(2001), "😀".repeat(2001), "답\u0000")) assertRejected("INVALID_ANSWER", "질문", answer)
		for (body in listOf(
			null, "", "{", "null", "[]", "\"질문\"",
			"""{"question":1,"answer":"답"}""", """{"question":["질문"],"answer":"답"}""", """{"question":"질문","answer":{"v":"답"}}""",
			"""{"question":"질문","answer":"답","published":"true"}""", """{"question":"질문","answer":"답","published":1}""",
		)) {
			val response = http(port, "POST", "/admin/faqs", body, token)
			assertEquals(400, response.statusCode(), "본문: $body")
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body())
		}
		assertEquals(0, jdbc.queryForObject("select count(*) from faqs", Int::class.java))

		assertEquals(201, create("가".repeat(200), "가".repeat(2000)).statusCode())
		assertEquals(201, create("😀".repeat(200), "😀".repeat(2000)).statusCode())
		assertEquals(201, create("  " + "가".repeat(200) + "  ", "답", true).statusCode(), "공백을 떼면 200자다")
		assertEquals(201, create("질문", "답", false).statusCode())
		assertEquals(listOf(200, 200, 200, 2), jdbc.queryForList("select char_length(question) from faqs order by sort_order", Int::class.java))
	}

	@Test
	fun `FAQ 를 고치고 공개하고 내린다`() {
		val id = created("처음 질문", published = false)
		createAdmin(port, token, "ops-kim", "ops-kim-password", jdbc)
		val theirs = adminToken(port, "ops-kim", "ops-kim-password")

		val published = patch(id, """{"published":true}""", theirs)
		assertEquals(200, published.statusCode())
		assertEquals(FAQ_KEYS, published.json().propertyNames().toSet())
		assertEquals(true, published.json()["published"].asBoolean())
		assertEquals("처음 질문", published.json()["question"].asString(), "보내지 않은 것은 그대로다")
		assertEquals("ops-kim", published.json()["updatedBy"]["name"].asString(), "고친 관리자를 적는다")
		assertEquals(listOf("처음 질문"), questions(publicList()))

		val edited = patch(id, """{"question":"  고친 질문  ","answer":"고친 답","updatedBy":"${UUID.randomUUID()}","sortOrder":99}""")
		assertEquals(200, edited.statusCode())
		assertEquals(listOf("고친 질문", "고친 답", ROOT_ID), listOf(edited.json()["question"].asString(), edited.json()["answer"].asString(), edited.json()["updatedBy"]["name"].asString()))
		assertEquals(true, edited.json()["published"].asBoolean())
		assertEquals(published.json()["sortOrder"], edited.json()["sortOrder"], "순서는 여기서 바꾸지 않는다")
		assertEquals("고친 답", publicList()[0]["answer"].asString())

		assertEquals(false, patch(id, """{"published":false}""").json()["published"].asBoolean())
		assertEquals(emptyList(), publicList())
		assertEquals("고친 질문", adminList()[0]["question"].asString())

		val errors = listOf(
			"INVALID_REQUEST" to null, "INVALID_REQUEST" to "{}", "INVALID_REQUEST" to "[]", "INVALID_REQUEST" to """{"other":1}""",
			"INVALID_REQUEST" to """{"question":1}""", "INVALID_REQUEST" to """{"published":"yes"}""", "INVALID_REQUEST" to """{"published":null}""",
			"INVALID_QUESTION" to """{"question":""}""", "INVALID_QUESTION" to """{"question":null}""", "INVALID_QUESTION" to json(mapOf("question" to "가".repeat(201))),
			"INVALID_ANSWER" to """{"answer":"  "}""", "INVALID_ANSWER" to """{"answer":null}""", "INVALID_ANSWER" to json(mapOf("question" to "질문", "answer" to "가".repeat(2001))),
		)
		for ((code, body) in errors) {
			val response = patch(id, body)
			assertEquals(400, response.statusCode(), "본문: ${body?.take(40)}")
			assertEquals("""{"error":"$code"}""", response.body())
		}
		assertEquals(listOf("고친 질문", "고친 답"), adminList()[0].let { listOf(it["question"].asString(), it["answer"].asString()) }, "거절된 요청은 아무것도 바꾸지 않는다")
		for (missing in listOf(UUID.randomUUID().toString(), "not-a-uuid", "order")) {
			val response = patch(missing, """{"published":true}""")
			assertEquals(404, response.statusCode(), missing)
			assertEquals("""{"error":"NOT_FOUND"}""", response.body())
		}
	}

	@Test
	fun `FAQ 를 지운다`() {
		val first = created("하나")
		val second = created("둘")

		val response = http(port, "DELETE", "/admin/faqs/$first", token = token)
		assertEquals(204, response.statusCode())
		assertEquals("", response.body())
		assertEquals(listOf("둘"), questions(adminList()))
		assertEquals(listOf("둘"), questions(publicList()))

		for (missing in listOf(first, UUID.randomUUID().toString(), "not-a-uuid")) {
			assertEquals(404, http(port, "DELETE", "/admin/faqs/$missing", token = token).statusCode(), missing)
		}
		assertEquals(404, patch(first, """{"published":true}""").statusCode())
		assertEquals(second, adminList()[0]["id"].asString())
		assertEquals("셋", questions(adminList() + listOf(create("셋", "답").json())).last())
		assertEquals(listOf("둘", "셋"), questions(adminList()), "지운 뒤에 만든 것도 끝에 붙는다")
	}

	@Test
	fun `순서를 바꾸면 관리자 목록과 공개 목록이 그 순서가 된다`() {
		val a = created("하나"); val b = created("둘", published = false); val c = created("셋"); val d = created("넷")
		val updatedBefore = adminList().associate { it["id"].asString() to it["updatedAt"].asString() }

		val response = reorder(json(mapOf("ids" to listOf(d, b, a, c))))
		assertEquals(200, response.statusCode())
		assertEquals(listOf("넷", "둘", "하나", "셋"), questions(response.json().toList()), "응답은 새 순서의 전체 목록이다")
		assertEquals(FAQ_KEYS, response.json()[0].propertyNames().toSet())
		assertEquals(listOf("넷", "둘", "하나", "셋"), questions(adminList()))
		assertEquals(listOf("넷", "하나", "셋"), questions(publicList()))
		assertEquals(updatedBefore, adminList().associate { it["id"].asString() to it["updatedAt"].asString() }, "순서만 바꾼 것은 고친 때를 바꾸지 않는다")
		assertEquals(listOf("넷", "둘", "하나", "셋", "다섯"), questions(adminList() + listOf(create("다섯", "답").json())), "새 FAQ 는 여전히 끝에 붙는다")
		assertEquals("다섯", questions(adminList()).last())
	}

	@Test
	fun `순서 요청은 지금 있는 FAQ 전부를 한 번씩 담아야 한다`() {
		val a = created("하나"); val b = created("둘"); val c = created("셋")
		val bad = listOf(
			listOf(a, b), // 하나가 빠졌다
			listOf(a, b, c, UUID.randomUUID().toString()), // 없는 것이 있다
			listOf(a, b, UUID.randomUUID().toString()),
			listOf(a, b, b), // 겹친다
			listOf(a, b, c, c),
			listOf(a, b, "not-a-uuid"),
			emptyList(),
		)
		for (ids in bad) {
			val response = reorder(json(mapOf("ids" to ids)))
			assertEquals(400, response.statusCode(), "ids: $ids")
			assertEquals("""{"error":"INVALID_ORDER"}""", response.body())
		}
		for (body in listOf(null, "{}", "[]", "null", """{"ids":null}""", """{"ids":"$a"}""", """{"ids":{"0":"$a"}}""", """{"ids":["$a","$b",3]}""", """{"ids":["$a","$b",null]}""", """{"ids":[["$a","$b","$c"]]}""")) {
			val response = reorder(body)
			assertEquals(400, response.statusCode(), "본문: $body")
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body())
		}
		assertEquals(listOf("하나", "둘", "셋"), questions(adminList()), "거절된 요청은 순서를 바꾸지 않는다")

		assertEquals(200, reorder(json(mapOf("ids" to listOf(c.uppercase(), b, a)))).statusCode(), "id 의 대소문자는 가리지 않는다")
		assertEquals(listOf("셋", "둘", "하나"), questions(adminList()))

		jdbc.update("delete from faqs")
		val empty = reorder("""{"ids":[]}""")
		assertEquals(200, empty.statusCode())
		assertEquals("[]", empty.body())
	}

	@Test
	fun `꺼진 관리자가 고친 FAQ 도 그대로 보인다`() {
		val other = createAdmin(port, token, "ops-kim", "ops-kim-password", jdbc)
		val id = create("운영 김이 만든 질문", "답", true, adminToken(port, "ops-kim", "ops-kim-password")).json()["id"].asString()

		assertEquals(200, http(port, "PATCH", "/admin/admins/$other", """{"active":false}""", token).statusCode())

		val faq = adminList().single { it["id"].asString() == id }
		assertEquals("ops-kim", faq["updatedBy"]["name"].asString())
		assertEquals(listOf("운영 김이 만든 질문"), questions(publicList()))
	}
}

/** 공개 조회의 주소별 제한. 한도를 3 으로 낮춰 본다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["byeolmyeong.rate-limit.faqs-per-minute=3"])
class FaqRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `공개 목록은 한 주소에서 분당 한도를 넘으면 429 다`() {
		repeat(3) { assertEquals(200, http(port, "GET", "/faqs").statusCode(), "${it + 1}번째") }

		val limited = http(port, "GET", "/faqs")
		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
		assertEquals(200, http(port, "GET", "/health").statusCode(), "다른 경로는 막지 않는다")
	}
}
