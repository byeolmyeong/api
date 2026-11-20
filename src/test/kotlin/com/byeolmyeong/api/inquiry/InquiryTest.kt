package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.web.BadRequestException
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val mapper = JsonMapper()

private fun parse(json: String): Inquiry = parseInquiry(mapper.readTree(json))

private fun rejected(json: String): String = assertFailsWith<BadRequestException>(json) { parse(json) }.code

private fun body(length: Int, char: String = "가"): String = char.repeat(length)

/** `POST /inquiries` 의 본문 규칙(결정 039 의 2). 새 앱은 `{ category, body }`, 옛 앱은 `replyEmail` 키를 함께 보낸다. */
class InquiryTest {

	@Test
	fun `새 앱의 종류는 BUG · IDEA · ACCOUNT · ETC 넷이다`() {
		for (category in listOf("BUG", "IDEA", "ACCOUNT", "ETC")) {
			assertEquals(category, parse("""{"category":"$category","body":"${body(10)}"}""").category)
		}
		for (category in listOf("READING", "SYNASTRY", "OTHER", "bug", "", "PAYMENT")) {
			assertEquals("INVALID_CATEGORY", rejected("""{"category":"$category","body":"${body(10)}"}"""), category)
		}
	}

	@Test
	fun `새 앱의 글은 앞뒤 공백을 뗀 10~1000자다`() {
		assertEquals(body(1000), parse("""{"category":"BUG","body":"  ${body(1000)} \n"}""").body)
		assertEquals(body(1000, "😀"), parse("""{"category":"BUG","body":"${body(1000, "😀")}"}""").body, "코드 포인트로 센다")
		assertEquals("INVALID_BODY", rejected("""{"category":"BUG","body":"${body(1001)}"}"""))
		assertEquals("INVALID_BODY", rejected("""{"category":"BUG","body":"${body(1001, "😀")}"}"""))
		assertEquals("INVALID_BODY", rejected("""{"category":"BUG","body":"${body(9)}"}"""))
	}

	@Test
	fun `replyEmail 키가 있으면 옛 앱이라 옛 종류와 2000자까지 받는다`() {
		for (category in listOf("ACCOUNT", "READING", "SYNASTRY", "BUG", "OTHER", "IDEA", "ETC")) {
			assertEquals(category, parse("""{"category":"$category","body":"${body(10)}","replyEmail":null}""").category, category)
		}
		assertEquals(body(2000), parse("""{"category":"OTHER","body":"${body(2000)}","replyEmail":"me@example.com"}""").body)
		assertEquals("INVALID_BODY", rejected("""{"category":"OTHER","body":"${body(2001)}","replyEmail":null}"""))
		assertEquals("INVALID_CATEGORY", rejected("""{"category":"PAYMENT","body":"${body(10)}","replyEmail":null}"""))
	}

	@Test
	fun `replyEmail 은 값과 타입이 무엇이든 읽지 않는다`() {
		for (email in listOf("null", "\"\"", "\"me@example.com\"", "\"not-an-email\"", "1", "false", "[\"a@b.c\"]", "{\"v\":1}", "\"a\\u0000@b.c\"")) {
			assertEquals("BUG", parse("""{"category":"BUG","body":"${body(10)}","replyEmail":$email}""").category, email)
		}
	}

	@Test
	fun `타입이 틀리면 INVALID_REQUEST 가 먼저다`() {
		for (json in listOf("[]", "\"글\"", """{"category":1,"body":"${body(10)}"}""", """{"category":"BUG","body":["${body(10)}"]}""")) {
			assertEquals("INVALID_REQUEST", rejected(json), json)
		}
	}

	@Test
	fun `문의를 보낸 앱은 헤더에서 읽고 모르면 null 이다`() {
		inquiryApp("ios", "1.2.3").let { assertEquals("ios" to "1.2.3", it.platform to it.version) }
		inquiryApp(" Android ", " 2.0 ").let { assertEquals("android" to "2.0", it.platform to it.version) }
		inquiryApp(null, null).let { assertNull(it.platform); assertNull(it.version) }
		inquiryApp("web", "1.2.3.4").let { assertNull(it.platform); assertNull(it.version) }
		inquiryApp("ios", "v1.2").let { assertEquals("ios", it.platform); assertNull(it.version) }
		inquiryApp("", "").let { assertNull(it.platform); assertNull(it.version) }
	}
}
