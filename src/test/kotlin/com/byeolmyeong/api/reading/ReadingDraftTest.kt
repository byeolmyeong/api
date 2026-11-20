package com.byeolmyeong.api.reading

import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Houses
import com.byeolmyeong.api.chart.PlanetPosition
import com.byeolmyeong.api.chart.Sign
import com.byeolmyeong.api.chart.degreeInSign
import com.byeolmyeong.api.chart.signOf
import com.byeolmyeong.api.generation.PlacementBasis
import com.byeolmyeong.api.generation.Reason
import com.byeolmyeong.api.generation.ReasonWhen
import com.byeolmyeong.api.generation.RulerBasis
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 생성기가 돌려준 JSON 을 글로 읽는 곳과, 고른 근거 id 를 구조로 바꾸는 곳. 응답은 외부 입력이라 필드와 타입을 본다. */
class ReadingDraftTest {

	private val mapper = JsonMapper()

	private fun parse(json: String) = parseReadingDraft(mapper.readTree(json))

	@Test
	fun `모양이 맞으면 글로 읽는다`() {
		val draft = ReadingDraft(
			headline = "총평",
			sections = (1..5).map { DraftSection("제목$it", "본문$it", listOf("P-SUN", "R-SUN")) },
			strengths = listOf("하나", "둘", "셋"),
			cautions = listOf("넷", "다섯"),
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

	private fun position(body: Body, longitude: Double, house: Int?) =
		PlanetPosition(body, longitude, signOf(longitude), degreeInSign(longitude), retrograde = false, speed = 1.0, house = house)

	// 해 쌍둥이 6하우스(수성이 다스림), 달 사수 11하우스, 상승점 사자
	private val chart = Chart(
		timeKnown = true,
		positions = listOf(position(Body.SUN, 75.0, 6), position(Body.MOON, 250.0, 11)),
		points = emptyList(),
		houses = Houses(ascendant = 125.0, midheaven = 35.0, vertex = 0.0, cusps = emptyList()),
		aspects = emptyList(),
		moonSignMayChange = false,
	)

	@Test
	fun `고른 근거 id 를 태어날 때의 구조로 바꾸고 옛 근거 낱말을 함께 만든다`() {
		val draft = ReadingDraft("총평", listOf(DraftSection("제목", "본문", listOf("P-SUN", "R-SUN"))), listOf("하나", "둘", "셋"), listOf("넷", "다섯"))

		val body = readingBody(draft, chart)
		val reason = Reason(ReasonWhen.BIRTH, listOf(PlacementBasis("SUN", Sign.GEMINI, 6), RulerBasis("SUN", Body.MERCURY)))
		assertEquals(ReadingBody("총평", listOf(BodySection("제목", "본문", reason)), listOf("하나", "둘", "셋"), listOf("넷", "다섯")), body)

		val reading = body.toReading(createdAt = "2026-11-20T03:04:05Z", shareId = "share")
		assertEquals(listOf(ReadingSection("제목", "본문", reason, listOf("태양", "쌍둥이", "6하우스", "수성"))), reading.sections)
		assertEquals("2026-11-20T03:04:05Z", reading.createdAt)
		assertEquals(
			listOf("headline", "sections", "strengths", "cautions", "createdAt", "shareId"),
			mapper.valueToTree<tools.jackson.databind.JsonNode>(reading).propertyNames().toList(),
		)
	}
}
