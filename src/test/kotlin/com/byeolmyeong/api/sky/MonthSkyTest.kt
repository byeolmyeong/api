package com.byeolmyeong.api.sky

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.TransitTarget
import com.byeolmyeong.api.generation.themeOf
import com.byeolmyeong.api.web.BadRequestException
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 이번 달(결정 032): 고를 달 읽기와 "이번 달 내내" 고르기(문구표 초안 5절 — 소유자 "초안 그대로 써"). */
class MonthSkyTest {

	private val today = LocalDate.of(2026, 11, 20)
	private val november = YearMonth.of(2026, 11)

	@Test
	fun `달 - 없으면 한국 날짜의 이번 달, 지난달 · 이번 달 · 다음 달만 받는다`() {
		assertEquals(november, parseMonth(null, today))
		assertEquals(november, parseMonth("", today), "빈 값은 없는 것")
		assertEquals(YearMonth.of(2026, 10), parseMonth("2026-10", today))
		assertEquals(YearMonth.of(2026, 12), parseMonth("2026-12", today))
		assertEquals(YearMonth.of(2027, 1), parseMonth("2027-01", LocalDate.of(2026, 12, 31)), "해를 넘는 다음 달")
		for (bad in listOf("2026-1", "2026-13", "2026-00", "26-11", "2026/11", "2026-11-01", "abcd-ef", " 2026-11", "+2026-11")) {
			assertEquals("INVALID_MONTH", assertFailsWith<BadRequestException>(bad) { parseMonth(bad, today) }.code, bad)
		}
		for (far in listOf("2026-09", "2027-01", "2025-11")) {
			assertEquals("MONTH_OUT_OF_RANGE", assertFailsWith<BadRequestException>(far) { parseMonth(far, today) }.code, far)
		}
	}

	private val korea = ZoneId.of("Asia/Seoul")

	private fun interval(kind: IntervalKind, from: String, to: String, area: LifeArea = LifeArea.WORK, size: ChangeSize? = null, mover: Body = Body.SATURN) = Interval(
		kind = kind,
		mover = mover,
		aspect = AspectType.SQUARE,
		target = TransitTarget.SUN,
		from = LocalDate.parse(from).atTime(9, 0).atZone(korea).toInstant(),
		to = LocalDate.parse(to).atTime(9, 0).atZone(korea).toInstant(),
		area = area,
		size = size,
	)

	@Test
	fun `이번 달 내내 - 그달과 10일 이상 겹치는 구간, 우선순위 순으로 제목이 겹치지 않게 둘까지`() {
		val opportunity = interval(IntervalKind.OPPORTUNITY, "2026-10-01", "2026-12-31", area = LifeArea.SELF, mover = Body.JUPITER)
		val small = interval(IntervalKind.CHANGE, "2026-11-21", "2027-03-01", size = ChangeSize.SMALL) // 11-21 ~ 11-30 = 10일
		val nineDays = interval(IntervalKind.CHANGE, "2026-11-22", "2027-03-01", area = LifeArea.PEOPLE, size = ChangeSize.BIG, mover = Body.PLUTO) // 9일 — 빠진다
		val fated = interval(IntervalKind.FATED, "2026-11-01", "2026-11-30", area = LifeArea.PEOPLE, mover = Body.JUPITER)
		val before = interval(IntervalKind.CHANGE, "2026-01-01", "2026-10-31", size = ChangeSize.BIG, mover = Body.URANUS)

		val themes = monthThemes(listOf(opportunity, small, nineDays, fated, before), november)

		assertEquals(listOf(themeOf(small).title, themeOf(fated).title), themes.map { it.title }, "변화(작은) > 운명적 만남 > 기회")
		assertEquals(MonthTheme(LifeArea.WORK, themeOf(small).title, LocalDate.of(2026, 11, 21), LocalDate.of(2027, 3, 1)), themes[0])
	}

	@Test
	fun `같은 우선순위면 그달과 더 많이 겹치는 것, 그다음 늦게 시작한 것 - 같은 제목은 한 번만`() {
		// 기회 · 일 구간은 제목이 같다("일에서 기회를 넓혀 보기 좋은 시기")
		val wide = interval(IntervalKind.OPPORTUNITY, "2026-10-01", "2026-12-31", mover = Body.JUPITER)
		val sameTitle = interval(IntervalKind.OPPORTUNITY, "2026-11-01", "2026-11-25", mover = Body.JUPITER)
		val people = interval(IntervalKind.OPPORTUNITY, "2026-11-05", "2026-11-30", area = LifeArea.PEOPLE, mover = Body.JUPITER)
		assertEquals(themeOf(wide).title, themeOf(sameTitle).title)

		val themes = monthThemes(listOf(sameTitle, people, wide), november)

		assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 5)), themes.map { it.from }, "30일 겹침 > 26일 · 같은 제목(25일)은 건너뛴다")

		// 겹치는 날 수도 같으면 늦게 시작한 것
		val early = interval(IntervalKind.CHANGE, "2026-10-01", "2026-12-31", size = ChangeSize.SMALL, mover = Body.SATURN)
		val late = interval(IntervalKind.CHANGE, "2026-11-01", "2026-12-31", area = LifeArea.SELF, size = ChangeSize.SMALL, mover = Body.URANUS)
		assertEquals(listOf(late.from, early.from).map { it.atZone(korea).toLocalDate() }, monthThemes(listOf(early, late), november).map { it.from })

		assertEquals(emptyList(), monthThemes(emptyList(), november))
	}
}
