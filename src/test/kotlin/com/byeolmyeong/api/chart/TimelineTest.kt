package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 기준값: NASA JPL Horizons(DE441)의 지구 중심 겉보기 황경으로 만든 src/test/resources/chart/timeline-horizons-reference.tsv.
// 2026-11-11 에 받았다. 받은 방법과 계산 방법은 그 파일의 머리말과 chart/horizons/fetch.py · reference.py 에 있다.
// 출생 시각은 모두 테스트용 가짜 값이다. 상승점·중천·버텍스·하우스 경계가 대상인 구간은 대상 황경만
// Swiss Ephemeris 기준값(ChartReference.kt)이고 지나가는 행성의 황경은 Horizons 다.
class TimelineTest {

	private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

	private class Sample(val name: String, val utc: Instant, val lat: Double, val lon: Double, val timeKnown: Boolean) {
		val chart: Chart by lazy { computeChart(utc, lat, lon, timeKnown) }
		val intervals: List<Interval> by lazy { computeIntervals(chart, utc) }
	}

	private val a = Sample("A", SEOUL_1990.utc, SEOUL_1990.latitude, SEOUL_1990.longitude, timeKnown = true)
	private val b = Sample("B", BUSAN_1985.utc, BUSAN_1985.latitude, BUSAN_1985.longitude, timeKnown = true)

	// 시각 모름: 2000-06-15 한국 정오. 위경도는 쓰이지 않는다.
	private val c = Sample("C", Instant.parse("2000-06-15T03:00:00Z"), 0.0, 0.0, timeKnown = false)
	private val samples = listOf(a, b, c)

	private class ReferenceRow(val chart: String, val kind: String, val mover: String, val what: String, val target: String, val from: Instant, val to: Instant)

	private val reference: List<ReferenceRow> by lazy {
		val stream = checkNotNull(javaClass.getResourceAsStream("/chart/timeline-horizons-reference.tsv"))
		stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
			val cells = line.split("\t")
			ReferenceRow(cells[0], cells[1], cells[2], cells[3], cells[4], instant(cells[5]), instant(cells[6]))
		}
	}

	private fun instant(text: String): Instant = Instant.parse(text.replace("Z", ":00Z"))

	private fun month(instant: Instant): YearMonth = YearMonth.from(instant.atZone(seoul))

	private fun days(a: Instant, b: Instant): Double = abs(ChronoUnit.MINUTES.between(a, b)) / 1440.0

	@Test
	fun `마디의 해와 만 나이가 기준값과 같다`() {
		// 기준값 파일의 MILESTONE 줄에서 처음 정확히 겹치는 때를 한국 날짜로 옮긴 것(해 · 만 나이).
		// 토성 사각 · 토성 귀환 · 천왕성 대립 · 두 번째 토성 귀환 순서.
		val expected = mapOf(
			"A" to listOf(2011 to 21, 2020 to 29, 2034 to 44, 2049 to 58), // 1990-03-15 생: 2011-11-09 · 2020-01-17 · 2034-08-20 · 2049-02-21
			"B" to listOf(2007 to 21, 2014 to 29, 2029 to 43, 2044 to 58), // 1985-11-02 생: 2007-08-19 · 2014-12-08 · 2029-07-03 · 2044-01-19
			"C" to listOf(2022 to 21, 2029 to 29, 2043 to 43, 2059 to 58), // 2000-06-15 생: 2022-05-14 · 2029-08-24 · 2043-09-30 · 2059-05-29
		)
		for (sample in samples) {
			val milestones = computeMilestones(sample.utc, seoul)
			assertEquals(MilestoneKind.entries, milestones.map { it.kind }, sample.name)
			assertEquals(expected.getValue(sample.name), milestones.map { it.year to it.age }, sample.name)
		}
	}

	@Test
	fun `마디의 정확한 때와 1도 안에 있는 기간이 기준값과 같은 달이다`() {
		var maxDays = 0.0
		for (sample in samples) {
			val rows = reference.filter { it.chart == sample.name && it.kind == "MILESTONE" }.associateBy { it.what }
			assertEquals(4, rows.size)
			for (milestone in computeMilestones(sample.utc, seoul)) {
				val row = rows.getValue(milestone.kind.name)
				val firstExact = instant(row.target.substringBefore(","))
				val label = "${sample.name} ${milestone.kind}"
				assertEquals(month(firstExact), month(milestone.exact), "$label 정확한 때")
				assertEquals(month(row.from), month(milestone.from), "$label 시작")
				assertEquals(month(row.to), month(milestone.to), "$label 끝")
				assertTrue(!milestone.exact.isBefore(milestone.from) && !milestone.exact.isAfter(milestone.to), label)
				maxDays = maxOf(maxDays, days(firstExact, milestone.exact), days(row.from, milestone.from), days(row.to, milestone.to))
			}
		}
		println("마디 기준값과의 최대 차이: %.2f 일 (12건)".format(maxDays))
	}

	@Test
	fun `역행으로 세 번 지나가는 마디는 한 기간으로 묶이고 해는 처음 겹치는 때다`() {
		// A 의 토성 사각: 2011-11-08 · 2012-05-30 · 2012-07-21 세 번 정확히 겹친다(기준값 파일).
		val square = computeMilestones(a.utc, seoul).first { it.kind == MilestoneKind.SATURN_SQUARE }
		assertEquals(2011, square.year)
		assertEquals(YearMonth.of(2011, 10), month(square.from))
		assertEquals(YearMonth.of(2012, 8), month(square.to))
		// B 의 토성 귀환: 정확히 겹치는 것은 2014-12 한 번이지만 2015년에 1도 안으로 다시 들어왔다 나간다.
		val saturnReturn = computeMilestones(b.utc, seoul).first { it.kind == MilestoneKind.SATURN_RETURN }
		assertEquals(YearMonth.of(2014, 11), month(saturnReturn.from))
		assertEquals(YearMonth.of(2015, 9), month(saturnReturn.to))
	}

	@Test
	fun `마디의 크기는 토성 사각만 작고 나머지는 크다`() {
		assertEquals(
			listOf(ChangeSize.SMALL, ChangeSize.BIG, ChangeSize.BIG, ChangeSize.BIG),
			computeMilestones(a.utc, seoul).map { it.size },
		)
	}

	@Test
	fun `변화·기회·운명적 만남 구간의 개수와 시작·끝 달이 기준값과 같다`() {
		var compared = 0
		var maxDays = 0.0
		val mismatches = mutableListOf<String>()
		for (sample in samples) {
			val expected = reference.filter { it.chart == sample.name && it.kind != "MILESTONE" }
				.groupBy { listOf(it.kind, it.mover, it.what, it.target) }
			val actual = sample.intervals.groupBy { listOf(it.kind.name, it.mover.name, it.aspect?.name ?: "HOUSE", it.target.name) }
			assertEquals(expected.keys, actual.keys, "${sample.name}: 구간의 종류")
			for ((key, rows) in expected) {
				val found = actual.getValue(key).sortedBy { it.from }
				val label = "${sample.name} ${key.joinToString(" ")}"
				assertEquals(rows.size, found.size, "$label: 개수")
				for ((row, interval) in rows.sortedBy { it.from }.zip(found)) {
					compared++
					maxDays = maxOf(maxDays, days(row.from, interval.from), days(row.to, interval.to))
					if (month(row.from) != month(interval.from) || month(row.to) != month(interval.to)) {
						mismatches += "$label: 기준 ${row.from}~${row.to} 계산 ${interval.from}~${interval.to}"
					}
				}
			}
		}
		println("구간 기준값과의 최대 차이: %.2f 일 (%d건)".format(maxDays, compared))
		assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
		assertEquals(reference.count { it.kind != "MILESTONE" }, compared)
	}

	@Test
	fun `시각을 모르면 운명적 만남 구간과 상승점·중천·하우스·버텍스 구간이 없다`() {
		val needsTime = setOf(
			TransitTarget.ASCENDANT, TransitTarget.MIDHEAVEN, TransitTarget.VERTEX,
			TransitTarget.HOUSE_5, TransitTarget.HOUSE_7, TransitTarget.HOUSE_11,
		)
		assertEquals(0, c.intervals.count { it.kind == IntervalKind.FATED })
		assertEquals(0, c.intervals.count { it.target in needsTime })
		assertEquals(setOf(TransitTarget.SUN, TransitTarget.MOON, TransitTarget.VENUS), c.intervals.map { it.target }.toSet())
		// 시각을 알면 있다
		assertTrue(a.intervals.any { it.kind == IntervalKind.FATED })
		assertTrue(needsTime.all { target -> a.intervals.any { it.target == target } })
	}

	@Test
	fun `구간의 종류마다 지나가는 행성과 각도와 대상이 정해져 있다`() {
		val outer = setOf(Body.SATURN, Body.URANUS, Body.NEPTUNE, Body.PLUTO)
		for (interval in a.intervals + b.intervals + c.intervals) {
			when (interval.kind) {
				IntervalKind.CHANGE -> {
					assertTrue(interval.mover in outer)
					assertTrue(interval.target in setOf(TransitTarget.SUN, TransitTarget.MOON, TransitTarget.ASCENDANT))
					assertTrue(interval.aspect in setOf(AspectType.CONJUNCTION, AspectType.SQUARE, AspectType.OPPOSITION))
				}
				IntervalKind.OPPORTUNITY -> {
					assertEquals(Body.JUPITER, interval.mover)
					assertTrue(interval.aspect in setOf(AspectType.CONJUNCTION, AspectType.TRINE, null))
					assertEquals(interval.aspect == null, interval.target.name.startsWith("HOUSE_"))
				}
				IntervalKind.FATED -> {
					assertTrue(interval.mover in outer + Body.JUPITER)
					assertEquals(TransitTarget.VERTEX, interval.target)
					assertEquals(AspectType.CONJUNCTION, interval.aspect)
				}
			}
		}
	}

	@Test
	fun `구간은 태어난 뒤부터 만 80세 전에 시작하고 시작 순서다`() {
		for (sample in samples) {
			val lastStart = sample.utc.atOffset(ZoneOffset.UTC).plusYears(80).toInstant()
			assertTrue(sample.intervals.isNotEmpty())
			for (interval in sample.intervals) {
				assertTrue(interval.from.isBefore(interval.to), "${sample.name}: 시작이 끝보다 앞이다")
				assertTrue(interval.from.isAfter(sample.utc) && interval.from.isBefore(lastStart), "${sample.name}: 범위 밖 $interval")
				assertTrue(interval.to.isBefore(lastStart.plus(5 * 365, ChronoUnit.DAYS)), "${sample.name}: 끝나지 않는 구간 $interval")
			}
			assertEquals(sample.intervals.sortedBy { it.from }, sample.intervals)
		}
	}

	@Test
	fun `태어날 때 이미 안에 있던 구간은 넣지 않고 만 80세에 걸친 구간은 끝을 자르지 않는다`() {
		// A 는 태어날 때 목성이 11하우스 안에 있다(기준값: 목성 황경 90.6도, 11하우스 59.7~94.6도). 그 통과는 구간이 아니다.
		val firstHouse11 = a.intervals.first { it.target == TransitTarget.HOUSE_11 }
		assertTrue(month(firstHouse11.from) > YearMonth.of(1991, 1), firstHouse11.toString())
		for (sample in samples) {
			val birthday = sample.utc.atZone(seoul).toLocalDate()
			val eightieth = birthday.plusYears(80)
			for (interval in sample.intervals) {
				assertTrue(interval.from.atZone(seoul).toLocalDate() != birthday, "시작이 생일이다: $interval")
				assertTrue(interval.to.atZone(seoul).toLocalDate() != eightieth, "끝이 만 80세 생일이다: $interval")
			}
		}
		// 기준값 파일에서 만 80세를 넘어 끝나는 구간이 실제로 있다(A: 2070-03-15 뒤에 끝남)
		assertTrue(a.intervals.any { it.to.isAfter(a.utc.atOffset(ZoneOffset.UTC).plusYears(80).toInstant()) })
	}

	@Test
	fun `큰 변화는 천왕성·해왕성·명왕성이 태양·달과 합·충일 때뿐이고 변화가 아닌 구간에는 크기가 없다`() {
		val slow = setOf(Body.URANUS, Body.NEPTUNE, Body.PLUTO)
		val lights = setOf(TransitTarget.SUN, TransitTarget.MOON)
		val strong = setOf(AspectType.CONJUNCTION, AspectType.OPPOSITION)
		for (interval in a.intervals + c.intervals) {
			if (interval.kind != IntervalKind.CHANGE) {
				assertNull(interval.size)
				continue
			}
			val big = interval.mover in slow && interval.target in lights && interval.aspect in strong
			assertEquals(if (big) ChangeSize.BIG else ChangeSize.SMALL, interval.size, interval.toString())
		}
		assertTrue(a.intervals.any { it.size == ChangeSize.BIG })
		// 토성 통과 · 상승점 대상 · 사분은 작다
		assertTrue(a.intervals.filter { it.mover == Body.SATURN }.all { it.size != ChangeSize.BIG })
		assertTrue(a.intervals.filter { it.target == TransitTarget.ASCENDANT }.all { it.size != ChangeSize.BIG })
		assertTrue(a.intervals.filter { it.aspect == AspectType.SQUARE }.all { it.size != ChangeSize.BIG })
	}

	@Test
	fun `시각을 알면 영역은 대상이 놓인 하우스로 정한다`() {
		fun expectedArea(house: Int) = when (house) {
			5, 7, 11 -> LifeArea.PEOPLE
			6, 10 -> LifeArea.WORK
			2, 8 -> LifeArea.MONEY
			else -> LifeArea.SELF
		}
		for (sample in listOf(a, b)) {
			fun natalHouse(body: Body) = assertNotNull(sample.chart.positions.first { it.body == body }.house)
			for (interval in sample.intervals) {
				val expected = when (interval.target) {
					TransitTarget.SUN -> expectedArea(natalHouse(Body.SUN))
					TransitTarget.MOON -> expectedArea(natalHouse(Body.MOON))
					TransitTarget.VENUS -> expectedArea(natalHouse(Body.VENUS))
					TransitTarget.ASCENDANT -> LifeArea.SELF // 1하우스
					TransitTarget.MIDHEAVEN -> LifeArea.WORK // 10하우스
					TransitTarget.VERTEX -> LifeArea.PEOPLE // F4: 운명적 만남 구간의 영역은 사람·인연
					TransitTarget.HOUSE_5, TransitTarget.HOUSE_7, TransitTarget.HOUSE_11 -> LifeArea.PEOPLE
				}
				assertEquals(expected, interval.area, "${sample.name} $interval")
			}
		}
		// A 의 태양은 9하우스(ChartReference), 그래서 태양이 대상인 구간은 나 자신이다
		assertEquals(9, SEOUL_1990.sunHouse)
		assertTrue(a.intervals.filter { it.target == TransitTarget.SUN }.all { it.area == LifeArea.SELF })
	}

	@Test
	fun `시각을 모르면 영역은 행성의 뜻으로 정한다`() {
		for (interval in c.intervals) {
			val expected = when {
				interval.kind == IntervalKind.CHANGE && interval.mover == Body.SATURN -> LifeArea.WORK
				interval.kind == IntervalKind.OPPORTUNITY && interval.target == TransitTarget.VENUS -> LifeArea.PEOPLE
				else -> LifeArea.SELF
			}
			assertEquals(expected, interval.area, interval.toString())
		}
		assertEquals(setOf(LifeArea.WORK, LifeArea.PEOPLE, LifeArea.SELF), c.intervals.map { it.area }.toSet())
	}

	@Test
	fun `평생의 마디와 구간을 계산하는 시간을 잰다`() {
		// 처음 한 번은 JIT 전이라 느리다. 둘 다 적는다.
		val timings = (1..5).map {
			val started = System.nanoTime()
			computeMilestones(a.utc, seoul)
			val count = computeIntervals(a.chart, a.utc).size
			(System.nanoTime() - started) / 1_000_000 to count
		}
		println("평생 타임라인 계산: 처음 ${timings.first().first}ms, 그 뒤 ${timings.drop(1).map { it.first }}ms (구간 ${timings.first().second}개)")
		// 요청 스레드가 아니라 뒤쪽 일꾼이 계산한다. 그래도 한 번이 2초를 넘으면 계산 방식을 다시 본다.
		assertTrue(timings.drop(1).all { it.first < 2_000 }, timings.toString())
	}
}
