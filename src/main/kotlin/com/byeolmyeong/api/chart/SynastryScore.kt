package com.byeolmyeong.api.chart

import kotlin.math.abs
import kotlin.math.roundToInt

// 궁합의 "나 기준 점수". 규칙의 원본은 앱 저장소 docs/release-1.0.0.md F5 "점수 계산 규칙" 이고,
// 가족의 가중치는 앱 결정 020 의 제안값이다. 숫자는 이 파일 한 곳에서만 정한다.

/** 관계 유형. 유형마다 가중치를 주는 조합이 다르다. */
enum class Relation { LOVE, FRIEND, WORK, FAMILY }

/** 내 쪽에서 보는 곳: 개인 행성 다섯과, 태어난 시각을 알 때의 상승점. */
enum class SynastryTarget(val body: Body?) {
	SUN(Body.SUN), MOON(Body.MOON), MERCURY(Body.MERCURY), VENUS(Body.VENUS), MARS(Body.MARS), ASC(null),
}

/**
 * 상대의 천체 [theirs] 가 내 [mine] 에 만드는 각도 하나. [orb] 는 정확한 각도에서 벗어난 크기(도),
 * [points] 는 가중치를 곱하기 전의 점수(각도별 점수 × 오차에 따른 비율)다.
 */
data class SynastryAspect(val mine: SynastryTarget, val theirs: Body, val type: AspectType, val orb: Double, val points: Double)

// 각도별 점수와 허용 오차. 출생 차트의 허용 범위(AspectType.maxOrb)와 다르다.
private class Rule(val type: AspectType, val points: Double, val maxOrb: Double)

private val RULES = listOf(
	Rule(AspectType.CONJUNCTION, 6.0, 8.0),
	Rule(AspectType.SEXTILE, 5.0, 5.0),
	Rule(AspectType.SQUARE, -6.0, 7.0),
	Rule(AspectType.TRINE, 8.0, 7.0),
	Rule(AspectType.OPPOSITION, -4.0, 8.0),
)

/** 합에 토성이나 화성이 끼면 +6 이 아니라 이 점수다. */
private const val HARD_CONJUNCTION_POINTS = -3.0
private val HARD_BODIES = setOf(Body.SATURN, Body.MARS)

/**
 * 상대([theirs])의 천체 10개가 내([mine]) 태양·달·수성·금성·화성(시각을 알면 상승점도)에 만드는 각도.
 * 내 쪽 순서 → 상대 쪽 순서로 늘 같은 순서로 준다.
 */
fun synastryAspects(mine: Chart, theirs: Chart): List<SynastryAspect> {
	val targets = SynastryTarget.entries.mapNotNull { target ->
		val longitude = if (target.body == null) mine.houses?.ascendant else mine.positions.firstOrNull { it.body == target.body }?.longitude
		longitude?.let { target to it }
	}
	return targets.flatMap { (target, longitude) ->
		theirs.positions.mapNotNull { planet -> aspectTo(target, longitude, planet) }
	}
}

private fun aspectTo(target: SynastryTarget, longitude: Double, planet: PlanetPosition): SynastryAspect? {
	val separation = abs(signedDifference(planet.longitude, longitude))
	// 허용 오차가 겹치지 않으므로 맞는 각도는 많아야 하나다.
	val rule = RULES.firstOrNull { abs(separation - it.angle()) <= it.maxOrb } ?: return null
	val orb = abs(separation - rule.angle())
	val hard = rule.type == AspectType.CONJUNCTION && (planet.body in HARD_BODIES || target.body in HARD_BODIES)
	val full = if (hard) HARD_CONJUNCTION_POINTS else rule.points
	// 정확히 맞으면 100%, 허용 오차 끝이면 0%(선형).
	return SynastryAspect(target, planet.body, rule.type, orb, full * (1.0 - orb / rule.maxOrb))
}

private fun Rule.angle(): Double = type.angle

/** 관계 유형별 가중치. 적히지 않은 조합은 1 이다. "A-B" 는 어느 쪽이 누구 것이든 같다(토성-태양은 상대의 토성과 내 태양뿐이다). */
private fun weight(relation: Relation, mine: SynastryTarget, theirs: Body): Double {
	fun pair(a: Body, b: Body) = (mine.body == a && theirs == b) || (mine.body == b && theirs == a)
	return when (relation) {
		Relation.LOVE -> if (pair(Body.VENUS, Body.MARS) || pair(Body.SUN, Body.MOON)) 2.0 else 1.0
		Relation.FRIEND -> if (pair(Body.MERCURY, Body.MERCURY)) 2.0 else if (pair(Body.MOON, Body.MOON)) 1.5 else 1.0
		Relation.WORK -> if (pair(Body.MERCURY, Body.MERCURY)) 2.0 else if (mine == SynastryTarget.SUN && theirs == Body.SATURN) 1.5 else 1.0
		Relation.FAMILY -> if (pair(Body.MOON, Body.MOON)) 2.0 else if (pair(Body.SUN, Body.MOON)) 1.5 else 1.0
	}
}

/** 가중치를 곱해 모두 더한 값. 40~100 으로 바꾸기 전이다. */
fun synastrySum(aspects: List<SynastryAspect>, relation: Relation): Double =
	aspects.sumOf { it.points * weight(relation, it.mine, it.theirs) }

/** 나([mine]) 기준으로 본 상대([theirs])의 점수(40~100). 같은 입력에는 늘 같은 값이다. */
fun synastryScore(mine: Chart, theirs: Chart, relation: Relation): Int =
	scoreOfSum(synastrySum(synastryAspects(mine, theirs), relation))

// 합산값 → 점수. 표본 1,200쌍의 합산값은 가운데가 +10~11, 아래 1% 가 -21~-30, 위 1% 가 +38~+48 이었다
// (docs/plans/2026-11-10-api-mvp.md 실행 기록 "태스크 9 (직접 입력)"). 그 양 끝을 40 과 100 에 맞춘 직선이다.
private const val SUM_AT_MIN_SCORE = -30.0
private const val SUM_AT_MAX_SCORE = 45.0
const val MIN_SYNASTRY_SCORE = 40
const val MAX_SYNASTRY_SCORE = 100

/** 합산값을 40~100 으로 바꾼다: -30 이하는 40, +45 이상은 100, 그 사이는 직선(1 마다 0.8점, 합산값 0 은 64점). */
fun scoreOfSum(sum: Double): Int {
	val ratio = (sum - SUM_AT_MIN_SCORE) / (SUM_AT_MAX_SCORE - SUM_AT_MIN_SCORE)
	val score = MIN_SYNASTRY_SCORE + ratio * (MAX_SYNASTRY_SCORE - MIN_SYNASTRY_SCORE)
	return score.roundToInt().coerceIn(MIN_SYNASTRY_SCORE, MAX_SYNASTRY_SCORE)
}
