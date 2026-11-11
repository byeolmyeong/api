package com.byeolmyeong.api.chart

import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.rotationEctEqd
import io.github.cosinekitty.astronomy.siderealTime
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class HouseSystem { PLACIDUS }

/** 상승점·중천·버텍스와 플라시더스 하우스 경계 12개(1하우스부터, 황경). */
data class Houses(val ascendant: Double, val midheaven: Double, val vertex: Double, val cusps: List<Double>)

// 플라시더스는 극권에서 계산되지 않는다. 국내 출생지만 받는 동안은 닿지 않는 값이다(ADR 002).
private const val PLACIDUS_MAX_LATITUDE = 66.0
private const val CUSP_ITERATIONS = 50
private const val CUSP_TOLERANCE_DEGREES = 1e-9

fun computeHouses(utc: Instant, latitude: Double, longitude: Double): Houses {
	require(abs(latitude) < PLACIDUS_MAX_LATITUDE) { "플라시더스 하우스는 위도 66도 미만에서만 계산한다" }
	val time = astroTime(utc)
	val obliquity = trueObliquityRadians(time)
	val phi = Math.toRadians(latitude)
	// 그 장소의 겉보기 항성시를 각도로 바꾼 값(자오선의 적경).
	val ramc = Math.toRadians(normalizeDegrees(siderealTime(time) * 15.0 + longitude))

	val midheaven = eclipticLongitudeOfRightAscension(ramc, obliquity)
	val ascendant = risingLongitude(ramc, obliquity, phi)
	// 버텍스: 황도와 묘유권(동서를 지나는 수직권)이 서쪽에서 만나는 점. 여위도(90도 - 위도)의 땅에서
	// 자오선을 반 바퀴 돌린 때의 상승점과 같다. 남반구는 여위도의 부호가 바뀐다.
	val colatitude = if (phi >= 0) Math.PI / 2 - phi else -Math.PI / 2 - phi
	val vertex = risingLongitude(ramc + Math.PI, obliquity, colatitude)
	val cusp11 = placidusCusp(ramc, obliquity, phi, fraction = 1.0 / 3.0, aboveHorizon = true)
	val cusp12 = placidusCusp(ramc, obliquity, phi, fraction = 2.0 / 3.0, aboveHorizon = true)
	val cusp2 = placidusCusp(ramc, obliquity, phi, fraction = 2.0 / 3.0, aboveHorizon = false)
	val cusp3 = placidusCusp(ramc, obliquity, phi, fraction = 1.0 / 3.0, aboveHorizon = false)
	fun opposite(degrees: Double) = normalizeDegrees(degrees + 180.0)

	return Houses(
		ascendant = ascendant,
		midheaven = midheaven,
		vertex = vertex,
		cusps = listOf(
			ascendant, cusp2, cusp3, opposite(midheaven), opposite(cusp11), opposite(cusp12),
			opposite(ascendant), opposite(cusp2), opposite(cusp3), midheaven, cusp11, cusp12,
		),
	)
}

/** 황경이 속한 하우스(1~12). 경계 위의 점은 그 경계에서 시작하는 하우스에 속한다. */
fun houseOf(longitude: Double, cusps: List<Double>): Int {
	for (i in cusps.indices) {
		val width = normalizeDegrees(cusps[(i + 1) % cusps.size] - cusps[i])
		if (normalizeDegrees(longitude - cusps[i]) < width) return i + 1
	}
	error("하우스 경계가 황도를 한 바퀴 덮지 않는다")
}

// 자오선의 적경이 [ramc] 이고 위도가 [phi] 인 곳에서 동쪽 지평선에 떠오르는 황도의 점.
private fun risingLongitude(ramc: Double, obliquity: Double, phi: Double): Double =
	normalizeDegrees(Math.toDegrees(atan2(cos(ramc), -(sin(ramc) * cos(obliquity) + tan(phi) * sin(obliquity)))))

// 참 황도 → 참 적도 회전은 x 축을 도는 회전 하나라, 행렬 성분에서 황도 경사를 읽는다.
private fun trueObliquityRadians(time: Time): Double {
	val rot = rotationEctEqd(time).rot
	return atan2(abs(rot[1][2]), rot[1][1])
}

private fun eclipticLongitudeOfRightAscension(rightAscension: Double, obliquity: Double): Double =
	normalizeDegrees(Math.toDegrees(atan2(sin(rightAscension), cos(rightAscension) * cos(obliquity))))

// 플라시더스: 황도 위의 점이 자기 반일주호(지평선 위) 또는 반야호(지평선 아래)의 1/3·2/3 을 지난 곳이 경계다.
// 그 점의 적위가 답에 달려 있어 적경을 되풀이해 좁힌다.
private fun placidusCusp(ramc: Double, obliquity: Double, phi: Double, fraction: Double, aboveHorizon: Boolean): Double {
	val quarter = Math.PI / 2
	var rightAscension = if (aboveHorizon) ramc + fraction * quarter else ramc + Math.PI - fraction * quarter
	repeat(CUSP_ITERATIONS) {
		val declination = atan(sin(rightAscension) * tan(obliquity))
		val ascensionalDifference = asin(tan(phi) * tan(declination))
		val next = if (aboveHorizon) {
			ramc + fraction * (quarter + ascensionalDifference)
		} else {
			ramc + Math.PI - fraction * (quarter - ascensionalDifference)
		}
		val moved = abs(next - rightAscension)
		rightAscension = next
		if (Math.toDegrees(moved) < CUSP_TOLERANCE_DEGREES) return eclipticLongitudeOfRightAscension(rightAscension, obliquity)
	}
	return eclipticLongitudeOfRightAscension(rightAscension, obliquity)
}
