package com.byeolmyeong.api.chart

import io.github.cosinekitty.astronomy.Time
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// 시기(인생의 변곡점): 누구에게나 오는 마디와 사람마다 다른 구간. 규칙의 원본은 앱 저장소 release-1.0.0 F4 와 앱 결정 012,
// 이 파일이 고른 것(해 = 처음 겹치는 때, 시각 모름의 영역 등)은 docs/decisions/008-report-timing.md.

/** 누구에게나 오는 마디. 시간 순서다. */
enum class MilestoneKind { SATURN_SQUARE, SATURN_RETURN, URANUS_OPPOSITION, SECOND_SATURN_RETURN }

enum class IntervalKind { CHANGE, OPPORTUNITY, FATED }

/** 구간이 닿는 삶의 영역: 사람·인연 / 일 / 돈 / 나 자신. */
enum class LifeArea { PEOPLE, WORK, MONEY, SELF }

enum class ChangeSize { BIG, SMALL }

/** 지나가는 행성이 닿는 내 차트의 자리. `HOUSE_n` 은 목성이 그 하우스를 지나는 기간이다. */
enum class TransitTarget { SUN, MOON, VENUS, ASCENDANT, MIDHEAVEN, VERTEX, HOUSE_5, HOUSE_7, HOUSE_11 }

/**
 * [exact] 는 처음 정확히 겹치는 때이고 [year]·[age](만 나이)는 그때의 것이다.
 * [from]~[to] 는 허용 오차 안에 있는 기간이다(역행으로 여러 번 지나가면 처음 들어온 때부터 마지막으로 나간 때까지).
 */
data class Milestone(
	val kind: MilestoneKind,
	val exact: Instant,
	val from: Instant,
	val to: Instant,
	val year: Int,
	val age: Int,
	val size: ChangeSize,
)

/** [aspect] 는 하우스 통과(`HOUSE_n`)면 null. [size] 는 변화 구간에만 있다. */
data class Interval(
	val kind: IntervalKind,
	val mover: Body,
	val aspect: AspectType?,
	val target: TransitTarget,
	val from: Instant,
	val to: Instant,
	val area: LifeArea,
	val size: ChangeSize?,
)

/** 구간 허용 오차(도). 지나가는 행성이 대상과 이 안에 있는 기간이 한 구간이다. */
private const val ORB_DEGREES = 1.0

/** 태어난 뒤부터 만 80세 전까지 시작하는 구간을 계산한다. */
private const val LIFE_YEARS = 80L

/** 만 80세 직전에 시작한 구간의 끝을 보려고 이만큼 더 본다(가장 긴 구간도 몇 해다). */
private const val LOOK_AHEAD_YEARS = 5L

private const val DAY_MILLIS = 86_400_000L

private val CHANGE_MOVERS = listOf(Body.SATURN, Body.URANUS, Body.NEPTUNE, Body.PLUTO)
private val FATED_MOVERS = listOf(Body.JUPITER) + CHANGE_MOVERS
private val BIG_MOVERS = setOf(Body.URANUS, Body.NEPTUNE, Body.PLUTO)
private val LIGHTS = setOf(TransitTarget.SUN, TransitTarget.MOON)

// 대상보다 이만큼 앞선 황경에 왔을 때가 그 각도다. 사분과 삼분은 앞뒤 두 곳이 있다.
private val CHANGE_ASPECTS = listOf(
	0.0 to AspectType.CONJUNCTION, 90.0 to AspectType.SQUARE, 180.0 to AspectType.OPPOSITION, 270.0 to AspectType.SQUARE,
)
private val OPPORTUNITY_ASPECTS = listOf(0.0 to AspectType.CONJUNCTION, 120.0 to AspectType.TRINE, 240.0 to AspectType.TRINE)
private val OPPORTUNITY_HOUSES = listOf(5 to TransitTarget.HOUSE_5, 7 to TransitTarget.HOUSE_7, 11 to TransitTarget.HOUSE_11)

/**
 * 마디 네 개: 토성 사각(태어난 자리에서 270도, 만 21~22세 무렵), 토성 귀환(360도), 천왕성 대립(180도), 두 번째 토성 귀환(720도).
 * 해와 만 나이는 [zone] 의 달력으로 센다.
 */
fun computeMilestones(birthUtc: Instant, zone: ZoneId): List<Milestone> {
	val saturn = transitPath(Body.SATURN, birthUtc)
	val uranus = transitPath(Body.URANUS, birthUtc)
	fun milestone(kind: MilestoneKind, path: TransitPath, degreesFromBirth: Double, size: ChangeSize): Milestone {
		val level = path.unwrapped[0] + degreesFromBirth
		val exact = checkNotNull(path.firstCrossing(level)) { "$kind 가 계산 범위 안에 오지 않는다" }
		val (from, to) = path.passes(level - ORB_DEGREES, 2 * ORB_DEGREES).first { exact in it.first..it.second }
		val birthDate = birthUtc.atZone(zone).toLocalDate()
		val exactDate = Instant.ofEpochMilli(exact).atZone(zone).toLocalDate()
		return Milestone(
			kind = kind,
			exact = Instant.ofEpochMilli(exact),
			from = Instant.ofEpochMilli(from),
			to = Instant.ofEpochMilli(to),
			year = exactDate.year,
			age = ChronoUnit.YEARS.between(birthDate, exactDate).toInt(),
			size = size,
		)
	}
	return listOf(
		milestone(MilestoneKind.SATURN_SQUARE, saturn, 270.0, ChangeSize.SMALL),
		milestone(MilestoneKind.SATURN_RETURN, saturn, 360.0, ChangeSize.BIG),
		milestone(MilestoneKind.URANUS_OPPOSITION, uranus, 180.0, ChangeSize.BIG),
		milestone(MilestoneKind.SECOND_SATURN_RETURN, saturn, 720.0, ChangeSize.BIG),
	)
}

/**
 * 태어난 뒤부터 만 80세 전까지 시작하는 변화·기회·운명적 만남 구간. 시작 순서다. [chart] 는 [birthUtc] 의 차트여야 한다.
 * 태어날 때 이미 허용 오차 안에 있던 구간은 넣지 않는다(시작이 생일이 되어 저장한 글에 출생 날짜가 남는다).
 * 끝은 만 80세에서 자르지 않는다(같은 이유). 시각을 모르는 차트는 상승점·중천·하우스·버텍스가 없으므로 그것이 대상인 구간(운명적 만남 전부 포함)이 없다.
 */
fun computeIntervals(chart: Chart, birthUtc: Instant): List<Interval> {
	val paths = FATED_MOVERS.associateWith { transitPath(it, birthUtc) }
	val birth = birthUtc.toEpochMilli()
	val lastStart = birthUtc.atOffset(ZoneOffset.UTC).plusYears(LIFE_YEARS).toInstant().toEpochMilli()
	val intervals = mutableListOf<Interval>()
	fun add(kind: IntervalKind, mover: Body, aspect: AspectType?, target: TransitTarget, bandStart: Double, bandWidth: Double) {
		for ((from, to) in paths.getValue(mover).passes(bandStart, bandWidth)) {
			if (from <= birth || from >= lastStart || from >= to) continue
			intervals += Interval(
				kind, mover, aspect, target, Instant.ofEpochMilli(from), Instant.ofEpochMilli(to),
				area = areaOf(kind, mover, target, chart),
				size = if (kind == IntervalKind.CHANGE) changeSize(mover, aspect, target) else null,
			)
		}
	}
	fun addAspects(kind: IntervalKind, mover: Body, target: TransitTarget, longitude: Double, aspects: List<Pair<Double, AspectType>>) {
		for ((ahead, aspect) in aspects) add(kind, mover, aspect, target, longitude + ahead - ORB_DEGREES, 2 * ORB_DEGREES)
	}
	fun natal(body: Body): Double = chart.positions.first { it.body == body }.longitude

	val changeTargets = listOfNotNull(
		TransitTarget.SUN to natal(Body.SUN),
		TransitTarget.MOON to natal(Body.MOON),
		chart.houses?.let { TransitTarget.ASCENDANT to it.ascendant },
	)
	val opportunityTargets = changeTargets + listOfNotNull(
		chart.houses?.let { TransitTarget.MIDHEAVEN to it.midheaven },
		TransitTarget.VENUS to natal(Body.VENUS),
	)
	for (mover in CHANGE_MOVERS) {
		for ((target, longitude) in changeTargets) addAspects(IntervalKind.CHANGE, mover, target, longitude, CHANGE_ASPECTS)
	}
	for ((target, longitude) in opportunityTargets) {
		addAspects(IntervalKind.OPPORTUNITY, Body.JUPITER, target, longitude, OPPORTUNITY_ASPECTS)
	}
	chart.houses?.let { houses ->
		for ((house, target) in OPPORTUNITY_HOUSES) {
			val start = houses.cusps[house - 1]
			add(IntervalKind.OPPORTUNITY, Body.JUPITER, null, target, start, normalizeDegrees(houses.cusps[house % 12] - start))
		}
		for (mover in FATED_MOVERS) {
			add(IntervalKind.FATED, mover, AspectType.CONJUNCTION, TransitTarget.VERTEX, houses.vertex - ORB_DEGREES, 2 * ORB_DEGREES)
		}
	}
	return intervals.sortedWith(compareBy({ it.from }, { it.to }))
}

/** 하우스가 뜻하는 삶의 영역(F4): 사람·인연 5·7·11, 일 6·10, 돈 2·8, 나 자신 1·3·4·9·12. */
fun areaOfHouse(house: Int): LifeArea = when (house) {
	5, 7, 11 -> LifeArea.PEOPLE
	6, 10 -> LifeArea.WORK
	2, 8 -> LifeArea.MONEY
	else -> LifeArea.SELF
}

// 큰 변화: 천왕성·해왕성·명왕성이 태양·달과 합·충일 때. 그 밖(토성 통과, 상승점 대상, 사분)은 작은 변화.
private fun changeSize(mover: Body, aspect: AspectType?, target: TransitTarget): ChangeSize {
	val big = mover in BIG_MOVERS && target in LIGHTS && (aspect == AspectType.CONJUNCTION || aspect == AspectType.OPPOSITION)
	return if (big) ChangeSize.BIG else ChangeSize.SMALL
}

// 영역은 자극받는 하우스(대상이 놓인 하우스)로 정한다. 운명적 만남은 언제나 사람·인연이다.
// 시각을 모르면 하우스가 없어 행성의 뜻으로 정한다: 변화 구간은 지나가는 행성(토성 → 일, 그 밖 → 나 자신),
// 기회 구간은 대상(금성 → 사람·인연, 태양·달 → 나 자신). 결정 008.
private fun areaOf(kind: IntervalKind, mover: Body, target: TransitTarget, chart: Chart): LifeArea {
	fun houseOfNatal(body: Body): Int? = chart.positions.first { it.body == body }.house
	val house = when (target) {
		TransitTarget.SUN -> houseOfNatal(Body.SUN)
		TransitTarget.MOON -> houseOfNatal(Body.MOON)
		TransitTarget.VENUS -> houseOfNatal(Body.VENUS)
		TransitTarget.ASCENDANT -> 1
		TransitTarget.MIDHEAVEN -> 10
		TransitTarget.HOUSE_5 -> 5
		TransitTarget.HOUSE_7 -> 7
		TransitTarget.HOUSE_11 -> 11
		TransitTarget.VERTEX -> return LifeArea.PEOPLE
	}
	if (house != null) return areaOfHouse(house)
	return when {
		kind == IntervalKind.CHANGE && mover == Body.SATURN -> LifeArea.WORK
		kind == IntervalKind.OPPORTUNITY && target == TransitTarget.VENUS -> LifeArea.PEOPLE
		else -> LifeArea.SELF
	}
}

/**
 * 한 행성이 태어난 때부터 만 85세까지 지나간 길. [unwrapped] 는 360도에서 접지 않고 이어 붙인 황경이라
 * 한 바퀴 돌 때마다 360씩 커진다(처음 값은 태어난 때의 황경). 역행하면 잠깐 줄어든다.
 */
private class TransitPath(val times: LongArray, val unwrapped: DoubleArray) {

	/**
	 * 황경이 띠 [[start], [start] + [width]] (360도마다 되풀이) 안에 있던 기간(1970년부터의 밀리초).
	 * 같은 바퀴에서 역행으로 여러 번 드나들면 처음 들어온 때부터 마지막으로 나간 때까지 한 기간으로 묶는다.
	 * 계산 범위의 처음이나 끝에 이미 안에 있으면 그 끝에서 자른다. 값 사이는 직선으로 잇는다.
	 */
	fun passes(start: Double, width: Double): List<Pair<Long, Long>> {
		val found = sortedMapOf<Int, Pair<Long, Long>>()
		for (i in 0 until times.size - 1) {
			val lapBefore = floor((unwrapped[i] - start) / 360.0).toInt()
			val lapAfter = floor((unwrapped[i + 1] - start) / 360.0).toInt()
			for (lap in if (lapBefore == lapAfter) listOf(lapBefore) else listOf(lapBefore, lapAfter)) {
				// 이 바퀴의 띠 아래 끝에서 잰 높이. 0 이상 width 이하면 안이다.
				val before = unwrapped[i] - start - 360.0 * lap
				val after = unwrapped[i + 1] - start - 360.0 * lap
				var enter = 0.0
				var leave = 1.0
				if (before != after) {
					val atBottom = (0.0 - before) / (after - before)
					val atTop = (width - before) / (after - before)
					enter = max(enter, min(atBottom, atTop))
					leave = min(leave, max(atBottom, atTop))
				} else if (before < 0.0 || before > width) {
					continue
				}
				if (enter > leave) continue
				val from = between(i, enter)
				val to = between(i, leave)
				found.merge(lap, from to to) { old, new -> min(old.first, new.first) to max(old.second, new.second) }
			}
		}
		return found.values.toList()
	}

	/** 이어 붙인 황경이 [level] 을 처음 넘는 때. 범위 안에 없으면 null. */
	fun firstCrossing(level: Double): Long? {
		for (i in 0 until times.size - 1) {
			if (unwrapped[i] < level && unwrapped[i + 1] >= level) {
				return between(i, (level - unwrapped[i]) / (unwrapped[i + 1] - unwrapped[i]))
			}
		}
		return null
	}

	private fun between(i: Int, fraction: Double): Long = times[i] + ((times[i + 1] - times[i]) * fraction).toLong()
}

// 위치를 구하는 간격(일). 가장 빠를 때도 한 간격에 0.3도를 넘지 않게 잡았다(목성 0.25도/일, 토성 0.14, 천왕성 이후 0.07 이하).
// 허용 오차 띠(2도)보다 훨씬 좁아 띠를 건너뛰지 못한다.
private fun stepDays(body: Body): Long = when (body) {
	Body.JUPITER -> 1
	Body.SATURN -> 2
	else -> 4
}

private fun transitPath(body: Body, birthUtc: Instant): TransitPath {
	val start = birthUtc.toEpochMilli()
	val end = birthUtc.atOffset(ZoneOffset.UTC).plusYears(LIFE_YEARS + LOOK_AHEAD_YEARS).toInstant().toEpochMilli()
	val step = stepDays(body) * DAY_MILLIS
	val times = (generateSequence(start) { it + step }.takeWhile { it < end } + end).toList().toLongArray()
	val unwrapped = DoubleArray(times.size)
	var previous = 0.0
	for (i in times.indices) {
		val longitude = apparentLongitude(body, Time.fromMillisecondsSince1970(times[i]))
		unwrapped[i] = if (i == 0) longitude else unwrapped[i - 1] + signedDifference(longitude, previous)
		previous = longitude
	}
	return TransitPath(times, unwrapped)
}
