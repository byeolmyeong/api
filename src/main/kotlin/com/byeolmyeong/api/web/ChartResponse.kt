package com.byeolmyeong.api.web

import com.byeolmyeong.api.chart.Aspect
import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.PointKind
import com.byeolmyeong.api.chart.Sign
import com.byeolmyeong.api.chart.degreeInSign
import com.byeolmyeong.api.chart.signOf
import com.fasterxml.jackson.annotation.JsonInclude
import kotlin.math.floor
import kotlin.math.roundToLong

// `Chart` 응답. 형태의 원본은 docs/architecture.md "API 계약". 값이 없는 필드(house·houses·angles)는 키를 뺀다.

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChartResponse(
	val timeKnown: Boolean,
	val positions: List<PositionResponse>,
	val points: List<PointResponse>,
	val houses: List<HouseResponse>?,
	val angles: AnglesResponse?,
	val aspects: List<AspectResponse>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PositionResponse(
	val body: Body,
	val sign: Sign,
	val degree: Int,
	val minute: Int,
	val house: Int?,
	val retrograde: Boolean,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PointResponse(val point: PointKind, val sign: Sign, val degree: Int, val minute: Int, val house: Int?)

data class HouseResponse(val house: Int, val sign: Sign, val degree: Int, val minute: Int)

data class AngleResponse(val sign: Sign, val degree: Int, val minute: Int)

data class AnglesResponse(val asc: AngleResponse, val mc: AngleResponse)

data class AspectResponse(val a: Body, val b: Body, val type: AspectType, val orb: Double, val applying: Boolean)

/** 별자리 안 도수를 도·분으로 나눈다. 올리지 않고 버린다: 29도 59.9분이 다음 별자리 0도가 되면 안 된다. */
internal fun degreeAndMinute(degreeInSign: Double): Pair<Int, Int> {
	val degree = floor(degreeInSign).toInt()
	val minute = floor((degreeInSign - degree) * 60.0).toInt().coerceAtMost(59)
	return degree to minute
}

fun Chart.toResponse(): ChartResponse = ChartResponse(
	timeKnown = timeKnown,
	positions = positions.map {
		val (degree, minute) = degreeAndMinute(it.degreeInSign)
		PositionResponse(it.body, it.sign, degree, minute, it.house, it.retrograde)
	},
	points = points.map {
		val (degree, minute) = degreeAndMinute(it.degreeInSign)
		PointResponse(it.kind, it.sign, degree, minute, it.house)
	},
	houses = houses?.cusps?.mapIndexed { index, cusp ->
		val (degree, minute) = degreeAndMinute(degreeInSign(cusp))
		HouseResponse(index + 1, signOf(cusp), degree, minute)
	},
	angles = houses?.let { AnglesResponse(angle(it.ascendant), angle(it.midheaven)) },
	aspects = aspects.map { it.toResponse() },
)

private fun angle(longitude: Double): AngleResponse {
	val (degree, minute) = degreeAndMinute(degreeInSign(longitude))
	return AngleResponse(signOf(longitude), degree, minute)
}

// 오차 각도는 소수 둘째 자리까지 준다.
private fun Aspect.toResponse() = AspectResponse(a, b, type, (orb * 100.0).roundToLong() / 100.0, applying)
