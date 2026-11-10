package com.byeolmyeong.api.chart

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.geoVector
import io.github.cosinekitty.astronomy.rotationEqjEct
import java.time.Instant
import kotlin.math.floor
import io.github.cosinekitty.astronomy.Body as AstroBody

enum class Body { SUN, MOON, MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE, PLUTO }

enum class Sign { ARIES, TAURUS, GEMINI, CANCER, LEO, VIRGO, LIBRA, SCORPIO, SAGITTARIUS, CAPRICORN, AQUARIUS, PISCES }

data class PlanetPosition(
	val body: Body,
	val longitude: Double,
	val sign: Sign,
	val degreeInSign: Double,
	val retrograde: Boolean,
)

/** 각도를 0 이상 360 미만으로 접는다. */
fun normalizeDegrees(degrees: Double): Double {
	val folded = degrees - 360.0 * floor(degrees / 360.0)
	return if (folded >= 360.0) 0.0 else folded
}

/** 황경이 속한 별자리. 반올림하지 않는다: 29.9999도는 앞 별자리, 30.0도는 다음 별자리. */
fun signOf(longitude: Double): Sign = Sign.entries[floor(normalizeDegrees(longitude) / 30.0).toInt()]

fun degreeInSign(longitude: Double): Double {
	val folded = normalizeDegrees(longitude)
	return folded - 30.0 * floor(folded / 30.0)
}

/** 태양·달·수성~명왕성의 지구 중심 겉보기 황경(날짜 기준 황도)과 별자리·역행 여부. */
fun computePositions(utc: Instant): List<PlanetPosition> {
	val time = astroTime(utc)
	return Body.entries.map { body ->
		val longitude = apparentLongitude(body, time)
		PlanetPosition(
			body = body,
			longitude = longitude,
			sign = signOf(longitude),
			degreeInSign = degreeInSign(longitude),
			retrograde = longitudeSpeed(body, time) < 0.0,
		)
	}
}

internal fun astroTime(utc: Instant): Time = Time.fromMillisecondsSince1970(utc.toEpochMilli())

// 빛이 오는 시간과 광행차를 보정한 J2000 적도 벡터를 그 시각의 참 황도(세차·장동 반영)로 돌린다.
internal fun apparentLongitude(body: Body, time: Time): Double {
	val equatorialJ2000 = geoVector(body.toAstro(), time, Aberration.Corrected)
	return normalizeDegrees(rotationEqjEct(time).rotate(equatorialJ2000).toSpherical().lon)
}

private const val SPEED_HALF_STEP_DAYS = 0.5 / 24.0

/** 황경의 하루 이동량(도/일). 앞뒤 30분의 차이로 구한다. 음수면 역행이다. */
internal fun longitudeSpeed(body: Body, time: Time): Double {
	val before = apparentLongitude(body, time.addDays(-SPEED_HALF_STEP_DAYS))
	val after = apparentLongitude(body, time.addDays(SPEED_HALF_STEP_DAYS))
	return signedDifference(after, before) / (2 * SPEED_HALF_STEP_DAYS)
}

/** a - b 를 -180 초과 180 이하로 접은 값. */
internal fun signedDifference(a: Double, b: Double): Double {
	val difference = normalizeDegrees(a - b)
	return if (difference > 180.0) difference - 360.0 else difference
}

private fun Body.toAstro(): AstroBody = when (this) {
	Body.SUN -> AstroBody.Sun
	Body.MOON -> AstroBody.Moon
	Body.MERCURY -> AstroBody.Mercury
	Body.VENUS -> AstroBody.Venus
	Body.MARS -> AstroBody.Mars
	Body.JUPITER -> AstroBody.Jupiter
	Body.SATURN -> AstroBody.Saturn
	Body.URANUS -> AstroBody.Uranus
	Body.NEPTUNE -> AstroBody.Neptune
	Body.PLUTO -> AstroBody.Pluto
}
