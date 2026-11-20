package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BODY = "리포트가 열리지 않아요. 확인 부탁드립니다."
private val mapper = JsonMapper()

/** 새 앱의 문의 요청 본문(결정 039). 값은 JSON 글자로 옮겨 넣는다(따옴표·줄바꿈이 있어도 된다). */
private fun inquiry(category: String = "BUG", body: String = BODY): String =
	mapper.writeValueAsString(mapOf("category" to category, "body" to body))

/** 옛 앱의 문의 요청 본문(결정 013 — `replyEmail` 키를 언제나 보낸다). */
private fun legacyInquiry(category: String = "BUG", body: String = BODY, replyEmail: String? = null): String =
	mapper.writeValueAsString(mapOf("category" to category, "body" to body, "replyEmail" to replyEmail))

private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

/** 개발용 계정이 아닌 다른 계정의 세션을 DB 에 직접 만든다. */
private fun otherAccountToken(jdbc: JdbcTemplate, name: String): Pair<UUID, String> {
	val id = UUID.randomUUID()
	val token = "session-token-of-$name"
	jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", id, name)
	jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(token), id)
	return id to token
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.inquiries-per-hour=100000"],
)
class InquiryApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
	}

	private fun post(body: String?, token: String? = devToken(port)): HttpResponse<String> = http(port, "POST", "/inquiries", body, token)

	private fun rows(): Int = jdbc.queryForObject("select count(*) from inquiries", Int::class.java)!!

	private fun assertRejected(code: String, body: String?) {
		val response = post(body)
		assertEquals(400, response.statusCode(), "본문: ${body?.take(80)}")
		assertEquals("""{"error":"$code"}""", response.body())
		assertEquals(0, rows(), "거절한 문의가 저장됐다")
	}

	@Test
	fun `문의를 저장하고 201 과 id 를 준다`() {
		val token = devToken(port)
		val response = http(port, "POST", "/inquiries", inquiry("IDEA", "  $BODY \n"), token, "X-App-Platform", "IOS", "X-App-Version", "1.2.3")

		assertEquals(201, response.statusCode())
		val json = mapper.readTree(response.body())
		assertEquals(listOf("id"), json.propertyNames().toList(), "응답에는 id 만 있다")
		val id = UUID.fromString(json["id"].asString())

		val row = jdbc.queryForMap("select * from inquiries")
		assertEquals(
			setOf(
				"id", "owner_id", "category", "body", "reply_email", "created_at", "status", "admin_memo", "handled_by", "handled_at",
				"answer_body", "answered_at", "answered_by", "answer_draft", "app_platform", "app_version",
			),
			row.keys,
			"정해진 것만 저장한다(기기 이름·주소 없음). 처리 칸은 관리자가(결정 014), 답 칸은 답할 때(결정 039) 적는다",
		)
		assertEquals("NEW", row["status"])
		assertEquals(
			List(7) { null },
			listOf("admin_memo", "handled_by", "handled_at", "answer_body", "answered_at", "answered_by", "answer_draft").map { row[it] },
		)
		assertEquals(listOf("ios", "1.2.3"), listOf(row["app_platform"], row["app_version"]), "보낸 앱의 헤더를 적어 둔다(플랫폼은 소문자)")
		assertEquals(id, row["id"])
		assertEquals(jdbc.queryForObject("select account_id from sessions limit 1", UUID::class.java), row["owner_id"], "소유자는 세션의 계정이다")
		assertEquals("IDEA", row["category"])
		assertEquals(BODY, row["body"], "앞뒤 공백을 떼고 저장한다")
		assertNull(row["reply_email"])
		assertTrue(row["created_at"] != null)
	}

	@Test
	fun `앱 헤더가 없거나 읽을 수 없으면 앱 칸은 비운다`() {
		assertEquals(201, post(inquiry()).statusCode())
		assertEquals(201, http(port, "POST", "/inquiries", inquiry(), devToken(port), "X-App-Platform", "web", "X-App-Version", "1.2-beta").statusCode())
		assertEquals(
			listOf(0),
			listOf(jdbc.queryForObject("select count(*) from inquiries where app_platform is not null or app_version is not null", Int::class.java)),
		)
	}

	@Test
	fun `새 앱의 종류는 넷이고 옛 종류는 받지 않는다`() {
		for (category in listOf("BUG", "IDEA", "ACCOUNT", "ETC")) {
			assertEquals(201, post(inquiry(category)).statusCode(), category)
		}
		assertEquals(listOf("ACCOUNT", "BUG", "ETC", "IDEA"), jdbc.queryForList("select category from inquiries order by category", String::class.java))
		jdbc.update("delete from inquiries")
		for (category in listOf("READING", "SYNASTRY", "OTHER")) assertRejected("INVALID_CATEGORY", inquiry(category))
	}

	@Test
	fun `옛 앱(replyEmail 키)은 옛 종류 다섯과 2000자까지 받고 이메일은 저장하지 않는다`() {
		assertRejected("INVALID_BODY", legacyInquiry("OTHER", "가".repeat(2001)))
		assertRejected("INVALID_CATEGORY", legacyInquiry("PAYMENT"))
		for (category in listOf("ACCOUNT", "READING", "SYNASTRY", "BUG", "OTHER")) {
			assertEquals(201, post(legacyInquiry(category, replyEmail = "me@example.com")).statusCode(), category)
		}
		assertEquals(201, post(legacyInquiry("OTHER", "가".repeat(2000))).statusCode(), "옛 앱은 2000자까지")
		assertEquals(
			listOf("ACCOUNT", "BUG", "OTHER", "OTHER", "READING", "SYNASTRY"),
			jdbc.queryForList("select category from inquiries order by category", String::class.java),
			"옛 종류는 받은 그대로 저장한다",
		)
		assertEquals(0, jdbc.queryForObject("select count(*) from inquiries where reply_email is not null", Int::class.java), "이메일은 와도 무시한다(결정 039)")
	}

	@Test
	fun `본문의 소유자 id 를 믿지 않는다`() {
		val (other, _) = otherAccountToken(jdbc, "someone-else")
		val response = post("""{"category":"BUG","body":"$BODY","ownerId":"$other","owner_id":"$other"}""")

		assertEquals(201, response.statusCode())
		assertEquals(0, jdbc.queryForObject("select count(*) from inquiries where owner_id = ?", Int::class.java, other))
	}

	@Test
	fun `종류가 없거나 목록에 없으면 INVALID_CATEGORY`() {
		assertRejected("INVALID_CATEGORY", inquiry("PAYMENT"))
		assertRejected("INVALID_CATEGORY", inquiry("bug"))
		assertRejected("INVALID_CATEGORY", inquiry(""))
		assertRejected("INVALID_CATEGORY", """{"body":"$BODY"}""")
		assertRejected("INVALID_CATEGORY", """{"category":null,"body":"$BODY"}""")
	}

	@Test
	fun `글은 앞뒤 공백을 뗀 10자부터 1000자까지다 (한글 한 글자가 1)`() {
		assertRejected("INVALID_BODY", inquiry(body = "가".repeat(9)))
		assertRejected("INVALID_BODY", inquiry(body = "   " + "가".repeat(9) + "\n\n  "))
		assertRejected("INVALID_BODY", inquiry(body = "가".repeat(1001)))
		assertRejected("INVALID_BODY", inquiry(body = ""))
		assertRejected("INVALID_BODY", inquiry(body = " ".repeat(50)))
		assertRejected("INVALID_BODY", """{"category":"BUG"}""")
		assertRejected("INVALID_BODY", """{"category":"BUG","body":null}""")

		assertEquals(201, post(inquiry(body = "가".repeat(10))).statusCode())
		assertEquals(201, post(inquiry(body = "가".repeat(1000))).statusCode(), "한글 1000자는 바이트로 3000 이지만 받는다")
		assertEquals(201, post(inquiry(body = "  " + "가".repeat(1000) + "  ")).statusCode(), "공백을 떼면 1000자다")
		assertEquals(
			listOf(10, 1000, 1000),
			jdbc.queryForList("select char_length(body) from inquiries order by created_at", Int::class.java),
		)
	}

	@Test
	fun `글자 수는 코드 포인트로 센다 (이모지 한 개가 1)`() {
		assertRejected("INVALID_BODY", inquiry(body = "😀".repeat(9)))
		assertRejected("INVALID_BODY", inquiry(body = "😀".repeat(1001)))
		assertEquals(201, post(inquiry(body = "😀".repeat(10))).statusCode())
		assertEquals(201, post(inquiry(body = "😀".repeat(1000))).statusCode())
	}

	@Test
	fun `글 가운데의 줄바꿈과 따옴표는 그대로 저장한다`() {
		val body = "첫 줄입니다.\n\n둘째 줄 \"따옴표\" 와 '; drop table inquiries; --"
		assertEquals(201, post(inquiry(body = body)).statusCode())
		assertEquals(body, jdbc.queryForObject("select body from inquiries", String::class.java))
	}

	@Test
	fun `replyEmail 은 값과 타입이 무엇이든 읽지 않고 저장하지 않는다`() {
		val values = listOf("null", "\"\"", "\"me@example.com\"", "\"plainaddress\"", "1", "false", "[\"me@example.com\"]", "{\"v\":1}", "\"me\\u0000@example.com\"")
		for (value in values) assertEquals(201, post("""{"category":"BUG","body":"$BODY","replyEmail":$value}""").statusCode(), value)
		assertEquals(values.size, rows())
		assertEquals(values.size, jdbc.queryForObject("select count(*) from inquiries where reply_email is null", Int::class.java))
	}

	@Test
	fun `본문이 JSON 객체가 아니거나 타입이 틀리면 500 이 아니라 400 INVALID_REQUEST`() {
		val bad = listOf(
			null,
			"",
			"문의합니다",
			"{",
			"""{"category":"BUG","body":"$BODY"""",
			"null",
			"[]",
			"""[{"category":"BUG","body":"$BODY"}]""",
			"\"$BODY\"",
			"12345678901",
			"""{"category":1,"body":"$BODY"}""",
			"""{"category":["BUG"],"body":"$BODY"}""",
			"""{"category":{"v":"BUG"},"body":"$BODY"}""",
			"""{"category":true,"body":"$BODY"}""",
			"""{"category":"BUG","body":12345678901}""",
			"""{"category":"BUG","body":["$BODY"]}""",
			"""{"category":"BUG","body":{"text":"$BODY"}}""",
			"""{"category":"BUG","body":true}""",
		)
		for (body in bad) assertRejected("INVALID_REQUEST", body)
	}

	@Test
	fun `DB 가 받지 못하는 글자(NUL)가 있어도 500 이 아니다`() {
		assertRejected("INVALID_BODY", """{"category":"BUG","body":"문의합니다 \u0000 확인해 주세요"}""")
	}

	@Test
	fun `세션이 없거나 틀리면 401 이고 저장하지 않는다`() {
		for (token in listOf(null, "no-such-token")) {
			val response = post(inquiry(), token)
			assertEquals(401, response.statusCode())
			assertEquals("""{"error":"UNAUTHORIZED"}""", response.body())
		}
		assertEquals(0, rows())
	}

	/** 문의 한 건을 DB 에 직접 넣는다. 옛 문의(종류 · 이메일) · 답한 문의를 흉내 낸다. */
	private fun seed(owner: UUID, minutesAgo: Int, category: String = "BUG", email: String? = null, answer: String? = null, status: String = "NEW"): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"""
			insert into inquiries (id, owner_id, category, body, reply_email, status, created_at, answer_body, answered_at, admin_memo, answer_draft, app_platform, app_version)
			values (?, ?, ?, ?, ?, ?, now() - make_interval(mins => ?), ?, case when ?::text is null then null else now() end, '관리자만보는메모', '보내지않은임시답', 'ios', '1.0.0')
			""",
			id, owner, category, "문의 글 $minutesAgo 분 전", email, status, minutesAgo, answer, answer,
		)
		return id
	}

	private fun devOwner(token: String): UUID =
		jdbc.queryForObject("select account_id from sessions where token_hash = ?", UUID::class.java, sha256(token))!!

	@Test
	fun `내 문의 목록은 내 것만 새것부터 정해진 모양으로 준다`() {
		val token = devToken(port)
		val me = devOwner(token)
		val (other, _) = otherAccountToken(jdbc, "someone-else")
		val old = seed(me, 30, "READING", email = "me@example.com", status = "DONE")
		val answered = seed(me, 10, "IDEA", answer = "답변 드립니다.\n고쳤어요.", status = "ANSWERED")
		val newest = seed(me, 1, "BUG", status = "IN_PROGRESS")
		seed(other, 0, "BUG")

		val response = http(port, "GET", "/inquiries", token = token)
		assertEquals(200, response.statusCode())
		val items = mapper.readTree(response.body())
		assertTrue(items.isArray, "배열이다")
		assertEquals(listOf(newest, answered, old).map { it.toString() }, items.toList().map { it["id"].asString() }, "내 것만, 새것부터")

		val open = items[0]
		assertEquals(setOf("id", "category", "body", "status", "createdAt"), open.propertyNames().toSet(), "값이 없는 키는 보내지 않는다")
		assertEquals(listOf("BUG", "문의 글 1 분 전", "OPEN"), listOf("category", "body", "status").map { open[it].asString() }, "관리자의 처리 단계는 보이지 않는다")
		assertTrue(open["createdAt"].asString().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))

		val withAnswer = items[1]
		assertEquals(setOf("id", "category", "body", "status", "createdAt", "answer"), withAnswer.propertyNames().toSet())
		assertEquals("ANSWERED", withAnswer["status"].asString())
		assertEquals(setOf("body", "answeredAt"), withAnswer["answer"].propertyNames().toSet())
		assertEquals("답변 드립니다.\n고쳤어요.", withAnswer["answer"]["body"].asString())
		assertTrue(withAnswer["answer"]["answeredAt"].asString().endsWith("Z"))

		val legacy = items[2]
		assertEquals(setOf("id", "category", "body", "status", "createdAt", "replyEmail"), legacy.propertyNames().toSet(), "옛 문의는 옛 칸을 그대로")
		assertEquals(listOf("READING", "OPEN", "me@example.com"), listOf("category", "status", "replyEmail").map { legacy[it].asString() })

		for (hidden in listOf("관리자만보는메모", "보내지않은임시답", "1.0.0", "ios", me.toString(), other.toString())) {
			assertFalse(response.body().contains(hidden), "응답에 '$hidden' 이 있다")
		}
	}

	@Test
	fun `문의 한 건은 내 것만 보이고 남의 것 · 없는 것 · id 가 아닌 값은 404`() {
		val token = devToken(port)
		val me = devOwner(token)
		val (other, theirs) = otherAccountToken(jdbc, "someone-else")
		val mine = seed(me, 5, "ETC", answer = "답입니다.", status = "ANSWERED")
		val notMine = seed(other, 5)

		val response = http(port, "GET", "/inquiries/$mine", token = token)
		assertEquals(200, response.statusCode())
		assertEquals(mapper.readTree(http(port, "GET", "/inquiries", token = token).body())[0], mapper.readTree(response.body()), "목록의 원소와 같다")

		for (path in listOf("/inquiries/$notMine", "/inquiries/${UUID.randomUUID()}", "/inquiries/not-a-uuid", "/inquiries/1")) {
			val missing = http(port, "GET", path, token = token)
			assertEquals(404, missing.statusCode(), path)
			assertEquals("""{"error":"NOT_FOUND"}""", missing.body())
		}
		assertEquals(200, http(port, "GET", "/inquiries/$notMine", token = theirs).statusCode(), "주인은 본다")
		assertEquals("[]", http(port, "GET", "/inquiries", token = otherAccountToken(jdbc, "no-inquiries").second).body(), "문의가 없으면 빈 배열")
	}

	@Test
	fun `세션이 없거나 틀리면 읽기도 401 이다`() {
		val id = seed(devOwner(devToken(port)), 1)
		for (path in listOf("/inquiries", "/inquiries/$id")) {
			for (token in listOf(null, "no-such-token")) {
				val response = http(port, "GET", path, token = token)
				assertEquals(401, response.statusCode(), path)
				assertEquals("""{"error":"UNAUTHORIZED"}""", response.body())
			}
		}
	}

	@Test
	fun `계정을 지우면 그 계정의 문의만 지워진다`() {
		val mine = devToken(port)
		val (other, theirs) = otherAccountToken(jdbc, "someone-else")
		assertEquals(201, post(legacyInquiry(replyEmail = "me@example.com"), mine).statusCode())
		assertEquals(201, post(inquiry(), mine).statusCode())
		assertEquals(201, post(inquiry(), theirs).statusCode())
		assertEquals(3, rows())

		assertEquals(204, http(port, "DELETE", "/account", token = mine).statusCode())

		assertEquals(1, rows())
		assertEquals(1, jdbc.queryForObject("select count(*) from inquiries where owner_id = ?", Int::class.java, other))

		assertEquals(204, http(port, "DELETE", "/account", token = theirs).statusCode())
		assertEquals(0, rows())
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `문의 글과 이메일은 로그에도 오류 응답에도 없다`(output: CapturedOutput) {
		val secretBody = "아무도몰라야하는문의글 입니다 가나다라"
		val secretEmail = "hidden-reply@secret-domain.example"
		val responses = listOf(
			post(legacyInquiry("BUG", secretBody, secretEmail)), // 저장되는 길(옛 앱 — 이메일은 버린다)
			post(legacyInquiry("PAYMENT", secretBody, secretEmail)), // 종류가 틀림
			post(legacyInquiry("BUG", secretBody.repeat(200), secretEmail)), // 글이 너무 김
			post(inquiry("BUG", secretBody.repeat(60))), // 새 앱의 글이 너무 김
			post("""{"category":"BUG","body":"$secretBody","replyEmail":"$secretEmail""""), // JSON 이 깨짐
			post("""{"category":"BUG","body":["$secretBody"],"replyEmail":"$secretEmail"}"""), // 타입이 틀림
			post("""{"category":"BUG","body":"$secretBody \u0000","replyEmail":"$secretEmail"}"""),
			http(port, "GET", "/inquiries", token = devToken(port)), // 읽기
		)
		assertEquals(listOf(201, 400, 400, 400, 400, 400, 400, 200), responses.map { it.statusCode() })
		assertNull(jdbc.queryForList("select id from inquiries offset 1").firstOrNull(), "한 건만 저장된다")

		val answered = responses.dropLast(1).joinToString("\n") { it.body() }
		for (secret in listOf("아무도몰라야하는문의글", "가나다라", "hidden-reply", "secret-domain")) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
			assertFalse(answered.contains(secret), "응답에 '$secret' 이 있다")
		}
		assertFalse(responses.last().body().contains("secret-domain"), "이메일은 저장하지 않으므로 읽기에도 없다")
		assertTrue(output.all.contains("event=inquiry_created category=BUG"), "저장했다는 이벤트는 종류 코드만 남긴다")
	}
}

/** 계정별 제한. 설정을 건드리지 않아 기본값(한 시간에 5건)을 본다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class InquiryRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Test
	fun `한 계정의 여섯 번째 문의는 429 이고 다른 계정과 틀린 요청은 세지 않는다`() {
		jdbc.update("delete from accounts")
		val mine = devToken(port)
		val (other, theirs) = otherAccountToken(jdbc, "someone-else")
		fun post(token: String, body: String = inquiry()) = http(port, "POST", "/inquiries", body, token)

		// 틀린 요청은 한도를 쓰지 않는다
		repeat(7) { assertEquals(400, post(mine, inquiry(body = "짧다")).statusCode()) }
		repeat(5) { assertEquals(201, post(mine).statusCode(), "${it + 1}번째") }

		val limited = post(mine)
		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
		assertEquals(429, post(devToken(port)).statusCode(), "같은 계정의 다른 세션도 같이 센다")
		assertEquals(5, jdbc.queryForObject("select count(*) from inquiries", Int::class.java), "막힌 문의는 저장하지 않는다")

		assertEquals(201, post(theirs).statusCode(), "다른 계정은 막히지 않는다")
		assertEquals(1, jdbc.queryForObject("select count(*) from inquiries where owner_id = ?", Int::class.java, other))
		assertEquals(404, http(port, "GET", "/profile", token = mine).statusCode(), "다른 엔드포인트는 막지 않는다(프로필이 없어 404)")
	}
}
