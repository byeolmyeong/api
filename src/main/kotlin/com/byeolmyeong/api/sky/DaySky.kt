package com.byeolmyeong.api.sky

import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.SkyTransit
import com.byeolmyeong.api.chart.dayTransits
import com.byeolmyeong.api.chart.daySkyTransit
import com.byeolmyeong.api.chart.notableDays
import com.byeolmyeong.api.generation.DayCategory
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.Reason
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.adviceProblem
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.dayCategory
import com.byeolmyeong.api.generation.feel
import com.byeolmyeong.api.generation.label
import com.byeolmyeong.api.generation.monthPhrases
import com.byeolmyeong.api.generation.pointLabel
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import com.byeolmyeong.api.web.BadRequestException
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

// 그날의 하늘(결정 034)의 모양 · 날짜 읽기 · 서버가 정하는 것 · 모델에게 맡기는 것 · 검증. 형태의 원본은 docs/architecture.md "API 계약" 의 "그날의 하늘".
// 날짜는 한국 달력의 날짜다(`YYYY-MM-DD`). 날짜 · 도수는 모델에게 넘기지 않는다.

/** 그날의 흐름(시안 14-4): 사람 · 일 · 마음 한두 문장. */
data class DayFlow(val people: String, val work: String, val mind: String)

/** 그날의 작은 실천 하나. [title] 은 "~기" 로 끝난다(결정 037). */
data class DayPractice(val title: String)

/** `POST /day-sky` 의 응답. [createdAt] 은 그 글을 만든 때(UTC, 초까지). */
data class DaySky(
	val date: LocalDate,
	val title: String,
	val category: DayCategory,
	val flow: DayFlow,
	val reason: Reason,
	val practices: List<DayPractice>,
	val createdAt: String,
)

/** 저장하는 본문(`day_skies.body`). 날짜와 만든 때는 행의 칸이다. */
data class DaySkyBody(val title: String, val category: DayCategory, val flow: DayFlow, val reason: Reason, val practices: List<DayPractice>) {
	fun toDaySky(date: LocalDate, createdAt: String): DaySky = DaySky(date, title, category, flow, reason, practices, createdAt)
}

/** `POST /day-sky` 의 요청 본문. 값은 [parseDaySkyDate] 가 본다. */
data class DaySkyRequest(val date: String?)

private val DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * 그날. 없으면(본문이 없거나 `date` 가 없거나 null) [today](한국 날짜)의 오늘 — 오늘의 하늘. 형식(`YYYY-MM-DD`, 있는 날)이 틀리면(빈 글자 포함)
 * 400 `INVALID_DATE`, 지난달 1일 ~ 다음 달 말일 밖이면 400 `DATE_OUT_OF_RANGE`(결정 034 의 5).
 */
fun parseDaySkyDate(text: String?, today: LocalDate): LocalDate {
	if (text == null) return today
	if (!DATE_FORMAT.matches(text)) throw BadRequestException("INVALID_DATE")
	val date = try {
		LocalDate.parse(text)
	} catch (e: DateTimeParseException) {
		throw BadRequestException("INVALID_DATE")
	}
	val month = YearMonth.from(today)
	if (date < month.minusMonths(1).atDay(1) || date > month.plusMonths(1).atEndOfMonth()) throw BadRequestException("DATE_OUT_OF_RANGE")
	return date
}

/** "[그날의 통과]" 에 주는 줄 수(세기 순). */
const val DAY_TRANSIT_LINES = 5

/** 실천의 수. */
private const val PRACTICE_COUNT = 3

/**
 * 서버가 정한 그날: [title] 은 눈여겨볼 날이면 그달의 문구표 문구, 아니면 null(모델이 쓴다). [transit] 은 근거,
 * [category] 는 칩, [transits] 는 그날 1° 안 다섯 별의 통과(세기 순, [DAY_TRANSIT_LINES] 까지).
 */
data class DayPlan(val title: String?, val category: DayCategory, val transit: SkyTransit, val transits: List<SkyTransit>)

/** 칩(문구표 3절의 칩 표). 달은 그 표에 줄이 없어 마음으로 둔다(달 = 마음 — 시안 11-4 "마음을 비추는 별"). */
fun skyCategory(transit: SkyTransit): DayCategory =
	if (transit.mover == Body.MOON) DayCategory.MIND else dayCategory(transit.mover, transit.target)

/**
 * 그날 서버가 정하는 것(결정 034 의 3): 그날이 그달의 눈여겨볼 날이면 그달의 `monthPhrases(notableDays)` 에서 그날 줄의 문구 · 칩 · 근거를 그대로
 * (한 달 안 겹침 피하기 때문에 그날 하나로 다시 고르면 다를 수 있다 — 이번 달 목록과 같은 말이어야 한다). 아니면 근거는 `chart.daySkyTransit`.
 */
fun dayPlan(chart: Chart, date: LocalDate): DayPlan {
	val days = notableDays(chart, YearMonth.from(date)) { dayCategory(it.mover, it.target) }
	val transits = dayTransits(chart, date).take(DAY_TRANSIT_LINES)
	days.zip(monthPhrases(days)).firstOrNull { it.first.date == date }?.let { (day, phrase) ->
		return DayPlan(phrase.text, phrase.category, day, transits)
	}
	val transit = daySkyTransit(chart, date)
	return DayPlan(null, skyCategory(transit), transit, transits)
}

private val DayCategory.label: String
	get() = when (this) {
		DayCategory.PEOPLE -> "사람"
		DayCategory.WORK -> "일"
		DayCategory.MIND -> "마음"
	}

private fun transitFact(transit: SkyTransit): String = "지나가는 ${transit.mover.label} – 나의 ${pointLabel(transit.target)} · ${transit.type.feel}"

/** 차트 글 아래에 붙이는 그날의 재료: 통과 · 이유 · 칩 · 제목. 날짜 · 도수 · 오차는 넣지 않는다. */
fun dayFacts(plan: DayPlan): String = listOf(
	"[그날의 통과]",
	plan.transits.joinToString("\n") { "- ${transitFact(it)}" }.ifEmpty { "(없음)" },
	"",
	"[이유]",
	transitFact(plan.transit),
	"",
	"[칩]",
	plan.category.label,
	"",
	"[제목]",
	plan.title ?: "(정해지지 않음 — `title` 에 직접 쓴다)",
).joinToString("\n")

private val DAY_SKY_RULES: String by lazy { promptResource("day-sky.md") }
private val DAY_SKY_TIME_UNKNOWN: String by lazy { promptResource("report-time-unknown.md") }

/** 그날의 글을 쓰게 하는 요청. 제목이 정해진 날은 돌려받을 구조에 제목 칸이 없다. */
fun daySkyRequest(chart: Chart, plan: DayPlan): GenerationRequest {
	val string = mapOf("type" to "string")
	fun obj(properties: List<Pair<String, Any>>): Map<String, Any> = mapOf(
		"type" to "object",
		"properties" to properties.toMap(),
		"required" to properties.map { it.first },
		"additionalProperties" to false,
	)
	val properties = listOfNotNull(
		("title" to string).takeIf { plan.title == null },
		"flow" to obj(listOf("people" to string, "work" to string, "mind" to string)),
		"practices" to mapOf("type" to "array", "items" to string),
	)
	return GenerationRequest(
		purpose = "day_sky",
		instructions = listOfNotNull(WRITING_RULES, DAY_SKY_RULES, DAY_SKY_TIME_UNKNOWN.takeIf { !chart.timeKnown }).joinToString("\n\n"),
		userText = chartFacts(chart) + "\n\n" + dayFacts(plan),
		schema = obj(properties),
	)
}

/** 모델이 돌려준 글. [title] 은 제목이 정해지지 않은 날에만 있다. */
data class DaySkyDraft(val title: String?, val flow: DayFlow, val practices: List<String>)

/** 생성기의 응답을 글로 읽는다. 필드가 빠졌거나 타입이 틀리면 null. [needsTitle] 이면 제목도 있어야 한다. */
fun parseDaySkyDraft(root: JsonNode, needsTitle: Boolean): DaySkyDraft? {
	val flow = root["flow"] ?: return null
	return DaySkyDraft(
		title = if (needsTitle) root["title"].stringOrNull() ?: return null else null,
		flow = DayFlow(
			people = flow["people"].stringOrNull() ?: return null,
			work = flow["work"].stringOrNull() ?: return null,
			mind = flow["mind"].stringOrNull() ?: return null,
		),
		practices = root["practices"].stringsOrNull() ?: return null,
	)
}

// 건강 · 돈 불리기를 말하는 낱말(결정 034 의 7 — 그날의 하늘의 금지 표현). 흔한 꼴만 막는다 — 돌려 말한 것은 지시문이 맡는다.
private val FORBIDDEN_TOPICS = listOf("건강", "질병", "병원", "투자", "주식", "코인")

/**
 * 생성된 글을 저장해도 되는지 본다(결정 034 의 7). 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 빈 글, 실천이 3개가 아님
 * - `HAS_NUMBER`: 어느 글에든 숫자가 있다(날짜 · 도수를 지어내지 않게)
 * - `NOT_ADVICE_FORM`: 실천 제목이 "기" 로 끝나지 않거나 어느 글에든 단정 · 운명 · 앞일의 끝맺음이 있다(`generation.adviceProblem`)
 * - `FORBIDDEN_TOPIC`: 어느 글에든 건강 · 투자를 말하는 낱말이 있다
 */
fun daySkyProblem(draft: DaySkyDraft): String? {
	val texts = listOfNotNull(draft.title) + listOf(draft.flow.people, draft.flow.work, draft.flow.mind) + draft.practices
	if (draft.practices.size != PRACTICE_COUNT || texts.any { it.isBlank() }) return "MALFORMED"
	if (texts.any { text -> text.any(Char::isDigit) }) return "HAS_NUMBER"
	adviceProblem(draft.practices, texts)?.let { return it }
	if (texts.any { text -> FORBIDDEN_TOPICS.any { it in text } }) return "FORBIDDEN_TOPIC"
	return null
}

/** 검증을 통과한 글과 서버가 정한 것을 저장할 본문으로. 제목은 정해진 것이 있으면 그것, 아니면 모델의 것. */
fun daySkyBody(draft: DaySkyDraft, plan: DayPlan): DaySkyBody = DaySkyBody(
	title = plan.title ?: checkNotNull(draft.title) { "제목이 정해지지 않은 날은 모델의 제목이 있다" },
	category = plan.category,
	flow = draft.flow,
	reason = dayReason(plan.transit),
	practices = draft.practices.map(::DayPractice),
)
