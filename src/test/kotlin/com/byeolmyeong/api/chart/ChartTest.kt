package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartTest {

	@Test
	fun `시각을 알면 하우스와 천체별 하우스 세 지점을 준다`() {
		for (reference in CHART_REFERENCES) {
			val chart = computeChart(reference.utc, reference.latitude, reference.longitude, timeKnown = true)
			assertTrue(chart.timeKnown)
			assertEquals(Body.entries, chart.positions.map { it.body })
			val houses = assertNotNull(chart.houses)
			assertEquals(12, houses.cusps.size)
			assertTrue(chart.positions.all { it.house in 1..12 })
			assertEquals(reference.sunHouse, chart.positions.first { it.body == Body.SUN }.house, reference.name)
			assertEquals(listOf(PointKind.NORTH_NODE, PointKind.LILITH, PointKind.FORTUNE, PointKind.VERTEX), chart.points.map { it.kind })
			assertTrue(chart.points.all { it.house in 1..12 })
			assertEquals(computeAspects(chart.positions), chart.aspects)
			assertEquals(false, chart.moonSignMayChange, "시각을 알면 달 별자리는 정해져 있다")
		}
	}

	@Test
	fun `시각을 모르면 하우스 상승점 중천 행운점 버텍스를 주지 않는다`() {
		val chart = computeChart(SEOUL_1990.utc, SEOUL_1990.latitude, SEOUL_1990.longitude, timeKnown = false)
		assertEquals(false, chart.timeKnown)
		assertNull(chart.houses)
		assertEquals(10, chart.positions.size)
		assertTrue(chart.positions.all { it.house == null })
		assertEquals(listOf(PointKind.NORTH_NODE, PointKind.LILITH), chart.points.map { it.kind })
		assertTrue(chart.points.all { it.house == null })
		assertTrue(chart.aspects.isNotEmpty())
	}

	// 한국 시각 1990-03-15 하루 동안 달은 천칭자리 29.29도에서 전갈자리 11.24도로 간다(Swiss Ephemeris 로 확인).
	// 다음 날 하루는 전갈자리 11.24도에서 23.11도로, 별자리가 바뀌지 않는다.
	@Test
	fun `시각을 모르고 그날 안에 달 별자리가 바뀌면 알려 준다`() {
		fun unknownTime(noonUtc: String) = computeChart(Instant.parse(noonUtc), SEOUL_1990.latitude, SEOUL_1990.longitude, timeKnown = false)
		assertEquals(true, unknownTime("1990-03-15T03:00:00Z").moonSignMayChange)
		assertEquals(false, unknownTime("1990-03-16T03:00:00Z").moonSignMayChange)
	}
}
