package com.byeolmyeong.api.sky

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.SkyTransit
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.dayTransits
import com.byeolmyeong.api.chart.daySkyTransit
import com.byeolmyeong.api.chart.notableDays
import com.byeolmyeong.api.generation.DayCategory
import com.byeolmyeong.api.generation.dayCategory
import com.byeolmyeong.api.generation.monthPhrases
import com.byeolmyeong.api.web.BadRequestException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 그날의 하늘(결정 034): 날짜 읽기 · 서버가 정하는 제목 · 칩 · 근거 · 모델 글의 검사. */
class DaySkyTest {

	private val today = LocalDate.of(2026, 11, 20)

	// 1990-03-15 14:30 서울(마포) — 통합 테스트와 같은 사람
	private val chart = computeChart(Instant.parse("1990-03-15T05:30:00Z"), 37.5663, 126.9019, timeKnown = true)

	@Test
	fun `날짜 - 없으면 한국 날짜의 오늘, 지난달 1일 ~ 다음 달 말일만, 형식이 틀리면 INVALID_DATE`() {
		assertEquals(today, parseDaySkyDate(null, today))
		assertEquals(LocalDate.of(2026, 10, 1), parseDaySkyDate("2026-10-01", today))
		assertEquals(LocalDate.of(2026, 12, 31), parseDaySkyDate("2026-12-31", today))
		assertEquals(LocalDate.of(2027, 1, 31), parseDaySkyDate("2027-01-31", LocalDate.of(2026, 12, 1)), "해를 넘는 다음 달")
		for (bad in listOf("", " ", "2026-1-01", "20261121", "2026/11/21", "2026-02-30", "2026-13-01", "2026-11-21T00:00", "abcd-ef-gh", " 2026-11-21", "+2026-11-21")) {
			assertEquals("INVALID_DATE", assertFailsWith<BadRequestException>(bad) { parseDaySkyDate(bad, today) }.code, bad)
		}
		for (far in listOf("2026-09-30", "2027-01-01", "2025-11-20")) {
			assertEquals("DATE_OUT_OF_RANGE", assertFailsWith<BadRequestException>(far) { parseDaySkyDate(far, today) }.code, far)
		}
	}

	@Test
	fun `눈여겨볼 날이면 제목 · 칩 · 근거가 그달의 문구표 값 그대로다`() {
		val month = YearMonth.of(2026, 11)
		val days = notableDays(chart, month) { dayCategory(it.mover, it.target) }
		val phrases = monthPhrases(days)
		assertTrue(days.isNotEmpty(), "고정 차트 · 고정 달에 눈여겨볼 날이 있다")
		for ((day, phrase) in days.zip(phrases)) {
			val plan = dayPlan(chart, day.date)
			assertEquals(phrase.text, plan.title, "${day.date}")
			assertEquals(phrase.category, plan.category)
			assertEquals(day, plan.transit)
		}
	}

	@Test
	fun `눈여겨볼 날이 아니면 제목은 비어 모델이 쓰고 근거는 그날의 하늘 근거다`() {
		val month = YearMonth.of(2026, 11)
		val notable = notableDays(chart, month) { dayCategory(it.mover, it.target) }.map { it.date }.toSet()
		val dates = (1..month.lengthOfMonth()).map(month::atDay).filter { it !in notable }
		for (date in dates) {
			val plan = dayPlan(chart, date)
			val transit = daySkyTransit(chart, date)
			assertNull(plan.title, "$date")
			assertEquals(transit, plan.transit)
			assertEquals(skyCategory(transit), plan.category)
			assertEquals(dayTransits(chart, date).take(DAY_TRANSIT_LINES), plan.transits)
		}
	}

	@Test
	fun `칩 - 다섯 별은 문구표의 칩 표, 달은 마음`() {
		fun transit(mover: Body, target: String) = SkyTransit(today, mover, target, AspectType.TRINE, orb = 0.5, score = 0.0)
		assertEquals(dayCategory(Body.MERCURY, "SUN"), skyCategory(transit(Body.MERCURY, "SUN")))
		assertEquals(DayCategory.WORK, skyCategory(transit(Body.MARS, "MC")))
		for (target in listOf("SUN", "VENUS", "MARS", "ASC", "MC")) assertEquals(DayCategory.MIND, skyCategory(transit(Body.MOON, target)), target)
	}

	@Test
	fun `그날의 재료 - 통과 · 이유 · 칩 · 제목, 날짜와 숫자가 없다`() {
		val date = LocalDate.of(2026, 11, 21)
		val plan = dayPlan(chart, date)
		val facts = dayFacts(plan)
		for (section in listOf("[그날의 통과]", "[이유]", "[칩]", "[제목]")) assertTrue(facts.contains(section), section)
		assertFalse(facts.any { it.isDigit() }, facts)
		assertFalse(facts.contains("°"))
	}

	private fun draft(
		title: String? = "가짜 제목이 반가운 날",
		people: String = "사람 쪽 흐름이에요",
		work: String = "일 쪽 흐름이에요",
		mind: String = "마음 쪽 흐름이에요",
		practices: List<String> = listOf("안부 한 줄 전하기", "할 일 하나 끝내 보기", "산책하며 숨 고르기"),
	) = DaySkyDraft(title, DayFlow(people, work, mind), practices)

	@Test
	fun `모델 글의 검사 - 칸 수 · 빈 글 · 숫자 · 금지 표현 · 실천 끝맺음`() {
		assertNull(daySkyProblem(draft()))
		assertNull(daySkyProblem(draft(title = null)), "정해진 제목이면 모델 제목이 없다")
		assertEquals("MALFORMED", daySkyProblem(draft(practices = listOf("안부 한 줄 전하기", "할 일 하나 끝내 보기"))))
		assertEquals("MALFORMED", daySkyProblem(draft(practices = List(4) { "안부 한 줄 전하기" })))
		assertEquals("MALFORMED", daySkyProblem(draft(work = " ")))
		assertEquals("MALFORMED", daySkyProblem(draft(title = "")))
		assertEquals("HAS_NUMBER", daySkyProblem(draft(people = "3시쯤 연락이 반가워요")))
		assertEquals("HAS_NUMBER", daySkyProblem(draft(title = "7번째로 좋은 날")))
		assertEquals("NOT_ADVICE_FORM", daySkyProblem(draft(practices = listOf("안부 한 줄 전해요", "할 일 하나 끝내 보기", "산책하며 숨 고르기"))))
		assertEquals("NOT_ADVICE_FORM", daySkyProblem(draft(mind = "반드시 좋은 일이 생겨요")))
		assertEquals("NOT_ADVICE_FORM", daySkyProblem(draft(people = "운명 같은 만남이 있어요")))
		assertEquals("NOT_ADVICE_FORM", daySkyProblem(draft(work = "일이 잘 풀리게 됩니다")))
		assertEquals("NOT_ADVICE_FORM", daySkyProblem(draft(work = "좋은 소식이 올 거예요")))
		assertEquals("FORBIDDEN_TOPIC", daySkyProblem(draft(mind = "건강을 챙기면 좋아요")))
		assertEquals("FORBIDDEN_TOPIC", daySkyProblem(draft(practices = listOf("주식 공부하기", "할 일 하나 끝내 보기", "산책하며 숨 고르기"))))
		assertEquals("FORBIDDEN_TOPIC", daySkyProblem(draft(work = "투자를 생각해 봐요")))
	}
}
