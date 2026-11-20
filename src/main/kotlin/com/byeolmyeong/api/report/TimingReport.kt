package com.byeolmyeong.api.report

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.ChartAngle
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.PointKind
import com.byeolmyeong.api.chart.TransitTarget
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.Reason
import com.byeolmyeong.api.generation.ReasonWhen
import com.byeolmyeong.api.generation.TransitBasis
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.adviceProblem
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.intervalPriority
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import java.time.LocalDate

// 시기 풀이(결정 029 · 033): 모양은 옛 리포트의 시기(ReportTiming)에 칸을 더한 것이고, 여기에는 시기 풀이에만 있는 것을 둔다 —
// 9줄 묶음, 묶음마다 돌려받을 구조, 검증, 글 붙이기(구간 이름은 표의 제목 그대로), 근거, 대표 구간. 형태의 원본은 docs/architecture.md "시기 풀이".

/** 시기 자세히(14-3) 01 무엇이 달라지나요 · 02 이렇게 지내 보세요 · 03 이 시기가 지나면. */
data class TimingDetail(val changes: String, val howTo: String, val after: String)

/** 이 시기의 작은 실천 하나. [title] 은 "~기" 로 끝난다(결정 037). */
data class TimingPractice(val title: String)

/** 모델이 돌려준 마디 한 줄. 해는 받은 것을 옮겨 적은 값이다. */
data class TimingMilestoneText(val id: String, val fromYear: Int, val toYear: Int, val title: String, val body: String)

/** 모델이 돌려준 구간 한 줄. 제목은 없다(표의 이름으로 고정 — 결정 033 의 3). */
data class TimingIntervalText(
	val id: String,
	val fromYear: Int,
	val toYear: Int,
	val summary: String,
	val body: String,
	val detail: TimingDetail,
	val practices: List<String>,
)

/** 한 묶음의 답. */
data class TimingBatchTexts(val milestones: List<TimingMilestoneText>, val intervals: List<TimingIntervalText>)

/** `POST /timing-report` · `GET /timing-report` 의 응답. [report] 는 `DONE` 일 때 `GET` 에서만 보낸다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TimingReportStatusResponse(val status: ReportStatus, val report: ReportTiming? = null)

/** 한 번에 맡기는 줄 수. 마디 4 + 구간 30까지(약 34줄)가 약 4번에 나뉜다(결정 033 의 7). */
private const val BATCH_SIZE = 9

/** 실천의 수. */
private const val PRACTICE_COUNT = 3

/** 모델에게 맡길 줄(마디 넷이 먼저, 그다음 구간)을 순서 그대로 9줄씩 묶는다. */
fun timingBatches(items: List<TimingItem>): List<List<TimingItem>> = items.chunked(BATCH_SIZE)

private val TIMING_REPORT_RULES: String by lazy { promptResource("timing-report.md") }
private val TIMING_REPORT_TIME_UNKNOWN: String by lazy { promptResource("report-time-unknown.md") }

/**
 * 한 묶음의 글을 쓰게 하는 요청. 차트 글 아래 "[시기 목록]" 에 그 묶음의 줄을 붙이고, 구간 줄 끝에는 고정된 이름(표의 제목)을 붙인다 —
 * 모델은 이름을 쓰지 않고 그 이름에 맞춰 설명 · 자세히 · 실천을 쓴다. 해는 서버가 계산한 값이고 옮겨 적기만 한다.
 */
fun timingReportRequest(chart: Chart, batch: List<TimingItem>, timing: ReportTiming): GenerationRequest {
	val titles = timing.intervals.associate { it.id to it.title }
	val lines = batch.map { item -> if (item.milestone) item.line else "${item.line} | 이름: ${titles.getValue(item.id)}" }
	val string = mapOf("type" to "string")
	val integer = mapOf("type" to "integer")
	fun id(ids: List<String>): Map<String, Any> = if (ids.isEmpty()) string else mapOf("type" to "string", "enum" to ids)
	fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
		"type" to "object",
		"properties" to mapOf(*properties),
		"required" to properties.map { it.first },
		"additionalProperties" to false,
	)
	val milestone = obj(
		"id" to id(batch.filter { it.milestone }.map { it.id }), "fromYear" to integer, "toYear" to integer, "title" to string, "body" to string,
	)
	val interval = obj(
		"id" to id(batch.filter { !it.milestone }.map { it.id }), "fromYear" to integer, "toYear" to integer,
		"summary" to string, "body" to string,
		"detail" to obj("changes" to string, "howTo" to string, "after" to string),
		"practices" to mapOf("type" to "array", "items" to string),
	)
	return GenerationRequest(
		purpose = "timing_report",
		instructions = listOfNotNull(WRITING_RULES, TIMING_REPORT_RULES, TIMING_REPORT_TIME_UNKNOWN.takeIf { !chart.timeKnown }).joinToString("\n\n"),
		userText = chartFacts(chart) + "\n\n[시기 목록]\n" + lines.joinToString("\n"),
		schema = obj("milestones" to mapOf("type" to "array", "items" to milestone), "intervals" to mapOf("type" to "array", "items" to interval)),
	)
}

/** 생성기의 응답을 한 묶음의 글로 읽는다. 필드가 빠졌거나 타입이 틀리면 null. */
fun parseTimingBatch(root: JsonNode): TimingBatchTexts? {
	fun JsonNode?.intOrNull(): Int? = this?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
	val milestones = root["milestones"]?.takeIf { it.isArray }?.values()?.map { item ->
		TimingMilestoneText(
			id = item["id"].stringOrNull() ?: return null,
			fromYear = item["fromYear"].intOrNull() ?: return null,
			toYear = item["toYear"].intOrNull() ?: return null,
			title = item["title"].stringOrNull() ?: return null,
			body = item["body"].stringOrNull() ?: return null,
		)
	} ?: return null
	val intervals = root["intervals"]?.takeIf { it.isArray }?.values()?.map { item ->
		val detail = item["detail"] ?: return null
		TimingIntervalText(
			id = item["id"].stringOrNull() ?: return null,
			fromYear = item["fromYear"].intOrNull() ?: return null,
			toYear = item["toYear"].intOrNull() ?: return null,
			summary = item["summary"].stringOrNull() ?: return null,
			body = item["body"].stringOrNull() ?: return null,
			detail = TimingDetail(
				changes = detail["changes"].stringOrNull() ?: return null,
				howTo = detail["howTo"].stringOrNull() ?: return null,
				after = detail["after"].stringOrNull() ?: return null,
			),
			practices = item["practices"].stringsOrNull() ?: return null,
		)
	} ?: return null
	return TimingBatchTexts(milestones, intervals)
}

// 글 안의 네 자리 해(1900~2199). 옛 리포트의 시기 글과 같은 규칙.
private val YEAR_IN_TEXT = Regex("(?<!\\d)(?:19|20|21)\\d{2}(?!\\d)")

private fun TimingIntervalText.texts(): List<String> = listOf(summary, body, detail.changes, detail.howTo, detail.after) + practices

/**
 * 한 묶음의 답을 써도 되는지 본다(결정 033 의 7). 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 맡긴 마디 · 구간 줄과 돌려준 줄이 하나씩 맞지 않는다(빠짐 · 더함 · 겹침), 빈 글, 실천이 3개가 아님
 * - `YEAR_MISMATCH`: 옮겨 적은 해가 계산한 해와 다르다, 또는 글 안에 그 줄의 기간 밖의 해가 있다
 * - `NOT_ADVICE_FORM`: 실천 제목이 "기" 로 끝나지 않거나 어느 글에든 단정 · 운명 · 앞일의 끝맺음이 있다(`generation.adviceProblem`)
 */
fun timingBatchProblem(texts: TimingBatchTexts, batch: List<TimingItem>): String? {
	val milestoneIds = batch.filter { it.milestone }.map { it.id }.sorted()
	val intervalIds = batch.filter { !it.milestone }.map { it.id }.sorted()
	if (texts.milestones.map { it.id }.sorted() != milestoneIds || texts.intervals.map { it.id }.sorted() != intervalIds) return "MALFORMED"
	val all = texts.milestones.flatMap { listOf(it.title, it.body) } + texts.intervals.flatMap { it.texts() }
	if (all.any { it.isBlank() } || texts.intervals.any { it.practices.size != PRACTICE_COUNT }) return "MALFORMED"
	val byId = batch.associateBy { it.id }
	val lines = texts.milestones.map { Triple(it.id, it.fromYear to it.toYear, listOf(it.title, it.body)) } +
		texts.intervals.map { Triple(it.id, it.fromYear to it.toYear, it.texts()) }
	for ((id, years, written) in lines) {
		val item = byId.getValue(id)
		if (years != item.fromYear to item.toYear) return "YEAR_MISMATCH"
		if (written.flatMap { YEAR_IN_TEXT.findAll(it).map { year -> year.value.toInt() } }.any { it !in item.fromYear..item.toYear }) return "YEAR_MISMATCH"
	}
	return adviceProblem(texts.intervals.flatMap { it.practices }, all)
}

/** 구간의 근거(결정 031 · 033 의 4): 그 구간을 만든 지나가는 별 · 대상 · 각도, 하우스 통과면 하우스. 계산값에서 바로 만든다. */
fun transitReason(interval: Interval): Reason {
	val item = when (interval.target) {
		TransitTarget.SUN, TransitTarget.MOON, TransitTarget.VENUS -> TransitBasis(interval.mover, interval.target.name, interval.aspect)
		TransitTarget.ASCENDANT -> TransitBasis(interval.mover, ChartAngle.ASC.name, interval.aspect)
		TransitTarget.MIDHEAVEN -> TransitBasis(interval.mover, ChartAngle.MC.name, interval.aspect)
		TransitTarget.VERTEX -> TransitBasis(interval.mover, PointKind.VERTEX.name, interval.aspect)
		TransitTarget.HOUSE_5 -> TransitBasis(interval.mover, house = 5)
		TransitTarget.HOUSE_7 -> TransitBasis(interval.mover, house = 7)
		TransitTarget.HOUSE_11 -> TransitBasis(interval.mover, house = 11)
	}
	return Reason(ReasonWhen.NOW, listOf(item))
}

/**
 * 묶음들의 글을 붙인다: 마디는 모델의 제목 · 본문, 맡긴 구간은 표의 이름 그대로 두고 본문 · 한 줄 · 자세히 · 실천 · 근거를 더한다.
 * 맡기지 않은 구간은 표의 글뿐이다. 날짜 · 영역 · 크기는 바뀌지 않는다. [sources] 는 구간 id 마다 계산한 구간이다.
 */
fun ReportTiming.withReportTexts(texts: List<TimingBatchTexts>, sources: Map<String, Interval>): ReportTiming {
	val milestoneTexts = texts.flatMap { it.milestones }.associateBy { it.id }
	val intervalTexts = texts.flatMap { it.intervals }.associateBy { it.id }
	return copy(
		milestones = milestones.map { m -> milestoneTexts[m.kind.name]?.let { m.copy(title = it.title, body = it.body) } ?: m },
		intervals = intervals.map { i ->
			intervalTexts[i.id]?.let {
				i.copy(
					body = it.body, summary = it.summary, detail = it.detail, practices = it.practices.map(::TimingPractice),
					reason = transitReason(sources.getValue(i.id)),
				)
			} ?: i
		},
	)
}

/** 대표 구간의 id(결정 033 의 2): [intervals](오늘 걸친 구간) 가운데 변화 큰 > 작은 > 운명적 만남 > 기회, 같으면 늦게 시작한 것. 없으면 null. */
fun mainIntervalId(intervals: List<TimingInterval>): String? =
	intervals.minWithOrNull(
		compareBy<TimingInterval> { intervalPriority(it.kind, it.size) }.thenByDescending { it.from }.thenByDescending { it.id.removePrefix("i").toIntOrNull() ?: 0 },
	)?.id

/** 저장된 시기 풀이에 [today](한국 날짜)의 "지금" 과 대표 구간을 붙인다. 날짜 비교뿐이고 저장하지 않는다. */
fun timingReportNow(timing: ReportTiming, today: LocalDate): ReportTiming {
	val now = timingNow(timing, today)
	return timing.copy(current = now.copy(mainId = mainIntervalId(now.intervals)))
}
