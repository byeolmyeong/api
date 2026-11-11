package com.byeolmyeong.api.chart

import kotlin.math.abs
import kotlin.math.sign

/** 각도 종류와 허용 범위(도). 허용 범위는 여기 한 곳에서만 정한다. */
enum class AspectType(val angle: Double, val maxOrb: Double) {
	CONJUNCTION(0.0, 8.0),
	SEXTILE(60.0, 6.0),
	SQUARE(90.0, 8.0),
	TRINE(120.0, 8.0),
	OPPOSITION(180.0, 8.0),
}

/** [orb] 는 정확한 각도에서 벗어난 크기(도), [applying] 은 지금 속도로 그 크기가 줄어드는 중인지. */
data class Aspect(val a: Body, val b: Body, val type: AspectType, val orb: Double, val applying: Boolean)

/**
 * [b] 가 없으면 [a] 한 차트 안의 천체 쌍(각 쌍 한 번), 있으면 [a] 의 천체와 [b] 의 천체 사이의 각도.
 */
fun computeAspects(a: List<PlanetPosition>, b: List<PlanetPosition>? = null): List<Aspect> {
	val pairs = if (b == null) {
		a.indices.flatMap { i -> (i + 1 until a.size).map { j -> a[i] to a[j] } }
	} else {
		a.flatMap { first -> b.map { second -> first to second } }
	}
	return pairs.mapNotNull { (first, second) -> aspectBetween(first, second) }
}

private fun aspectBetween(first: PlanetPosition, second: PlanetPosition): Aspect? {
	val difference = signedDifference(second.longitude, first.longitude)
	val separation = abs(difference)
	// 허용 범위가 겹치지 않으므로 맞는 종류는 많아야 하나다.
	val type = AspectType.entries.firstOrNull { abs(separation - it.angle) <= it.maxOrb } ?: return null
	val separationSpeed = sign(difference) * (second.speed - first.speed)
	return Aspect(
		a = first.body,
		b = second.body,
		type = type,
		orb = abs(separation - type.angle),
		applying = (separation - type.angle) * separationSpeed < 0.0,
	)
}
