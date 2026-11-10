package com.byeolmyeong.api.chart

import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.rotationEctEqd
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class PointKind { NORTH_NODE, LILITH, FORTUNE }

data class ChartPoint(
	val kind: PointKind,
	val longitude: Double,
	val sign: Sign,
	val degreeInSign: Double,
	/** 속한 하우스(1~12). 출생 시각을 모르면 null. */
	val house: Int?,
)

// 달 궤도의 평균 요소(Meeus, Astronomical Algorithms 2판 47장). T 는 J2000 부터의 율리우스 세기(TT).
private const val MOON_ORBIT_INCLINATION_DEGREES = 5.1453964

private fun meanAscendingNode(t: Double): Double =
	125.0445479 - 1934.1362891 * t + 0.0020754 * t * t + t * t * t / 467441.0 - t * t * t * t / 60616000.0

private fun meanPerigee(t: Double): Double =
	83.3532465 + 4069.0137287 * t - 0.0103200 * t * t - t * t * t / 80053.0 + t * t * t * t / 18999000.0

private fun centuries(time: Time): Double = time.tt / 36525.0

// 평균 요소는 평균 춘분점 기준이다. 행성 황경과 같은 기준(참 춘분점)이 되도록 황경 장동을 더한다.
private fun nutationDegrees(time: Time): Double {
	// 라이브러리는 장동을 처음 필요할 때 계산해 Time 에 담아 둔다(그 전에는 NaN). 회전 행렬을 구해 계산을 일으킨다.
	rotationEctEqd(time)
	val arcseconds = time.nutationPsi()
	check(!arcseconds.isNaN()) { "장동 값이 계산되지 않았다" }
	return arcseconds / 3600.0
}

/** 북쪽 교점(평균 교점)의 날짜 기준 황경. */
fun meanNodeLongitude(utc: Instant): Double {
	val time = astroTime(utc)
	return normalizeDegrees(meanAscendingNode(centuries(time)) + nutationDegrees(time))
}

/** 릴리스(달의 평균 원지점)의 날짜 기준 황경. 달 궤도면 위의 원지점 방향을 황도에 내린 값이다. */
fun meanLilithLongitude(utc: Instant): Double {
	val time = astroTime(utc)
	val t = centuries(time)
	val node = meanAscendingNode(t)
	val fromNode = Math.toRadians(meanPerigee(t) + 180.0 - node)
	val inclination = Math.toRadians(MOON_ORBIT_INCLINATION_DEGREES)
	val projected = Math.toDegrees(atan2(sin(fromNode) * cos(inclination), cos(fromNode)))
	return normalizeDegrees(node + projected + nutationDegrees(time))
}

/** 행운점. 낮(태양이 지평선 위)에는 상승점 + 달 - 태양, 밤에는 상승점 + 태양 - 달. */
fun fortuneLongitude(ascendant: Double, sun: Double, moon: Double, sunAboveHorizon: Boolean): Double =
	normalizeDegrees(if (sunAboveHorizon) ascendant + moon - sun else ascendant + sun - moon)
