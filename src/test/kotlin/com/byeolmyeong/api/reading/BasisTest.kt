package com.byeolmyeong.api.reading

import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.ChartPoint
import com.byeolmyeong.api.chart.Houses
import com.byeolmyeong.api.chart.PlanetPosition
import com.byeolmyeong.api.chart.PointKind
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.degreeInSign
import com.byeolmyeong.api.chart.signOf
import com.byeolmyeong.api.generation.basisVocabulary
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasisTest {

	private fun position(body: Body, longitude: Double, house: Int?) =
		PlanetPosition(body, longitude, signOf(longitude), degreeInSign(longitude), retrograde = false, speed = 1.0, house = house)

	private fun point(kind: PointKind, longitude: Double, house: Int?) =
		ChartPoint(kind, longitude, signOf(longitude), degreeInSign(longitude), house)

	// 태양 쌍둥이 6하우스, 달 사수 11하우스, 북쪽 교점 물병 8하우스, 상승점 사자, 중천 황소
	private val known = Chart(
		timeKnown = true,
		positions = listOf(position(Body.SUN, 75.0, 6), position(Body.MOON, 250.0, 11)),
		points = listOf(point(PointKind.NORTH_NODE, 310.0, 8)),
		houses = Houses(ascendant = 125.0, midheaven = 35.0, vertex = 0.0, cusps = emptyList()),
		aspects = emptyList(),
		moonSignMayChange = false,
	)

	private val unknown = Chart(
		timeKnown = false,
		positions = listOf(position(Body.SUN, 75.0, null), position(Body.MOON, 250.0, null)),
		points = listOf(point(PointKind.NORTH_NODE, 310.0, null)),
		houses = null,
		aspects = emptyList(),
		moonSignMayChange = false,
	)

	@Test
	fun `근거로 쓸 수 있는 말은 차트에 있는 요소뿐이다`() {
		assertEquals(
			setOf("태양", "달", "북쪽 교점", "상승점", "중천", "쌍둥이", "사수", "물병", "사자", "황소", "6하우스", "11하우스"),
			basisVocabulary(known).toSet(),
		)
	}

	@Test
	fun `시각을 모르면 하우스와 상승점과 중천이 없다`() {
		assertEquals(setOf("태양", "달", "북쪽 교점", "쌍둥이", "사수", "물병"), basisVocabulary(unknown).toSet())
	}

	@Test
	fun `실제 차트의 근거 말`() {
		// 1990-03-15 14:30 서울(UTC 05:30)
		val utc = Instant.parse("1990-03-15T05:30:00Z")
		val withTime = basisVocabulary(computeChart(utc, 37.5663, 126.9019, timeKnown = true))
		assertTrue(withTime.containsAll(listOf("태양", "달", "수성", "금성", "화성", "목성", "토성", "천왕성", "해왕성", "명왕성")))
		assertTrue(withTime.containsAll(listOf("북쪽 교점", "릴리스", "행운점", "버텍스", "상승점", "중천", "물고기")))
		assertTrue(withTime.any { it.endsWith("하우스") })

		val withoutTime = basisVocabulary(computeChart(utc, 37.5663, 126.9019, timeKnown = false))
		assertTrue(withoutTime.containsAll(listOf("태양", "달", "북쪽 교점", "릴리스", "물고기")))
		assertFalse(withoutTime.any { it.endsWith("하우스") || it in setOf("상승점", "중천", "행운점", "버텍스") }, withoutTime.toString())
	}

	private fun draft(
		headline: String = "총평",
		sections: List<ReadingSection> = (1..5).map { ReadingSection("제목", "본문", listOf("태양", "쌍둥이", "6하우스")) },
		strengths: List<String> = listOf("하나", "둘", "셋"),
		cautions: List<String> = listOf("하나", "둘", "셋"),
	) = ReadingDraft(headline, sections, strengths, cautions)

	@Test
	fun `구조와 근거가 맞으면 통과한다`() {
		assertNull(draftProblem(draft(), known))
		assertNull(draftProblem(draft(sections = (1..4).map { ReadingSection("제목", "본문", listOf("달", "사수")) }), unknown))
	}

	@Test
	fun `차트에 없는 근거는 걸린다`() {
		fun withBasis(vararg basis: String) = draft(sections = (1..5).map { ReadingSection("제목", "본문", basis.toList()) })
		assertEquals("BASIS_NOT_IN_CHART", draftProblem(withBasis("태양", "화성"), known), "없는 천체")
		assertEquals("BASIS_NOT_IN_CHART", draftProblem(withBasis("태양", "게"), known), "아무도 없는 별자리")
		assertEquals("BASIS_NOT_IN_CHART", draftProblem(withBasis("태양", "7하우스"), known), "아무도 없는 하우스")
		assertEquals("BASIS_NOT_IN_CHART", draftProblem(withBasis("태양", "쌍둥이자리"), known), "말이 정확히 같아야 한다")
		assertEquals("BASIS_NOT_IN_CHART", draftProblem(withBasis("SUN"), known))

		val four = (1..4).map { ReadingSection("제목", "본문", listOf("태양", "6하우스")) }
		assertEquals("BASIS_NOT_IN_CHART", draftProblem(draft(sections = four), unknown), "시각 모름에 하우스")
		assertEquals(
			"BASIS_NOT_IN_CHART",
			draftProblem(draft(sections = four.map { it.copy(basis = listOf("상승점")) }), unknown),
			"시각 모름에 상승점",
		)
	}

	@Test
	fun `비었거나 개수가 틀리면 걸린다`() {
		val section = ReadingSection("제목", "본문", listOf("태양"))
		assertEquals("MALFORMED", draftProblem(draft(headline = " "), known))
		assertEquals("MALFORMED", draftProblem(draft(sections = List(4) { section }), known), "시각을 알면 문단 5개")
		assertEquals("MALFORMED", draftProblem(draft(sections = List(5) { section }), unknown), "시각을 모르면 문단 4개")
		assertEquals("MALFORMED", draftProblem(draft(sections = List(5) { section.copy(title = "") }), known))
		assertEquals("MALFORMED", draftProblem(draft(sections = List(5) { section.copy(body = "\n") }), known))
		assertEquals("MALFORMED", draftProblem(draft(sections = List(5) { section.copy(basis = emptyList()) }), known))
		assertEquals("MALFORMED", draftProblem(draft(strengths = listOf("하나", "둘")), known))
		assertEquals("MALFORMED", draftProblem(draft(cautions = listOf("하나", "", "셋")), known))
		assertEquals("MALFORMED", draftProblem(draft(cautions = emptyList()), known))
	}
}
