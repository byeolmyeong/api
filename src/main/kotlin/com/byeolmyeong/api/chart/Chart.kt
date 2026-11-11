package com.byeolmyeong.api.chart

import java.time.Duration
import java.time.Instant

data class Chart(
	val timeKnown: Boolean,
	val positions: List<PlanetPosition>,
	val points: List<ChartPoint>,
	/** 상승점·중천·하우스 경계. 출생 시각을 모르면 null. */
	val houses: Houses?,
	val aspects: List<Aspect>,
	/** 출생 시각을 모르고 그날 안에 달의 별자리가 바뀐다. 시각을 알면 항상 false. */
	val moonSignMayChange: Boolean,
)

// 시각을 모를 때 [computeChart] 가 받는 시각은 그날 정오다. 하루의 양 끝은 정오에서 12시간씩 떨어져 있다.
private val HALF_DAY: Duration = Duration.ofHours(12)

// 지평선 위는 7~12하우스다.
private val HOUSES_ABOVE_HORIZON = 7..12

/**
 * 출생 차트. [timeKnown] 이 false 면 하우스·상승점·중천·행운점·버텍스와 천체별 하우스를 계산하지 않는다
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
		points += point(PointKind.VERTEX, houses.vertex)
	}
	val moonSignMayChange = !timeKnown && moonSignChanges(utc.minus(HALF_DAY), utc.plus(HALF_DAY))
	return Chart(timeKnown, positions, points, houses, computeAspects(positions), moonSignMayChange)
}
