package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
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
			assertEquals(listOf(PointKind.NORTH_NODE, PointKind.LILITH, PointKind.FORTUNE), chart.points.map { it.kind })
			assertTrue(chart.points.all { it.house in 1..12 })
			assertEquals(computeAspects(chart.positions), chart.aspects)
		}
	}

	@Test
	fun `시각을 모르면 하우스 상승점 중천 행운점을 주지 않는다`() {
		val chart = computeChart(SEOUL_1990.utc, SEOUL_1990.latitude, SEOUL_1990.longitude, timeKnown = false)
		assertEquals(false, chart.timeKnown)
		assertNull(chart.houses)
		assertEquals(10, chart.positions.size)
		assertTrue(chart.positions.all { it.house == null })
		assertEquals(listOf(PointKind.NORTH_NODE, PointKind.LILITH), chart.points.map { it.kind })
		assertTrue(chart.points.all { it.house == null })
		assertTrue(chart.aspects.isNotEmpty())
	}
}
