package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HousesTest {

	@Test
	fun `서울과 부산 예시의 상승점 중천 하우스 경계가 기준값과 0_1도 이내로 맞는다`() {
		var maxError = 0.0
		for (reference in CHART_REFERENCES) {
			val houses = computeHouses(reference.utc, reference.latitude, reference.longitude)
			assertEquals(12, houses.cusps.size)
			val pairs = listOf(
				"상승점" to (houses.ascendant to reference.ascendant),
				"중천" to (houses.midheaven to reference.midheaven),
			) + houses.cusps.mapIndexed { i, cusp -> "${i + 1}하우스" to (cusp to reference.cusps[i]) }
			for ((label, values) in pairs) {
				val error = angleError(values.first, values.second)
				maxError = maxOf(maxError, error)
				assertTrue(error <= 0.1, "${reference.name} $label: 오차 $error 도")
			}
		}
		println("하우스 경계·상승점·중천 최대 오차: %.6f 도 (28건)".format(maxError))
	}

	@Test
	fun `황경이 속한 하우스는 경계 사이로 정한다`() {
		val cusps = SEOUL_1990.cusps
		assertEquals(1, houseOf(125.053774, cusps))
		assertEquals(1, houseOf(146.0, cusps))
		assertEquals(2, houseOf(146.462870, cusps))
		assertEquals(9, houseOf(354.37, cusps)) // 9하우스 경계 352.2, 10하우스 경계 23.8 — 0도를 넘어간다
		assertEquals(9, houseOf(10.0, cusps))
		assertEquals(12, houseOf(125.0, cusps))
	}
}
