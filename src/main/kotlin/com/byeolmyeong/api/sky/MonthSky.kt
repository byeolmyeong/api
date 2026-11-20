package com.byeolmyeong.api.sky

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.SkyTransit
import com.byeolmyeong.api.generation.DayCategory
import com.byeolmyeong.api.generation.DayPhrase
import com.byeolmyeong.api.generation.Reason
import com.byeolmyeong.api.generation.ReasonWhen
import com.byeolmyeong.api.generation.TransitBasis
import com.byeolmyeong.api.generation.intervalPriority
import com.byeolmyeong.api.generation.themeOf
import com.byeolmyeong.api.web.BadRequestException
import java.time.LocalDate
import java.time.YearMonth

// 이번 달(결정 032)의 모양과 고르기. 응답 형태의 원본은 docs/architecture.md "API 계약" 의 "이번 달".
// 날짜는 모두 한국 달력의 날짜다(`YYYY-MM-DD`).

/** "이번 달 내내" 한 줄: 그달에 걸친 시기 구간. [title] 은 시기의 표 제목(시기 풀이의 구간 이름과 같은 말 — 결정 033). */
data class MonthTheme(val area: LifeArea, val title: String, val from: LocalDate, val to: LocalDate)

/** 눈여겨볼 날 하나 = 달력의 점. [reason] 은 `{ when: DAY, items: [TRANSIT] }`. */
data class MonthDay(val date: LocalDate, val category: DayCategory, val text: String, val reason: Reason)

/** `GET /timing/month` 의 응답. [month] 는 `YYYY-MM`. */
data class MonthSky(val month: String, val timeKnown: Boolean, val themes: List<MonthTheme>, val days: List<MonthDay>)

private val MONTH_FORMAT = Regex("""\d{4}-(0[1-9]|1[0-2])""")

/**
 * 고를 달. 없거나 빈 값이면 [today](한국 날짜)의 이번 달. 형식(`YYYY-MM`)이 틀리면 400 `INVALID_MONTH`,
 * 지난달 · 이번 달 · 다음 달 밖이면 400 `MONTH_OUT_OF_RANGE`(달력은 앞뒤 한 달 — 결정 032 의 1).
 */
fun parseMonth(text: String?, today: LocalDate): YearMonth {
	val current = YearMonth.from(today)
	if (text.isNullOrEmpty()) return current
	if (!MONTH_FORMAT.matches(text)) throw BadRequestException("INVALID_MONTH")
	val month = YearMonth.parse(text)
	if (month !in listOf(current.minusMonths(1), current, current.plusMonths(1))) throw BadRequestException("MONTH_OUT_OF_RANGE")
	return month
}

/** "이번 달 내내" 가 되려면 그달의 날과 겹쳐야 하는 날 수(문구표 초안 5절). */
private const val MIN_OVERLAP_DAYS = 10

/** "이번 달 내내" 의 상한. */
private const val MAX_THEMES = 2

/**
 * "이번 달 내내"(문구표 초안 5절, 결정 032 의 2): 그달의 날과 10일 이상 겹치는 구간을 우선순위(변화 큰 > 작은 > 운명적 만남 > 기회),
 * 같으면 그달과 더 많이 겹치는 것, 그다음 늦게 시작한 것 순으로 세워 제목이 겹치지 않게 둘까지.
 */
fun monthThemes(intervals: List<Interval>, month: YearMonth): List<MonthTheme> {
	val first = month.atDay(1)
	val last = month.atEndOfMonth()
	fun date(interval: Interval, end: Boolean) = (if (end) interval.to else interval.from).atZone(KOREA_ZONE).toLocalDate()
	fun overlap(interval: Interval): Int {
		val from = maxOf(date(interval, end = false), first)
		val to = minOf(date(interval, end = true), last)
		return if (to < from) 0 else (to.toEpochDay() - from.toEpochDay() + 1).toInt()
	}
	val ordered = intervals.map { it to overlap(it) }.filter { it.second >= MIN_OVERLAP_DAYS }
		.sortedWith(compareBy<Pair<Interval, Int>> { intervalPriority(it.first.kind, it.first.size) }.thenByDescending { it.second }.thenByDescending { it.first.from })
	return ordered.map { (interval, _) -> MonthTheme(interval.area, themeOf(interval).title, date(interval, end = false), date(interval, end = true)) }
		.distinctBy { it.title }
		.take(MAX_THEMES)
}

/** 그날의 근거: 지나가는 별 · 내 점 · 각도(결정 031). 이번 달의 눈여겨볼 날과 그날의 하늘이 같이 쓴다. */
fun dayReason(transit: SkyTransit): Reason = Reason(ReasonWhen.DAY, listOf(TransitBasis(transit.mover, transit.target, transit.type)))

/** 눈여겨볼 날과 그 문구를 응답의 한 줄로. */
fun monthDay(day: SkyTransit, phrase: DayPhrase): MonthDay = MonthDay(day.date, phrase.category, phrase.text, dayReason(day))
