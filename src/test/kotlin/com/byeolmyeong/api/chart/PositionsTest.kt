package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 기준값: NASA JPL Horizons, 지구 중심 겉보기 황경(날짜 기준 황도).
// 원본: 앱 저장소 docs/raw/2026-11-10-jpl-horizons-reference.md (2026-11-10 조회)
class PositionsTest {

	private val reference: Map<String, Map<Body, Double>> = mapOf(
		"1955-08-20T03:00:00Z" to mapOf(
			Body.SUN to 146.3341380, Body.MOON to 175.7958955, Body.MERCURY to 160.1176665,
			Body.VENUS to 142.9919193, Body.MARS to 145.3537104, Body.JUPITER to 134.5676386,
			Body.SATURN to 225.3117700, Body.URANUS to 119.7429947, Body.NEPTUNE to 205.9568951,
			Body.PLUTO to 146.4675066,
		),
		"1990-01-01T00:00:00Z" to mapOf(
			Body.SUN to 280.3045062, Body.MOON to 326.5573720, Body.MERCURY to 295.7954362,
			Body.VENUS to 306.2818333, Body.MARS to 249.6482639, Body.JUPITER to 95.2160871,
			Body.SATURN to 285.5983638, Body.URANUS to 275.7554118, Body.NEPTUNE to 282.0191365,
			Body.PLUTO to 227.0793833,
		),
		"2000-06-15T12:00:00Z" to mapOf(
			Body.SUN to 84.6800622, Body.MOON to 248.9041299, Body.MERCURY to 107.6394738,
			Body.VENUS to 85.7902559, Body.MARS to 89.3145796, Body.JUPITER to 56.7968070,
			Body.SATURN to 54.9027324, Body.URANUS to 320.6457778, Body.NEPTUNE to 306.2038904,
			Body.PLUTO to 251.1723985,
		),
	)

	// 하루 뒤 황경이 줄어든 천체(같은 원본의 "하루 뒤의 황경" 표). 6건.
	private val retrogradeReference: Map<String, Set<Body>> = mapOf(
		"1955-08-20T03:00:00Z" to emptySet(),
		"1990-01-01T00:00:00Z" to setOf(Body.MERCURY, Body.VENUS, Body.JUPITER),
		"2000-06-15T12:00:00Z" to setOf(Body.URANUS, Body.NEPTUNE, Body.PLUTO),
	)

	@Test
	fun `세 시각 열 천체의 황경이 기준값과 0_01도 이내로 맞는다`() {
		var maxError = 0.0
		for ((instant, expected) in reference) {
			val positions = computePositions(Instant.parse(instant))
			assertEquals(Body.entries, positions.map { it.body })
			for (position in positions) {
				assertTrue(position.longitude >= 0.0 && position.longitude < 360.0)
				val error = abs(position.longitude - expected.getValue(position.body))
				maxError = maxOf(maxError, error)
				assertTrue(error <= 0.01, "$instant ${position.body}: 오차 $error 도")
			}
		}
		println("행성 황경 최대 오차: %.6f 도 (30건)".format(maxError))
	}

	@Test
	fun `역행 여부가 기준과 같다`() {
		for ((instant, expected) in retrogradeReference) {
			val retrograde = computePositions(Instant.parse(instant)).filter { it.retrograde }.map { it.body }.toSet()
			assertEquals(expected, retrograde, instant)
		}
	}

	@Test
	fun `별자리와 별자리 안 도수는 황경에서 나온다`() {
		val sun = computePositions(Instant.parse("2000-06-15T12:00:00Z")).first { it.body == Body.SUN }
		assertEquals(Sign.GEMINI, sun.sign)
		assertEquals(sun.longitude - 60.0, sun.degreeInSign, 1e-9)
	}

	@Test
	fun `별자리 경계에서 반올림하지 않는다`() {
		assertEquals(Sign.ARIES, signOf(29.9999))
		assertEquals(29.9999, degreeInSign(29.9999), 1e-9)
		assertEquals(Sign.TAURUS, signOf(30.0))
		assertEquals(0.0, degreeInSign(30.0), 1e-9)
		assertEquals(Sign.PISCES, signOf(359.9999))
		assertEquals(Sign.ARIES, signOf(0.0))
	}
}
