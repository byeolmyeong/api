package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointsTest {

	@Test
	fun `평균 교점과 평균 릴리스가 기준값과 0_01도 이내로 맞는다`() {
		var maxError = 0.0
		for (reference in CHART_REFERENCES) {
			val nodeError = angleError(meanNodeLongitude(reference.utc), reference.meanNode)
			val lilithError = angleError(meanLilithLongitude(reference.utc), reference.meanLilith)
			maxError = maxOf(maxError, nodeError, lilithError)
			assertTrue(nodeError <= 0.01, "${reference.name} 교점: 오차 $nodeError 도")
			assertTrue(lilithError <= 0.01, "${reference.name} 릴리스: 오차 $lilithError 도")
		}
		println("교점·릴리스 최대 오차: %.6f 도 (4건)".format(maxError))
	}

	@Test
	fun `행운점은 낮과 밤의 공식이 다르다`() {
		assertEquals(100.0, fortuneLongitude(ascendant = 10.0, sun = 30.0, moon = 120.0, sunAboveHorizon = true), 1e-9)
		assertEquals(280.0, fortuneLongitude(ascendant = 10.0, sun = 30.0, moon = 120.0, sunAboveHorizon = false), 1e-9)
	}

	@Test
	fun `차트의 행운점이 기준값과 0_1도 이내로 맞는다`() {
		var maxError = 0.0
		for (reference in CHART_REFERENCES) {
			val chart = computeChart(reference.utc, reference.latitude, reference.longitude, timeKnown = true)
			val fortune = chart.points.first { it.kind == PointKind.FORTUNE }
			val error = angleError(fortune.longitude, reference.fortune)
			maxError = maxOf(maxError, error)
			assertTrue(error <= 0.1, "${reference.name} 행운점: 오차 $error 도")
		}
		println("행운점 최대 오차: %.6f 도 (2건)".format(maxError))
	}

	@Test
	fun `차트의 버텍스가 기준값과 0_1도 이내로 맞는다`() {
		var maxError = 0.0
		for (reference in CHART_REFERENCES) {
			val chart = computeChart(reference.utc, reference.latitude, reference.longitude, timeKnown = true)
			val vertex = chart.points.first { it.kind == PointKind.VERTEX }
			val error = angleError(vertex.longitude, reference.vertex)
			maxError = maxOf(maxError, error)
			assertTrue(error <= 0.1, "${reference.name} 버텍스: 오차 $error 도")
		}
		println("버텍스 최대 오차: %.6f 도 (2건)".format(maxError))
	}
}
