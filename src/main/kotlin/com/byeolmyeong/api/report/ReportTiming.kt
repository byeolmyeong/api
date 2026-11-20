package com.byeolmyeong.api.report

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.MilestoneKind
import com.byeolmyeong.api.chart.computeIntervals
import com.byeolmyeong.api.chart.computeMilestones
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.themeOf
import com.byeolmyeong.api.generation.timelineFact
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

// 심층 리포트의 시기 영역: 모양, 계산한 마디·구간에 글 붙이기, 모델에게 맡길 줄, 연도 대조, "지금 나의 구간".
// 응답 형태의 원본은 docs/architecture.md "API 계약", 고른 것의 이유는 docs/decisions/008-report-timing.md.
// 날짜는 모두 한국 달력의 날짜다(`YYYY-MM-DD`).

/** 마디 하나. [date]·[year]·[age] 는 처음 정확히 겹치는 때, [from]~[to] 는 허용 오차 안에 있는 기간. */
data class TimingMilestone(
	val kind: MilestoneKind,
	val date: LocalDate,
	val year: Int,
	val age: Int,
	val from: LocalDate,
	val to: LocalDate,
	val size: ChangeSize,
	val title: String,
	val body: String,
)

/** 구간 하나. [id] 는 그 리포트 안에서만 겹치지 않는다. [size] 는 변화 구간에만 있다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TimingInterval(
	val id: String,
	val kind: IntervalKind,
	val from: LocalDate,
	val to: LocalDate,
	val area: LifeArea,
	val size: ChangeSize?,
	val title: String,
	val body: String,
)

/**
 * 지금 나의 구간. [intervals] 는 [date](오늘)에 걸쳐 있는 구간(언제까지인지는 각 구간의 `to`),
 * [milestone] 은 오늘이 그 기간 안인 마디, [nextInterval]·[nextMilestone] 은 오늘 뒤에 처음 오는 것,
 * [nearestMilestone] 은 오늘에서 가장 가까운 마디(지났든 아니든)다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TimingNow(
	val date: LocalDate,
	val intervals: List<TimingInterval>,
	val milestone: TimingMilestone?,
	val nextInterval: TimingInterval?,
	val nextMilestone: TimingMilestone?,
	val nearestMilestone: MilestoneKind,
)

/** 시기 영역. [current] 는 저장하지 않고 읽을 때 붙인다. [timeKnown] 이 false 면 상승점·중천·하우스·버텍스 구간(운명적 만남 전부)이 없다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReportTiming(
	val timeKnown: Boolean,
	val milestones: List<TimingMilestone>,
	val intervals: List<TimingInterval>,
	val current: TimingNow? = null,
)

/** 모델에게 글을 맡길 수 있는 한 줄(마디 또는 구간). [line] 이 프롬프트에 가는 글이고 해만 담는다. */
data class TimingItem(
	val id: String,
	val milestone: Boolean,
	val from: LocalDate,
	val to: LocalDate,
	val line: String,
) {
	val fromYear: Int get() = from.year
	val toYear: Int get() = to.year
}

/** 계산한 시기(글은 모두 표의 글)와, 마디·구간마다 모델에게 줄 수 있는 줄. */
class TimingDraft(val timing: ReportTiming, val items: List<TimingItem>)

/** 모델이 돌려준 한 줄. 해는 받은 것을 옮겨 적은 값이다. */
data class TimingText(val id: String, val fromYear: Int, val toYear: Int, val title: String, val body: String)

/** 오늘 걸쳐 있거나 오늘부터 이만큼 안에 시작하는 구간에만 모델의 글을 붙인다. */
private const val AI_WINDOW_YEARS = 10L

/** 한 번에 맡기는 구간 수의 상한(시작 순서로). 마디 4개가 더 간다. */
private const val AI_MAX_INTERVALS = 30

/** 마디와 구간을 계산해 표의 글을 붙인다. [chart] 는 [birthUtc] 의 차트여야 한다. 한 번에 약 0.3초가 든다(결정 008). */
fun computeTiming(chart: Chart, birthUtc: Instant): TimingDraft {
	fun date(instant: Instant): LocalDate = instant.atZone(KOREA_ZONE).toLocalDate()
	val milestones = computeMilestones(birthUtc, KOREA_ZONE).map { milestone ->
		val theme = themeOf(milestone.kind)
		TimingMilestone(
			milestone.kind, date(milestone.exact), milestone.year, milestone.age, date(milestone.from), date(milestone.to),
			milestone.size, theme.title, theme.body,
		) to milestone
	}
	val intervals = computeIntervals(chart, birthUtc).mapIndexed { index, interval ->
		val theme = themeOf(interval)
		TimingInterval(
			"i${index + 1}", interval.kind, date(interval.from), date(interval.to), interval.area, interval.size, theme.title, theme.body,
		) to interval
	}
	val items = milestones.map { (shown, computed) ->
		TimingItem(shown.kind.name, milestone = true, shown.from, shown.to, line(shown.kind.name, shown.from, shown.to, timelineFact(computed)))
	} + intervals.map { (shown, computed) ->
		TimingItem(shown.id, milestone = false, shown.from, shown.to, line(shown.id, shown.from, shown.to, timelineFact(computed)))
	}
	return TimingDraft(ReportTiming(chart.timeKnown, milestones.map { it.first }, intervals.map { it.first }), items)
}

/** 모델에게 맡길 줄: 마디 전부와, [today] 에 걸쳐 있거나 그 뒤 10년 안에 시작하는 구간(시작 순서, 상한까지). */
fun timingItemsForModel(draft: TimingDraft, today: LocalDate): List<TimingItem> {
	val until = today.plusYears(AI_WINDOW_YEARS)
	return draft.items.filter { it.milestone } +
		draft.items.filter { !it.milestone && it.to >= today && it.from <= until }.take(AI_MAX_INTERVALS)
}

private fun line(id: String, from: LocalDate, to: LocalDate, description: String): String =
	"- $id | ${from.year}~${to.year} | $description"

private val TIMING_RULES: String by lazy { promptResource("report-timing.md") }
private val TIMING_TIME_UNKNOWN: String by lazy { promptResource("report-time-unknown.md") }

/** 시기 글을 쓰게 하는 요청. 차트 글 아래에 맡길 줄을 붙인다. 해는 서버가 계산한 값이고 모델은 옮겨 적기만 한다. */
fun reportTimingRequest(chart: Chart, items: List<TimingItem>): GenerationRequest {
	val string = mapOf("type" to "string")
	val integer = mapOf("type" to "integer")
	val item = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"id" to mapOf("type" to "string", "enum" to items.map { it.id }),
			"fromYear" to integer, "toYear" to integer, "title" to string, "body" to string,
		),
		"required" to listOf("id", "fromYear", "toYear", "title", "body"),
		"additionalProperties" to false,
	)
	return GenerationRequest(
		purpose = "report_timing",
		instructions = listOfNotNull(WRITING_RULES, TIMING_RULES, TIMING_TIME_UNKNOWN.takeIf { !chart.timeKnown }).joinToString("\n\n"),
		userText = chartFacts(chart) + "\n\n[시기 목록]\n" + items.joinToString("\n") { it.line },
		schema = mapOf(
			"type" to "object",
			"properties" to mapOf("items" to mapOf("type" to "array", "items" to item)),
			"required" to listOf("items"),
			"additionalProperties" to false,
		),
	)
}

/** 생성기의 응답을 시기 글로 읽는다. 필드가 빠졌거나 타입이 틀리면 null. */
fun parseTimingTexts(root: JsonNode): List<TimingText>? {
	fun JsonNode?.intOrNull(): Int? = this?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
	return root["items"]?.takeIf { it.isArray }?.values()?.map { item ->
		TimingText(
			id = item["id"].stringOrNull() ?: return null,
			fromYear = item["fromYear"].intOrNull() ?: return null,
			toYear = item["toYear"].intOrNull() ?: return null,
			title = item["title"].stringOrNull() ?: return null,
			body = item["body"].stringOrNull() ?: return null,
		)
	}
}

// 글 안의 네 자리 해(1900~2199).
private val YEAR_IN_TEXT = Regex("(?<!\\d)(?:19|20|21)\\d{2}(?!\\d)")

/**
 * 모델이 돌려준 시기 글을 써도 되는지 본다. 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 맡긴 줄과 돌려준 줄이 하나씩 맞지 않는다(빠짐·더함·겹침), 빈 글
 * - `YEAR_MISMATCH`: 옮겨 적은 해가 서버가 계산한 해와 다르다, 또는 글 안에 그 줄의 기간 밖의 해가 있다
 */
fun timingTextProblem(texts: List<TimingText>, items: List<TimingItem>): String? {
	if (texts.map { it.id }.sorted() != items.map { it.id }.sorted()) return "MALFORMED"
	if (texts.any { it.title.isBlank() || it.body.isBlank() }) return "MALFORMED"
	val byId = items.associateBy { it.id }
	for (text in texts) {
		val item = byId.getValue(text.id)
		if (text.fromYear != item.fromYear || text.toYear != item.toYear) return "YEAR_MISMATCH"
		val written = YEAR_IN_TEXT.findAll(text.title + " " + text.body).map { it.value.toInt() }
		if (written.any { it !in item.fromYear..item.toYear }) return "YEAR_MISMATCH"
	}
	return null
}

/** 모델의 글을 그 줄에만 붙인다. 날짜·영역·크기는 바뀌지 않는다. */
fun ReportTiming.withTexts(texts: List<TimingText>): ReportTiming {
	val byId = texts.associateBy { it.id }
	return copy(
		milestones = milestones.map { m -> byId[m.kind.name]?.let { m.copy(title = it.title, body = it.body) } ?: m },
		intervals = intervals.map { i -> byId[i.id]?.let { i.copy(title = it.title, body = it.body) } ?: i },
	)
}

/** 저장된 시기에서 [today] 의 "지금 나의 구간" 을 가린다. 날짜 비교뿐이고 천체 계산은 하지 않는다. */
fun timingNow(timing: ReportTiming, today: LocalDate): TimingNow = TimingNow(
	date = today,
	intervals = timing.intervals.filter { it.from <= today && today <= it.to },
	milestone = timing.milestones.firstOrNull { it.from <= today && today <= it.to },
	nextInterval = timing.intervals.firstOrNull { it.from > today },
	nextMilestone = timing.milestones.firstOrNull { it.date > today },
	nearestMilestone = timing.milestones.minBy { abs(it.date.toEpochDay() - today.toEpochDay()) }.kind,
)
