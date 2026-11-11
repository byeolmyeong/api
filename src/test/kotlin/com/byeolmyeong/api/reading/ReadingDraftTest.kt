package com.byeolmyeong.api.reading

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 생성기가 돌려준 JSON 을 글로 읽는 곳. 응답은 외부 입력이라 필드와 타입을 본다. */
class ReadingDraftTest {

	private val mapper = JsonMapper()

	private fun parse(json: String) = parseReadingDraft(mapper.readTree(json))

	@Test
	fun `모양이 맞으면 글로 읽는다`() {
		val draft = ReadingDraft(
			headline = "총평",
			sections = (1..5).map { ReadingSection("제목$it", "본문$it", listOf("태양", "6하우스")) },
			strengths = listOf("하나", "둘", "셋"),
			cautions = listOf("넷", "다섯", "여섯"),
		)
		assertEquals(draft, parseReadingDraft(mapper.valueToTree(draft)))
	}

	@Test
	fun `필드가 빠졌거나 타입이 틀리면 null 이다`() {
		assertNull(parse("""{"headline":"총평"}"""), "필드가 빠졌다")
		assertNull(parse("""{"headline":1,"sections":"x","strengths":[],"cautions":[]}"""), "타입이 틀리다")
		assertNull(
			parse("""{"headline":"총평","sections":[{"title":"제목","body":"본문","basis":[1]}],"strengths":[],"cautions":[]}"""),
			"근거가 문자열이 아니다",
		)
	}
}
