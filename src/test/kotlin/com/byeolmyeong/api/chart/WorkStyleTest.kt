package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 나의 일 방식 막대 셋(결정 038). 기준값은 공식 초안 docs/drafts/2026-11-20-work-style-formula.md 의 "6. 계산 예"
 * (소유자 2026-11-20 "초안 그대로 써"). 가짜 차트다 — 사람의 출생 정보가 아니다.
 */
class WorkStyleTest {

	private fun position(body: Body, sign: Sign, house: Int?): PlanetPosition {
		val longitude = sign.ordinal * 30.0 + 15.0
		return PlanetPosition(body, longitude, sign, degreeInSign(longitude), retrograde = false, speed = 1.0, house = house)
	}

	/** [placements] 는 천체 → (별자리, 하우스). [angles] 가 있으면 시각을 아는 차트(상승점 · 중천의 별자리). */
	private fun chart(placements: Map<Body, Pair<Sign, Int?>>, angles: Pair<Sign, Sign>? = null): Chart {
		val positions = Body.entries.map { body -> placements.getValue(body).let { (sign, house) -> position(body, sign, house) } }
		return Chart(
			timeKnown = angles != null,
			positions = positions,
			points = emptyList(),
			houses = angles?.let { (asc, mc) -> Houses(ascendant = asc.ordinal * 30.0 + 10.0, midheaven = mc.ordinal * 30.0 + 10.0, vertex = 0.0, cusps = emptyList()) },
			aspects = emptyList(),
			moonSignMayChange = false,
		)
	}

	// 예 1 — 해 물병 5 · 달 염소 4 · 수성 물병 5 · 금성 물병 5 · 화성 염소 4 · 목성 사자 11 · 토성 황소 8 · 천왕성 염소 4 · 해왕성 염소 4 · 명왕성 전갈 2
	private val example1 = mapOf(
		Body.SUN to (Sign.AQUARIUS to 5),
		Body.MOON to (Sign.CAPRICORN to 4),
		Body.MERCURY to (Sign.AQUARIUS to 5),
		Body.VENUS to (Sign.AQUARIUS to 5),
		Body.MARS to (Sign.CAPRICORN to 4),
		Body.JUPITER to (Sign.LEO to 11),
		Body.SATURN to (Sign.TAURUS to 8),
		Body.URANUS to (Sign.CAPRICORN to 4),
		Body.NEPTUNE to (Sign.CAPRICORN to 4),
		Body.PLUTO to (Sign.SCORPIO to 2),
	)

	private fun withoutHouses(placements: Map<Body, Pair<Sign, Int?>>) = placements.mapValues { (_, value) -> value.first to null }

	@Test
	fun `예 1 시각 앎 - 상승점 천칭 · 중천 게`() {
		assertEquals(WorkStyle(together = 51, flexible = 36, fast = 44), computeWorkStyle(chart(example1, Sign.LIBRA to Sign.CANCER)))
	}

	@Test
	fun `예 1 을 시각 모름으로 - 하우스 · 상승점 · 중천을 빼고 가중치를 다시 나눈다`() {
		assertEquals(WorkStyle(together = 61, flexible = 29, fast = 40), computeWorkStyle(chart(withoutHouses(example1))))
	}

	@Test
	fun `예 2 불이 많은 차트(시각 모름)`() {
		val example2 = mapOf(
			Body.SUN to (Sign.ARIES to null),
			Body.MOON to (Sign.GEMINI to null),
			Body.MERCURY to (Sign.ARIES to null),
			Body.VENUS to (Sign.PISCES to null),
			Body.MARS to (Sign.LEO to null),
			Body.JUPITER to (Sign.SAGITTARIUS to null),
			Body.SATURN to (Sign.VIRGO to null),
			Body.URANUS to (Sign.AQUARIUS to null),
			Body.NEPTUNE to (Sign.AQUARIUS to null),
			Body.PLUTO to (Sign.SAGITTARIUS to null),
		)
		assertEquals(WorkStyle(together = 69, flexible = 65, fast = 77), computeWorkStyle(chart(example2)))
	}

	@Test
	fun `모든 점이 한 별자리에 몰려도 세 값은 10~90 이다`() {
		for (sign in Sign.entries) {
			for (house in listOf(1, 4, 6)) {
				val crowded = Body.entries.associateWith { sign to house }
				for (style in listOf(computeWorkStyle(chart(crowded, sign to sign)), computeWorkStyle(chart(withoutHouses(crowded))))) {
					for (value in listOf(style.together, style.flexible, style.fast)) assertTrue(value in 10..90, "$sign $house: $style")
				}
			}
		}
		// 양자리 · 1하우스에 모두: 불 · 시작 · 일하는 별 불 · 빠른 하우스라 "빠르게" 끝에서 자른다
		assertEquals(90, computeWorkStyle(chart(Body.entries.associateWith { Sign.ARIES to 1 }, Sign.ARIES to Sign.ARIES)).fast)
	}

	@Test
	fun `같은 차트는 늘 같은 값이고 시각을 모르면 하우스를 읽지 않는다`() {
		val known = chart(example1, Sign.LIBRA to Sign.CANCER)
		assertEquals(computeWorkStyle(known), computeWorkStyle(known))

		// 시각 모름 차트에 하우스가 적혀 있어도(계산이 채우지 않는 값) 결과가 같다
		val unknown = chart(withoutHouses(example1))
		val unknownWithStrayHouses = chart(example1.mapValues { (_, value) -> value.first to 12 })
		assertEquals(computeWorkStyle(unknown), computeWorkStyle(unknownWithStrayHouses))
	}
}
