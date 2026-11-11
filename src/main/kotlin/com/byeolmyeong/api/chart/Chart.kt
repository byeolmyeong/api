package com.byeolmyeong.api.chart

import java.time.Instant

data class Chart(
	val timeKnown: Boolean,
	val positions: List<PlanetPosition>,
	val points: List<ChartPoint>,
	/** 상승점·중천·하우스 경계. 출생 시각을 모르면 null. */
	val houses: Houses?,
	val aspects: List<Aspect>,
)

// 지평선 위는 7~12하우스다.
private val HOUSES_ABOVE_HORIZON = 7..12

/**
 * 출생 차트. [timeKnown] 이 false 면 하우스·상승점·중천·행운점과 천체별 하우스를 계산하지 않는다
 * (그때 [utc] 는 그날 정오이고 위경도는 쓰지 않는다).
 */
fun computeChart(utc: Instant, lat: Double, lon: Double, timeKnown: Boolean): Chart {
	val houses = if (timeKnown) computeHouses(utc, lat, lon) else null
	fun houseOrNull(longitude: Double): Int? = houses?.let { houseOf(longitude, it.cusps) }
	fun point(kind: PointKind, longitude: Double) =
		ChartPoint(kind, longitude, signOf(longitude), degreeInSign(longitude), houseOrNull(longitude))

	val positions = computePositions(utc).map { it.copy(house = houseOrNull(it.longitude)) }
	val points = mutableListOf(
		point(PointKind.NORTH_NODE, meanNodeLongitude(utc)),
		point(PointKind.LILITH, meanLilithLongitude(utc)),
	)
	if (houses != null) {
		val sun = positions.first { it.body == Body.SUN }
		val moon = positions.first { it.body == Body.MOON }
		val fortune = fortuneLongitude(
			ascendant = houses.ascendant,
			sun = sun.longitude,
			moon = moon.longitude,
			sunAboveHorizon = sun.house in HOUSES_ABOVE_HORIZON,
		)
		points += point(PointKind.FORTUNE, fortune)
	}
	return Chart(timeKnown, positions, points, houses, computeAspects(positions))
}
