package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AspectsTest {

	private data class Known(
		val utc: String,
		val a: Body,
		val b: Body,
		val type: AspectType,
		val orb: Double,
		val applying: Boolean,
	)

	// 알려진 각도 5건. 출처: NASA JPL Horizons 황경(앱 저장소 docs/raw/2026-11-10-jpl-horizons-reference.md).
	// 오차 = |두 황경의 차 - 정확한 각도|. 다가가는 중인지는 같은 문서의 하루 뒤 황경으로 오차가 줄었는지 보았다
	// (태양은 하루 뒤 값이 표에 없지만 하루에 약 +1도 순행하므로 방향이 분명하다).
	private val known = listOf(
		// 태양 146.3341380, 명왕성 146.4675066 → 차 0.1334. 태양이 명왕성을 따라잡는 중.
		Known("1955-08-20T03:00:00Z", Body.SUN, Body.PLUTO, AspectType.CONJUNCTION, 0.1334, applying = true),
		// 목성 134.5676386, 토성 225.3117700 → 차 90.7441. 하루 뒤 90.5772 로 줄었다.
		Known("1955-08-20T03:00:00Z", Body.JUPITER, Body.SATURN, AspectType.SQUARE, 0.7441, applying = true),
		// 토성 285.5983638, 명왕성 227.0793833 → 차 58.5190. 하루 뒤 58.6099 로 60 에 가까워졌다.
		Known("1990-01-01T00:00:00Z", Body.SATURN, Body.PLUTO, AspectType.SEXTILE, 1.4810, applying = true),
		// 목성 95.2160871, 천왕성 275.7554118 → 차 180.5393. 하루 뒤 180.7338 로 벌어졌다.
		Known("1990-01-01T00:00:00Z", Body.JUPITER, Body.URANUS, AspectType.OPPOSITION, 0.5393, applying = false),
		// 태양 84.6800622, 천왕성 320.6457778 → 차 124.0343. 태양은 순행, 천왕성은 역행이라 벌어진다.
		Known("2000-06-15T12:00:00Z", Body.SUN, Body.URANUS, AspectType.TRINE, 4.0343, applying = false),
	)

	@Test
	fun `알려진 각도 5건의 종류 오차 방향이 맞는다`() {
		for (expected in known) {
			val aspects = computeAspects(computePositions(Instant.parse(expected.utc)))
			val found = aspects.firstOrNull { it.a == expected.a && it.b == expected.b }
			assertNotNull(found, "${expected.utc} ${expected.a}-${expected.b} 각도가 없다")
			assertEquals(expected.type, found.type, "${expected.a}-${expected.b}")
			assertEquals(expected.orb, found.orb, 0.01, "${expected.a}-${expected.b} 오차")
			assertEquals(expected.applying, found.applying, "${expected.a}-${expected.b} 방향")
		}
	}

	private fun position(body: Body, longitude: Double, speed: Double = 0.0) =
		PlanetPosition(body, longitude, signOf(longitude), degreeInSign(longitude), speed < 0.0, speed)

	@Test
	fun `허용 범위 밖이면 각도가 아니다`() {
		val inside = computeAspects(listOf(position(Body.SUN, 0.0), position(Body.MOON, 60.0 + AspectType.SEXTILE.maxOrb)))
		assertEquals(listOf(AspectType.SEXTILE), inside.map { it.type })
		val outside = computeAspects(listOf(position(Body.SUN, 0.0), position(Body.MOON, 60.0 + AspectType.SEXTILE.maxOrb + 0.01)))
		assertTrue(outside.isEmpty())
	}

	@Test
	fun `0도를 넘어가는 합을 찾는다`() {
		val aspects = computeAspects(listOf(position(Body.SUN, 359.0, 1.0), position(Body.MOON, 1.0, 13.0)))
		assertEquals(1, aspects.size)
		assertEquals(AspectType.CONJUNCTION, aspects[0].type)
		assertEquals(2.0, aspects[0].orb, 1e-9)
		assertEquals(false, aspects[0].applying) // 달이 앞서 있고 더 빠르다
	}

	@Test
	fun `한 차트 안에서는 같은 쌍을 한 번만 내고 자기 자신과는 비교하지 않는다`() {
		val aspects = computeAspects(listOf(position(Body.SUN, 10.0), position(Body.MOON, 100.0)))
		assertEquals(1, aspects.size)
		assertEquals(Body.SUN to Body.MOON, aspects[0].a to aspects[0].b)
	}

	@Test
	fun `두 차트 사이 각도는 a 의 천체와 b 의 천체를 모두 짝짓는다`() {
		val mine = listOf(position(Body.SUN, 10.0), position(Body.MOON, 195.0))
		val theirs = listOf(position(Body.SUN, 190.0), position(Body.MOON, 55.0))
		val aspects = computeAspects(mine, theirs)
		assertEquals(AspectType.OPPOSITION, aspects.first { it.a == Body.SUN && it.b == Body.SUN }.type)
		assertEquals(AspectType.CONJUNCTION, aspects.first { it.a == Body.MOON && it.b == Body.SUN }.type)
		assertNull(aspects.firstOrNull { it.a == Body.SUN && it.b == Body.MOON }) // 45도는 다섯 종류에 없다
	}

	@Test
	fun `천체와 상승점 중천 사이의 각도를 같은 허용 범위로 찾는다`() {
		val houses = Houses(ascendant = 125.0, midheaven = 35.0, vertex = 0.0, cusps = emptyList())
		val positions = listOf(
			position(Body.SUN, 128.0), // 상승점과 3도 차 합, 중천과 93도 차 사분(오차 3)
			position(Body.MOON, 217.0), // 상승점과 92도 사분(오차 2), 중천과 182도 충(오차 2)
			position(Body.MARS, 80.0), // 상승점과 45도, 중천과 45도 — 다섯 종류에 없다
		)

		val aspects = computeAngleAspects(positions, houses)

		assertEquals(
			setOf(
				AngleAspect(Body.SUN, ChartAngle.ASC, AspectType.CONJUNCTION, 3.0),
				AngleAspect(Body.SUN, ChartAngle.MC, AspectType.SQUARE, 3.0),
				AngleAspect(Body.MOON, ChartAngle.ASC, AspectType.SQUARE, 2.0),
				AngleAspect(Body.MOON, ChartAngle.MC, AspectType.OPPOSITION, 2.0),
			),
			aspects.map { it.copy(orb = Math.round(it.orb * 1e6) / 1e6) }.toSet(),
		)
		val edge = computeAngleAspects(listOf(position(Body.SUN, 125.0 + AspectType.CONJUNCTION.maxOrb + 0.01)), houses)
		assertTrue(edge.none { it.angle == ChartAngle.ASC }, "허용 범위 밖")
	}

	@Test
	fun `0도를 넘어가는 상승점과의 합을 찾는다`() {
		val houses = Houses(ascendant = 358.0, midheaven = 270.0, vertex = 0.0, cusps = emptyList())
		val aspect = computeAngleAspects(listOf(position(Body.SUN, 2.0)), houses).single { it.angle == ChartAngle.ASC }
		assertEquals(AspectType.CONJUNCTION, aspect.type)
		assertEquals(4.0, aspect.orb, 1e-9)
	}
}
