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

/** 문의 요청 본문. 값은 JSON 글자로 옮겨 넣는다(따옴표·줄바꿈이 있어도 된다). */
private fun inquiry(category: String = "BUG", body: String = BODY, replyEmail: String? = null): String =
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
		val response = post(inquiry("READING", "  $BODY \n", "  me@example.com "), token)

		assertEquals(201, response.statusCode())
		val json = mapper.readTree(response.body())
		assertEquals(listOf("id"), json.propertyNames().toList(), "응답에는 id 만 있다")
		val id = UUID.fromString(json["id"].asString())

		val row = jdbc.queryForMap("select * from inquiries")
		assertEquals(
			setOf("id", "owner_id", "category", "body", "reply_email", "created_at", "status", "admin_memo", "handled_by", "handled_at"),
			row.keys,
			"정해진 것만 저장한다(기기 정보·주소 없음). 뒤의 넷은 관리자가 처리하며 적는 칸이다(결정 014)",
		)
		assertEquals("NEW", row["status"])
		assertEquals(listOf(null, null, null), listOf(row["admin_memo"], row["handled_by"], row["handled_at"]))
		assertEquals(id, row["id"])
		assertEquals(jdbc.queryForObject("select account_id from sessions limit 1", UUID::class.java), row["owner_id"], "소유자는 세션의 계정이다")
		assertEquals("READING", row["category"])
		assertEquals(BODY, row["body"], "앞뒤 공백을 떼고 저장한다")
		assertEquals("me@example.com", row["reply_email"])
		assertTrue(row["created_at"] != null)
	}

	@Test
	fun `종류 다섯 가지를 모두 받는다`() {
		for (category in listOf("ACCOUNT", "READING", "SYNASTRY", "BUG", "OTHER")) {
			assertEquals(201, post(inquiry(category)).statusCode(), category)
		}
		assertEquals(
			listOf("ACCOUNT", "BUG", "OTHER", "READING", "SYNASTRY"),
			jdbc.queryForList("select category from inquiries order by category", String::class.java),
		)
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
	fun `글은 앞뒤 공백을 뗀 10자부터 2000자까지다 (한글 한 글자가 1)`() {
		assertRejected("INVALID_BODY", inquiry(body = "가".repeat(9)))
		assertRejected("INVALID_BODY", inquiry(body = "   " + "가".repeat(9) + "\n\n  "))
		assertRejected("INVALID_BODY", inquiry(body = "가".repeat(2001)))
		assertRejected("INVALID_BODY", inquiry(body = ""))
		assertRejected("INVALID_BODY", inquiry(body = " ".repeat(50)))
		assertRejected("INVALID_BODY", """{"category":"BUG"}""")
		assertRejected("INVALID_BODY", """{"category":"BUG","body":null}""")

		assertEquals(201, post(inquiry(body = "가".repeat(10))).statusCode())
		assertEquals(201, post(inquiry(body = "가".repeat(2000))).statusCode(), "한글 2000자는 바이트로 6000 이지만 받는다")
		assertEquals(201, post(inquiry(body = "  " + "가".repeat(2000) + "  ")).statusCode(), "공백을 떼면 2000자다")
		assertEquals(
			listOf(10, 2000, 2000),
			jdbc.queryForList("select char_length(body) from inquiries order by created_at", Int::class.java),
		)
	}

	@Test
	fun `글자 수는 코드 포인트로 센다 (이모지 한 개가 1)`() {
		assertRejected("INVALID_BODY", inquiry(body = "😀".repeat(9)))
		assertRejected("INVALID_BODY", inquiry(body = "😀".repeat(2001)))
		assertEquals(201, post(inquiry(body = "😀".repeat(10))).statusCode())
		assertEquals(201, post(inquiry(body = "😀".repeat(2000))).statusCode())
	}

	@Test
	fun `글 가운데의 줄바꿈과 따옴표는 그대로 저장한다`() {
		val body = "첫 줄입니다.\n\n둘째 줄 \"따옴표\" 와 '; drop table inquiries; --"
		assertEquals(201, post(inquiry(body = body)).statusCode())
		assertEquals(body, jdbc.queryForObject("select body from inquiries", String::class.java))
	}

	@Test
	fun `이메일이 없거나 null 이거나 빈 글이면 없는 것으로 저장한다`() {
		val none = listOf(
			"""{"category":"BUG","body":"$BODY"}""",
			"""{"category":"BUG","body":"$BODY","replyEmail":null}""",
			"""{"category":"BUG","body":"$BODY","replyEmail":""}""",
			"""{"category":"BUG","body":"$BODY","replyEmail":"   "}""",
			"""{"category":"BUG","body":"$BODY","replyEmail":"\t\n"}""",
		)
		for (body in none) assertEquals(201, post(body).statusCode(), body)

		assertEquals(none.size, rows())
		assertEquals(none.size, jdbc.queryForObject("select count(*) from inquiries where reply_email is null", Int::class.java))
	}

	@Test
	fun `모양이 맞는 이메일은 받고 앞뒤 공백을 떼어 저장한다`() {
		val longest = "a".repeat(254 - "@example.com".length) + "@example.com"
		for (email in listOf("me@example.com", "first.last+tag@sub.example.co.kr", " me2@example.com\n", longest)) {
			assertEquals(201, post(inquiry(replyEmail = email)).statusCode(), email)
		}
		assertEquals(
			setOf("me@example.com", "first.last+tag@sub.example.co.kr", "me2@example.com", longest),
			jdbc.queryForList("select reply_email from inquiries", String::class.java).toSet(),
		)
	}

	@Test
	fun `모양이 틀린 이메일은 INVALID_EMAIL`() {
		val tooLong = "a".repeat(255 - "@example.com".length) + "@example.com"
		val bad = listOf(
			"plainaddress", "@example.com", "me@", "me@example", "me@@example.com", "me@ex@ample.com",
			"me @example.com", "me@exam ple.com", "me@example.com extra", "me\t@example.com", tooLong,
		)
		for (email in bad) assertRejected("INVALID_EMAIL", inquiry(replyEmail = email))
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
			"""{"category":"BUG","body":"$BODY","replyEmail":1}""",
			"""{"category":"BUG","body":"$BODY","replyEmail":["me@example.com"]}""",
			"""{"category":"BUG","body":"$BODY","replyEmail":{"v":"me@example.com"}}""",
			"""{"category":"BUG","body":"$BODY","replyEmail":false}""",
		)
		for (body in bad) assertRejected("INVALID_REQUEST", body)
	}

	@Test
	fun `DB 가 받지 못하는 글자(NUL)가 있어도 500 이 아니다`() {
		assertRejected("INVALID_BODY", """{"category":"BUG","body":"문의합니다 \u0000 확인해 주세요"}""")
		assertRejected("INVALID_EMAIL", """{"category":"BUG","body":"$BODY","replyEmail":"me\u0000@example.com"}""")
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

	@Test
	fun `사용자가 문의를 읽는 경로는 없다`() {
		val token = devToken(port)
		val id = mapper.readTree(post(inquiry(), token).body())["id"].asString()

		for (path in listOf("/inquiries", "/inquiries/$id")) {
			val response = http(port, "GET", path, token = token)
			assertTrue(response.statusCode() in listOf(404, 405), "$path → ${response.statusCode()}")
			assertFalse(response.body().contains(BODY))
		}
	}

	@Test
	fun `계정을 지우면 그 계정의 문의만 지워진다`() {
		val mine = devToken(port)
		val (other, theirs) = otherAccountToken(jdbc, "someone-else")
		assertEquals(201, post(inquiry(replyEmail = "me@example.com"), mine).statusCode())
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
		val badEmail = "hidden-bad-reply@@secret-domain.example"
		val responses = listOf(
			post(inquiry("BUG", secretBody, secretEmail)), // 저장되는 길
			post(inquiry("PAYMENT", secretBody, secretEmail)), // 종류가 틀림
			post(inquiry("BUG", secretBody.repeat(200), secretEmail)), // 글이 너무 김
			post(inquiry("BUG", secretBody, badEmail)), // 이메일이 틀림
			post("""{"category":"BUG","body":"$secretBody","replyEmail":"$secretEmail""""), // JSON 이 깨짐
			post("""{"category":"BUG","body":["$secretBody"],"replyEmail":"$secretEmail"}"""), // 타입이 틀림
			post("""{"category":"BUG","body":"$secretBody","replyEmail":{"v":"$secretEmail"}}"""),
			post("""{"category":"BUG","body":"$secretBody \u0000","replyEmail":"$secretEmail"}"""),
		)
		assertEquals(listOf(201, 400, 400, 400, 400, 400, 400, 400), responses.map { it.statusCode() })
		assertNull(jdbc.queryForList("select id from inquiries offset 1").firstOrNull(), "한 건만 저장된다")

		val answered = responses.joinToString("\n") { it.body() }
		for (secret in listOf("아무도몰라야하는문의글", "가나다라", "hidden-reply", "hidden-bad-reply", "secret-domain")) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
			assertFalse(answered.contains(secret), "응답에 '$secret' 이 있다")
		}
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
