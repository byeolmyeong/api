package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 지나가는 별(결정 032 · 034, 규칙의 원본은 문구표 초안 docs/drafts/2026-11-20-month-phrases.md 2 · 4 · 6절).
 * 기준값: 2026년 12월 동지(해의 겉보기 황경 270°)는 2026-12-21 20:50 UTC 다(천문 연감). 한국 정오(03:00 UTC)로 재면
 * 12-21 은 약 269.24° · 12-22 는 약 270.26° · 12-23 은 약 271.28° 라 270° 에 가장 가까운 날은 12-22(오차 약 0.26°)다.
 */
class TransitsTest {

	private val december = YearMonth.of(2026, 12)
	private val solsticeDay = LocalDate.of(2026, 12, 22)

	private fun position(body: Body, longitude: Double) =
		PlanetPosition(body, longitude, signOf(longitude), degreeInSign(longitude), retrograde = false, speed = 1.0)

	/** 천체마다 황경(없는 천체는 [rest]), [angles] 가 있으면 시각을 아는 차트(상승점 · 중천의 황경). */
	private fun chart(longitudes: Map<Body, Double>, rest: Double, angles: Pair<Double, Double>? = null) = Chart(
		timeKnown = angles != null,
		positions = Body.entries.map { position(it, longitudes[it] ?: rest) },
		points = emptyList(),
		houses = angles?.let { (asc, mc) -> Houses(ascendant = asc, midheaven = mc, vertex = 0.0, cusps = emptyList()) },
		aspects = emptyList(),
		moonSignMayChange = false,
	)

	/** 그날 한국 정오에 지나가는 다섯 별 어느 것과도 어느 각도로든 [margin]° 안에 들지 않는 황경. */
	private fun quietLongitude(date: LocalDate, margin: Double = 1.5): Double {
		val noon = Instant.parse("${date}T03:00:00Z")
		val movers = computePositions(noon).filter { it.body in DAY_MOVERS }.map { it.longitude }
		return generateSequence(0.0) { it + 0.25 }.takeWhile { it < 360.0 }.first { natal ->
			movers.all { mover -> AspectType.entries.all { abs(abs(signedDifference(mover, natal)) - it.angle) > margin } }
		}
	}

	@Test
	fun `그달에서 가장 정확한 날을 조합마다 하나 고른다 - 동지의 해`() {
		val quiet = quietLongitude(solsticeDay)
		val natal = chart(mapOf(Body.MOON to 270.0, Body.MERCURY to 0.0, Body.VENUS to 210.0), rest = quiet)

		val candidates = monthTransitCandidates(natal, december)

		val conj = assertNotNull(candidates.singleOrNull { it.combination == "SUN.MOON.CONJUNCTION" })
		assertEquals(solsticeDay, conj.date)
		assertTrue(conj.orb in 0.2..0.32, "오차 ${conj.orb}")
		// 점수 = 각도(합 5) × 지나가는 해 3 × 내 달 3 × (1 − 오차 ÷ 2)
		assertEquals(5 * 3 * 3 * (1 - conj.orb / 2), conj.score, 1e-9)
		assertEquals(solsticeDay, candidates.single { it.combination == "SUN.MERCURY.SQUARE" }.date)
		assertEquals(solsticeDay, candidates.single { it.combination == "SUN.VENUS.SEXTILE" }.date)
		assertTrue(candidates.all { it.orb <= 1.0 }, "허용 오차 1°")
		assertEquals(candidates.map { it.combination }.distinct(), candidates.map { it.combination }, "조합마다 하나")
		assertTrue(candidates.all { it.date.month == december.month && it.date.year == 2026 })
		assertTrue(candidates.all { it.mover in DAY_MOVERS }, "달 · 토성 이후는 지나가는 별이 아니다")
		assertEquals(candidates, monthTransitCandidates(natal, december), "같은 입력 같은 결과")
	}

	@Test
	fun `지나가는 해와 내 해의 합(생일)은 후보가 아니고 시각을 모르면 상승점 · 중천이 대상이 아니다`() {
		val quiet = quietLongitude(solsticeDay)
		val unknown = monthTransitCandidates(chart(mapOf(Body.SUN to 270.0), rest = quiet), december)
		assertFalse(unknown.any { it.combination == "SUN.SUN.CONJUNCTION" }, "생일")
		assertTrue(unknown.none { it.target == "ASC" || it.target == "MC" })

		val known = monthTransitCandidates(chart(emptyMap(), rest = quiet, angles = 270.0 to 0.0), december)
		val asc = known.single { it.combination == "SUN.ASC.CONJUNCTION" }
		assertEquals(solsticeDay, asc.date)
		assertEquals(5 * 3 * 2.5 * (1 - asc.orb / 2), asc.score, 1e-9, "상승점 · 중천의 무게 2.5")
		assertEquals(solsticeDay, known.single { it.combination == "SUN.MC.SQUARE" }.date)
	}

	private fun day(date: Int, combination: String, score: Double): SkyTransit {
		val (mover, target, type) = combination.split(".")
		return SkyTransit(LocalDate.of(2026, 12, date), Body.valueOf(mover), target, AspectType.valueOf(type), orb = 0.1, score = score)
	}

	@Test
	fun `눈여겨볼 날 - 점수 8 이상, 사흘 간격, 카테고리 먼저, 셋까지, 날짜 순`() {
		val categories = mapOf("SUN.MOON" to "MIND", "MERCURY.SUN" to "PEOPLE", "MARS.SATURN" to "WORK", "VENUS.VENUS" to "PEOPLE", "JUPITER.MOON" to "MIND")
		fun categoryOf(transit: SkyTransit) = categories.getValue("${transit.mover}.${transit.target}")
		val candidates = listOf(
			day(10, "SUN.MOON.CONJUNCTION", 40.0),
			day(11, "MERCURY.SUN.TRINE", 30.0), // 10일과 하루 차이 — 사흘이 안 돼 빠진다
			day(14, "VENUS.VENUS.CONJUNCTION", 29.0), // 사람 · 첫째 훑기에서 고른다
			day(20, "JUPITER.MOON.TRINE", 28.0), // 마음은 이미 있다 — 첫째에서 건너뛰고 둘째에서 고려
			day(25, "MARS.SATURN.SQUARE", 9.0), // 일 · 첫째에서 고른다(점수가 낮아도 카테고리가 처음)
			day(28, "MERCURY.SUN.SEXTILE", 7.9), // 점수 8 미만은 버린다
		)

		val picked = pickNotableDays(candidates, ::categoryOf)

		assertEquals(listOf(10, 14, 25), picked.map { it.date.dayOfMonth }, "카테고리가 겹치지 않는 셋을 먼저")

		// 카테고리가 셋이 안 되면 둘째 훑기에서 날짜 조건만 지키며 점수 순으로 채운다
		val twoKinds = pickNotableDays(candidates.filter { it.date.dayOfMonth != 25 }, ::categoryOf)
		assertEquals(listOf(10, 14, 20), twoKinds.map { it.date.dayOfMonth })

		// 같은 점수면 이른 날, 그다음 조합 id 순
		val ties = pickNotableDays(listOf(day(20, "SUN.MOON.TRINE", 10.0), day(5, "SUN.MOON.SEXTILE", 10.0), day(6, "MERCURY.SUN.TRINE", 10.0)), ::categoryOf)
		assertEquals(listOf(5, 20), ties.map { it.date.dayOfMonth }, "5일(이른 날) 다음 6일은 사흘이 안 돼 빠진다")

		assertEquals(emptyList(), pickNotableDays(listOf(day(3, "SUN.MOON.TRINE", 7.99)), ::categoryOf), "후보가 없으면 빈 목록")
	}

	@Test
	fun `그날의 통과는 1도 안 다섯 별의 것을 세기 순으로 주고 시각을 모르면 상승점 · 중천이 없다`() {
		val quiet = quietLongitude(solsticeDay)
		val natal = chart(mapOf(Body.MOON to 270.0), rest = quiet)

		val transits = dayTransits(natal, solsticeDay)

		val conj = transits.single { it.combination == "SUN.MOON.CONJUNCTION" }
		assertEquals(solsticeDay, conj.date)
		assertTrue(transits.all { it.orb <= 1.0 && it.mover in DAY_MOVERS }, "달은 빼고 1° 안")
		assertEquals(transits.sortedByDescending { it.score }, transits, "세기 순")
		assertTrue(transits.none { it.target == "ASC" || it.target == "MC" })
		assertEquals(transits.first(), daySkyTransit(natal, solsticeDay), "그날의 하늘 근거는 가장 센 통과")

		val known = dayTransits(chart(mapOf(Body.MOON to 270.0), rest = quiet, angles = 270.0 to 0.0), solsticeDay)
		assertTrue(known.any { it.combination == "SUN.ASC.CONJUNCTION" } && known.any { it.combination == "SUN.MC.SQUARE" })
	}

	@Test
	fun `다섯 별의 통과가 없는 날은 달의 가장 정확한 각도가 그날의 하늘 근거다`() {
		val date = LocalDate.of(2026, 11, 20)
		val quiet = quietLongitude(date)
		val natal = chart(emptyMap(), rest = quiet)
		assertEquals(emptyList(), dayTransits(natal, date), "다섯 별이 어느 각도로도 1.5° 안에 오지 않는 자리")

		val reason = daySkyTransit(natal, date)

		assertEquals(Body.MOON, reason.mover)
		val moon = computePositions(Instant.parse("${date}T03:00:00Z")).single { it.body == Body.MOON }.longitude
		val best = AspectType.entries.minOf { abs(abs(signedDifference(moon, quiet)) - it.angle) }
		assertEquals(best, reason.orb, 1e-9, "내 점 모두와 각도 다섯 가운데 가장 정확한 것")
		assertEquals(date, reason.date)
		assertEquals(reason, daySkyTransit(natal, date), "같은 입력 같은 결과")
	}
}
