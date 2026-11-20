package com.byeolmyeong.api.chart

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// 그날 · 그달에 지나가는 별(결정 032 의 3 · 034 의 3). 규칙과 무게의 원본은 문구표 초안 docs/drafts/2026-11-20-month-phrases.md
// 2 · 4 · 6절(소유자 2026-11-20 "초안 그대로 써"). 위치는 한국 날짜의 정오에 잰다. 날짜 · 도수는 모델에게 넘기지 않는다.

/**
 * 지나가는 별 하나가 내 점 하나와 이루는 각도. [date] 는 한국 날짜, [target] 은 `Body` 또는 `ChartAngle` 의 이름(`"ASC"` · `"MC"`),
 * [orb] 는 정확한 각도에서 벗어난 크기(도), [score] 는 세기(초안 4절 — 달은 무게가 없어 0).
 */
data class SkyTransit(val date: LocalDate, val mover: Body, val target: String, val type: AspectType, val orb: Double, val score: Double) {
	/** 조합 id: `MERCURY.SUN.TRINE`. */
	val combination: String get() = "$mover.$target.$type"
}

/** 그날 · 그달의 지나가는 별: 해 · 수성 · 금성 · 화성 · 목성. 달은 그날의 하늘에서만 마지막 근거로 쓴다. */
val DAY_MOVERS = listOf(Body.SUN, Body.MERCURY, Body.VENUS, Body.MARS, Body.JUPITER)

/** 눈여겨볼 날로 삼을 수 있는 가장 작은 세기. */
const val NOTABLE_MIN_SCORE = 8.0

/** 한 달의 눈여겨볼 날 상한. */
const val MAX_NOTABLE_DAYS = 3

private const val DAY_ORB = 1.0
private const val MIN_GAP_DAYS = 3L
private val KOREA: ZoneId = ZoneId.of("Asia/Seoul")
private val NOON: LocalTime = LocalTime.NOON

private fun aspectWeight(type: AspectType): Double = when (type) {
	AspectType.CONJUNCTION -> 5.0
	AspectType.OPPOSITION, AspectType.SQUARE -> 4.0
	AspectType.TRINE -> 3.0
	AspectType.SEXTILE -> 2.0
}

private fun moverWeight(body: Body): Double = when (body) {
	Body.SUN, Body.MARS, Body.JUPITER -> 3.0
	Body.VENUS -> 2.5
	Body.MERCURY -> 2.0
	else -> 0.0
}

private fun bodyWeight(body: Body): Double = when (body) {
	Body.SUN, Body.MOON -> 3.0
	Body.MERCURY, Body.VENUS, Body.MARS -> 2.0
	Body.JUPITER, Body.SATURN -> 1.5
	Body.URANUS, Body.NEPTUNE, Body.PLUTO -> 1.0
}

private const val ANGLE_WEIGHT = 2.5

/** 내 점 하나: 이름(`Body` · `ChartAngle`), 황경, 무게. */
private class NatalPoint(val name: String, val longitude: Double, val weight: Double)

// 행성 10 + 시각을 알면 상승점 · 중천. 교점 · 릴리스 · 행운점 · 버텍스는 대상이 아니다(초안 2절).
private fun natalPoints(chart: Chart): List<NatalPoint> =
	chart.positions.map { NatalPoint(it.body.name, it.longitude, bodyWeight(it.body)) } +
		chart.houses?.let { listOf(NatalPoint(ChartAngle.ASC.name, it.ascendant, ANGLE_WEIGHT), NatalPoint(ChartAngle.MC.name, it.midheaven, ANGLE_WEIGHT)) }.orEmpty()

private fun noonOf(date: LocalDate): Instant = date.atTime(NOON).atZone(KOREA).toInstant()

private fun longitudeOn(body: Body, date: LocalDate): Double = apparentLongitude(body, astroTime(noonOf(date)))

private fun orbOf(mover: Double, natal: Double, type: AspectType): Double = abs(abs(signedDifference(mover, natal)) - type.angle)

private fun transit(date: LocalDate, mover: Body, point: NatalPoint, type: AspectType, orb: Double) =
	SkyTransit(date, mover, point.name, type, orb, aspectWeight(type) * moverWeight(mover) * point.weight * (1 - orb / 2))

// 지나가는 해 × 내 해의 합은 생일이라 뺀다 — 날짜가 찍히는 그날의 하늘 카드에서 생일이 드러난다(초안 2절).
private fun isBirthday(mover: Body, point: NatalPoint, type: AspectType) =
	mover == Body.SUN && point.name == Body.SUN.name && type == AspectType.CONJUNCTION

private val STRONGEST_FIRST: Comparator<SkyTransit> =
	compareByDescending<SkyTransit> { it.score }.thenBy { it.date }.thenBy { it.combination }

/**
 * 그달의 눈여겨볼 날 후보(초안 4절의 1~2): 조합(지나가는 별 × 내 점 × 각도)마다 그달에서 오차가 가장 작은 날 하나(같으면 이른 날),
 * 그 오차가 1° 이하인 것. 지나가는 해 × 내 해의 합은 뺀다. 세기 순(같으면 이른 날, 조합 id 순). 세기 기준(8)은 [pickNotableDays] 가 본다.
 */
fun monthTransitCandidates(chart: Chart, month: YearMonth): List<SkyTransit> {
	val days = (1..month.lengthOfMonth()).map(month::atDay)
	val points = natalPoints(chart)
	return DAY_MOVERS.flatMap { mover ->
		val longitudes = days.map { longitudeOn(mover, it) }
		points.flatMap { point ->
			AspectType.entries.mapNotNull { type ->
				if (isBirthday(mover, point, type)) return@mapNotNull null
				val best = days.indices.minBy { orbOf(longitudes[it], point.longitude, type) }
				val orb = orbOf(longitudes[best], point.longitude, type)
				if (orb <= DAY_ORB) transit(days[best], mover, point, type, orb) else null
			}
		}
	}.sortedWith(STRONGEST_FIRST)
}

/**
 * 눈여겨볼 날 고르기(초안 4절의 3~5): 세기 8 이상만, 세기 순(같으면 이른 날, 조합 id 순)으로 두 번 훑는다 —
 * 첫째는 고른 날과 사흘 이상 떨어지고 [categoryOf] 가 아직 없는 것, 둘째는 사흘 간격만 지켜 채운다. 셋까지, 날짜 순.
 */
fun <C> pickNotableDays(candidates: List<SkyTransit>, categoryOf: (SkyTransit) -> C): List<SkyTransit> {
	val ordered = candidates.filter { it.score >= NOTABLE_MIN_SCORE }.sortedWith(STRONGEST_FIRST)
	val picked = mutableListOf<SkyTransit>()
	fun spaced(candidate: SkyTransit) = picked.all { abs(ChronoUnit.DAYS.between(it.date, candidate.date)) >= MIN_GAP_DAYS }
	for (candidate in ordered) {
		if (picked.size == MAX_NOTABLE_DAYS) break
		if (spaced(candidate) && picked.none { categoryOf(it) == categoryOf(candidate) }) picked += candidate
	}
	for (candidate in ordered) {
		if (picked.size == MAX_NOTABLE_DAYS) break
		if (candidate !in picked && spaced(candidate)) picked += candidate
	}
	return picked.sortedBy { it.date }
}

/** 그달의 눈여겨볼 날(0~3개, 날짜 순). [categoryOf] 는 문구표의 칩(사람 · 일 · 마음)이다. */
fun <C> notableDays(chart: Chart, month: YearMonth, categoryOf: (SkyTransit) -> C): List<SkyTransit> =
	pickNotableDays(monthTransitCandidates(chart, month), categoryOf)

/** 그날(한국 날짜 정오) 다섯 별의 통과 가운데 1° 안의 것, 세기 순. 달은 넣지 않는다. 지나가는 해 × 내 해의 합은 뺀다. */
fun dayTransits(chart: Chart, date: LocalDate): List<SkyTransit> {
	val points = natalPoints(chart)
	return DAY_MOVERS.flatMap { mover ->
		val longitude = longitudeOn(mover, date)
		points.flatMap { point ->
			AspectType.entries.mapNotNull { type ->
				val orb = orbOf(longitude, point.longitude, type)
				if (orb <= DAY_ORB && !isBirthday(mover, point, type)) transit(date, mover, point, type, orb) else null
			}
		}
	}.sortedWith(STRONGEST_FIRST)
}

/**
 * 그날의 하늘의 근거(초안 6절, 결정 034): 그날 1° 안 다섯 별의 통과 가운데 가장 센 것. 없으면 달이 내 점과 이루는 각도 가운데
 * 가장 정확한 것 — 초안의 "5° 안 가장 정확한 것, 없으면 오차와 상관없이 가장 가까운 것" 은 둘 다 오차가 가장 작은 하나라 같다(근거가 비는 날은 없다).
 * 달의 [SkyTransit.score] 는 0 이다(지나가는 별의 무게 표에 달이 없다).
 */
fun daySkyTransit(chart: Chart, date: LocalDate): SkyTransit {
	dayTransits(chart, date).firstOrNull()?.let { return it }
	val moon = longitudeOn(Body.MOON, date)
	val closest = natalPoints(chart).flatMap { point ->
		AspectType.entries.map { type -> SkyTransit(date, Body.MOON, point.name, type, orbOf(moon, point.longitude, type), score = 0.0) }
	}.minWith(compareBy<SkyTransit> { it.orb }.thenBy { it.combination })
	return closest
}
