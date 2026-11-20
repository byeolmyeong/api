package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Houses
import com.byeolmyeong.api.chart.PlanetPosition
import com.byeolmyeong.api.chart.Sign
import com.byeolmyeong.api.chart.computeAspects
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.degreeInSign
import com.byeolmyeong.api.chart.signOf
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 근거 후보(결정 031): 서버가 차트에서 만들고 모델은 id 로 고른다. */
class BasisCandidatesTest {

	private fun position(body: Body, longitude: Double, house: Int?) =
		PlanetPosition(body, longitude, signOf(longitude), degreeInSign(longitude), retrograde = false, speed = 1.0, house = house)

	// 해 물병 5하우스 · 달 염소 4하우스 · 수성 · 금성 물병 5하우스(5하우스에 셋) · 토성 황소(해와 정확한 사분)
	// 상승점 천칭 · 중천 게. 달은 상승점과 사분(오차 3) · 중천과 충(오차 3), 해는 상승점과 삼분(후보에 넣지 않는 종류).
	private val longitudes = listOf(
		Triple(Body.SUN, 305.0, 5),
		Triple(Body.MOON, 283.0, 4),
		Triple(Body.MERCURY, 310.0, 5),
		Triple(Body.VENUS, 315.0, 5),
		Triple(Body.MARS, 20.0, 7),
		Triple(Body.JUPITER, 95.0, 10),
		Triple(Body.SATURN, 35.0, 8),
		Triple(Body.URANUS, 200.0, 1),
		Triple(Body.NEPTUNE, 210.0, 1),
		Triple(Body.PLUTO, 150.0, 12),
	)

	private fun chart(timeKnown: Boolean, sunLongitude: Double = 305.0): Chart {
		val positions = longitudes.map { (body, longitude, house) ->
			position(body, if (body == Body.SUN) sunLongitude else longitude, house.takeIf { timeKnown })
		}
		return Chart(
			timeKnown = timeKnown,
			positions = positions,
			points = emptyList(),
			houses = if (timeKnown) Houses(ascendant = 190.0, midheaven = 100.0, vertex = 250.0, cusps = emptyList()) else null,
			aspects = computeAspects(positions),
			moonSignMayChange = false,
		)
	}

	private val known = chart(timeKnown = true)
	private val unknown = chart(timeKnown = false)

	private fun List<BasisCandidate>.item(id: String): BasisItem = single { it.id == id }.item

	@Test
	fun `후보 id 꼴과 구조`() {
		val candidates = basisCandidates(known)

		assertEquals(PlacementBasis("SUN", Sign.AQUARIUS, 5), candidates.item("P-SUN"))
		assertEquals(PlacementBasis("ASC", Sign.LIBRA), candidates.item("P-ASC"))
		assertEquals(PlacementBasis("MC", Sign.CANCER), candidates.item("P-MC"))
		assertEquals(ClusterBasis(5, listOf(Body.SUN, Body.MERCURY, Body.VENUS)), candidates.item("C-5"))
		assertEquals(AspectBasis("SATURN", "SUN", AspectType.SQUARE), candidates.item("A-SATURN-SUN-SQUARE"))
		assertEquals(AspectBasis("MOON", "ASC", AspectType.SQUARE), candidates.item("A-MOON-ASC-SQUARE"))
		assertEquals(AspectBasis("MOON", "MC", AspectType.OPPOSITION), candidates.item("A-MOON-MC-OPPOSITION"))
		assertEquals(RulerBasis("SUN", Body.SATURN), candidates.item("R-SUN"))
		assertEquals(RulerBasis("ASC", Body.VENUS), candidates.item("R-ASC"))
	}

	@Test
	fun `염소자리 달은 토성이 다스린다`() {
		assertEquals(RulerBasis("MOON", Body.SATURN), basisCandidates(known).item("R-MOON"))
		assertEquals(RulerBasis("MOON", Body.SATURN), basisCandidates(unknown).item("R-MOON"))
	}

	@Test
	fun `다스리는 별이 그 별 자신이면 후보가 아니다`() {
		val leoSun = basisCandidates(chart(timeKnown = true, sunLongitude = 130.0))
		assertTrue(leoSun.none { it.id == "R-SUN" }, "사자자리 해는 해가 다스린다")
		assertTrue(leoSun.any { it.id == "R-MOON" })
	}

	@Test
	fun `순서는 놓인 자리 하우스 모임 각도(정확한 것부터) 다스리는 별이다`() {
		val ids = basisCandidates(known).map { it.id }

		val placements = Body.entries.map { "P-${it.name}" } + listOf("P-ASC", "P-MC")
		assertEquals(placements, ids.take(placements.size))
		assertEquals("C-5", ids[placements.size])
		val aspects = ids.filter { it.startsWith("A-") }
		assertEquals("A-SATURN-SUN-SQUARE", aspects.first(), "오차 0 이 맨 앞")
		assertEquals(placements.size + 1, ids.indexOf(aspects.first()), "모임 다음이 각도")
		assertEquals(listOf("R-SUN", "R-MOON", "R-ASC"), ids.takeLast(3))
	}

	@Test
	fun `각도 후보의 오차는 앞에서부터 커진다`() {
		val orbOf = known.aspects.associate { "A-${it.b.name}-${it.a.name}-${it.type.name}" to it.orb } +
			mapOf("A-MOON-ASC-SQUARE" to 3.0, "A-MOON-MC-OPPOSITION" to 3.0)
		val orbs = basisCandidates(known).filter { it.id.startsWith("A-") }.map { orbOf.getValue(it.id) }
		assertEquals(orbs.sorted(), orbs, "정확한 각도에 가까운 것부터")
	}

	@Test
	fun `상승점 중천과의 각도는 해 달의 합 충 사분만 후보다`() {
		val angleAspects = basisCandidates(known).map { it.item }.filterIsInstance<AspectBasis>().filter { it.b in setOf("ASC", "MC") }
		assertTrue(angleAspects.isNotEmpty())
		for (aspect in angleAspects) {
			assertTrue(aspect.a in setOf("SUN", "MOON"), aspect.toString())
			assertTrue(aspect.type in setOf(AspectType.CONJUNCTION, AspectType.OPPOSITION, AspectType.SQUARE), aspect.toString())
		}
		assertTrue(angleAspects.none { it.a == "SUN" && it.b == "ASC" }, "해와 상승점은 삼분이라 넣지 않는다")
	}

	@Test
	fun `하우스 모임은 천체 셋 이상인 하우스만이다`() {
		val clusters = basisCandidates(known).map { it.item }.filterIsInstance<ClusterBasis>()
		assertEquals(listOf(5), clusters.map { it.house }, "1하우스는 둘뿐이다")
		assertTrue(clusters.all { it.bodies.size >= 3 })
	}

	@Test
	fun `시각을 모르면 하우스 상승점 중천 하우스 모임 후보가 없다`() {
		val candidates = basisCandidates(unknown)
		val items = candidates.map { it.item }

		assertEquals(0, items.filterIsInstance<PlacementBasis>().count { it.house != null })
		assertEquals(0, items.filterIsInstance<ClusterBasis>().size)
		assertEquals(0, candidates.count { it.id.contains("ASC") || it.id.contains("MC") || it.id.startsWith("C-") })
		assertEquals(Body.entries.map { "P-${it.name}" }, candidates.filter { it.id.startsWith("P-") }.map { it.id })
	}

	@Test
	fun `실제 차트에서도 id 는 겹치지 않고 시각 모름에는 하우스가 없다`() {
		// 1990-03-15 14:30 서울(UTC 05:30)
		val utc = Instant.parse("1990-03-15T05:30:00Z")
		for (timeKnown in listOf(true, false)) {
			val candidates = basisCandidates(computeChart(utc, 37.5663, 126.9019, timeKnown))
			assertEquals(candidates.size, candidates.map { it.id }.toSet().size, "id 겹침 0 (timeKnown=$timeKnown)")
			if (!timeKnown) {
				val json = jacksonObjectMapper().writeValueAsString(candidates.map { it.item })
				for (word in listOf("house", "ASC", "MC", "CLUSTER")) assertFalse(json.contains(word), "시각 모름에 '$word': $json")
			}
		}
	}

	@Test
	fun `옛 근거 낱말은 그 차트의 근거 말 안에서 만든다`() {
		val utc = Instant.parse("1990-03-15T05:30:00Z")
		for (timeKnown in listOf(true, false)) {
			val chart = computeChart(utc, 37.5663, 126.9019, timeKnown)
			val vocabulary = basisVocabulary(chart).toSet()
			for (candidate in basisCandidates(chart)) {
				val words = basisWords(Reason(ReasonWhen.BIRTH, listOf(candidate.item)))
				assertTrue(words.isNotEmpty() && vocabulary.containsAll(words), "${candidate.id} → $words")
			}
		}
		val known = basisCandidates(known)
		assertEquals(listOf("토성", "태양"), basisWords(Reason(ReasonWhen.BIRTH, listOf(known.item("A-SATURN-SUN-SQUARE")))))
		assertEquals(
			listOf("태양", "물병", "5하우스", "수성", "금성", "토성"),
			basisWords(Reason(ReasonWhen.BIRTH, listOf(known.item("P-SUN"), known.item("C-5"), known.item("R-SUN")))),
			"겹치는 말은 한 번",
		)
	}

	@Test
	fun `지점은 근거 후보가 아니다`() {
		val utc = Instant.parse("1990-03-15T05:30:00Z")
		val json = jacksonObjectMapper().writeValueAsString(basisCandidates(computeChart(utc, 37.5663, 126.9019, timeKnown = true)).map { it.item })
		for (word in listOf("NORTH_NODE", "LILITH", "FORTUNE", "VERTEX")) assertFalse(json.contains(word), word)
	}

	@Test
	fun `근거의 JSON 은 kind 로 나뉘고 값이 없는 키는 보내지 않는다`() {
		val mapper = jacksonObjectMapper()
		val items: List<BasisItem> = listOf(
			PlacementBasis("SUN", Sign.AQUARIUS, 5),
			PlacementBasis("MOON", Sign.CAPRICORN),
			ClusterBasis(5, listOf(Body.SUN, Body.MERCURY, Body.VENUS)),
			AspectBasis("SATURN", "SUN", AspectType.SQUARE),
			RulerBasis("MOON", Body.SATURN),
		)
		val reason = Reason(ReasonWhen.BIRTH, items.take(2))

		assertEquals(
			"""[{"kind":"PLACEMENT","body":"SUN","sign":"AQUARIUS","house":5},{"kind":"PLACEMENT","body":"MOON","sign":"CAPRICORN"},""" +
				"""{"kind":"CLUSTER","house":5,"bodies":["SUN","MERCURY","VENUS"]},{"kind":"ASPECT","a":"SATURN","b":"SUN","type":"SQUARE"},""" +
				"""{"kind":"RULER","body":"MOON","ruler":"SATURN"}]""",
			mapper.writerFor(mapper.typeFactory.constructCollectionType(List::class.java, BasisItem::class.java)).writeValueAsString(items),
		)
		val json = mapper.writeValueAsString(reason)
		assertEquals("""{"when":"BIRTH","items":[{"kind":"PLACEMENT","body":"SUN","sign":"AQUARIUS","house":5},{"kind":"PLACEMENT","body":"MOON","sign":"CAPRICORN"}]}""", json)
		assertEquals(reason, mapper.readValue(json, Reason::class.java), "저장본을 다시 읽는다")
	}

	@Test
	fun `이 풀이에 쓰인 별은 문단 근거에서 겹치지 않게 앞에서부터 셋까지다`() {
		val candidates = basisCandidates(known)
		fun reason(vararg ids: String) = Reason(ReasonWhen.BIRTH, ids.map { candidates.item(it) })

		val stars = starsOf(
			listOf(
				reason("P-SUN", "A-SATURN-SUN-SQUARE"),
				reason("P-SUN"),
				reason("R-SUN"), // 해는 이미 있다
				reason("C-5"),
				reason("R-MOON"),
			),
		)

		assertEquals(
			listOf(Star(body = "SUN", sign = Sign.AQUARIUS), Star(body = "SATURN"), Star(house = 5, count = 3)),
			stars,
		)
		assertEquals(listOf(Star(body = "MOON")), starsOf(listOf(reason("R-MOON"))), "다스리는 별은 앞쪽 별")
		assertEquals(
			"""{"body":"SUN","sign":"AQUARIUS"}""",
			jacksonObjectMapper().writeValueAsString(stars.first()),
			"값이 없는 키는 보내지 않는다",
		)
	}

	@Test
	fun `차트 글 끝의 근거 후보 블록`() {
		val candidates = basisCandidates(known)
		val facts = chartFacts(known, candidates)

		val block = facts.substringAfter("[근거 후보]\n").lines()
		assertEquals(candidates.map { it.id }, block.map { it.substringBefore(":") }, "후보마다 한 줄, 순서 그대로")
		assertTrue(block.contains("P-SUN: 태양 · 물병 · 5하우스"), block.toString())
		assertTrue(block.contains("C-5: 5하우스에 모인 천체 — 태양, 수성, 금성"), block.toString())
		assertTrue(block.contains("A-SATURN-SUN-SQUARE: 토성 – 태양 · 직각으로 부딪힘"), block.toString())
		assertTrue(block.contains("R-MOON: 달이 놓인 별자리(염소)를 다스리는 별 — 토성"), block.toString())
		assertTrue(block.contains("R-ASC: 상승점의 별자리(천칭)를 다스리는 별 — 금성"), block.toString())
		assertFalse(facts.contains("[근거로 쓸 수 있는 말]"), "근거 후보를 주면 옛 근거 말 목록은 주지 않는다")

		assertTrue(chartFacts(known).contains("[근거로 쓸 수 있는 말]"), "후보 없이 부르는 옛 글은 그대로")
		assertFalse(chartFacts(known).contains("[근거 후보]"))

		val unknownFacts = chartFacts(unknown, basisCandidates(unknown))
		for (word in listOf("하우스", "상승점", "중천", "행운점", "버텍스")) assertFalse(unknownFacts.contains(word), "시각 모름에 '$word'")
	}
}
