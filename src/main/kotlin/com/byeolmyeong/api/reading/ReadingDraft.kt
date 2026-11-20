package com.byeolmyeong.api.reading

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.Reason
import com.byeolmyeong.api.generation.ReasonWhen
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.basisCandidates
import com.byeolmyeong.api.generation.basisSchema
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import tools.jackson.databind.JsonNode

// 요약 해석에만 있는 것: 지시문, 돌려받을 구조, 응답을 글로 읽기, 개수 · 근거 검증, 고른 근거 id 를 구조로 바꾸기.

/** 시각을 알 때 정확히 5문단, 모르면 "첫인상과 지향"이 빠져 4문단. */
const val SECTIONS_WITH_TIME = 5
const val SECTIONS_WITHOUT_TIME = 4
const val STRENGTH_COUNT = 3

/** 보완점은 2개(확정 시안 — 결정 030 의 4). */
const val CAUTION_COUNT = 2

/** 문단마다 고르는 근거 id 의 수(결정 031 의 3). */
val BASIS_PER_SECTION = 1..2

private val SUMMARY_RULES: String by lazy { promptResource("reading-summary.md") }
private val TIME_KNOWN: String by lazy { promptResource("reading-summary-time-known.md") }
private val TIME_UNKNOWN: String by lazy { promptResource("reading-summary-time-unknown.md") }

fun readingRequest(chart: Chart): GenerationRequest {
	val candidates = basisCandidates(chart)
	return GenerationRequest(
		purpose = "reading_summary",
		instructions = WRITING_RULES + "\n\n" + SUMMARY_RULES + "\n\n" + if (chart.timeKnown) TIME_KNOWN else TIME_UNKNOWN,
		userText = chartFacts(chart, candidates),
		schema = readingSchema(candidates.map { it.id }),
	)
}

// 근거는 그 차트의 후보 id 중에서만 고르게 한다(enum). 개수 제약은 구조 지정이 받지 않아 지시문과 서버 검증이 맡는다.
private fun readingSchema(candidateIds: List<String>): Map<String, Any> {
	val string = mapOf("type" to "string")
	val strings = mapOf("type" to "array", "items" to string)
	val section = mapOf(
		"type" to "object",
		"properties" to mapOf("title" to string, "body" to string, "basis" to basisSchema(candidateIds)),
		"required" to listOf("title", "body", "basis"),
		"additionalProperties" to false,
	)
	return mapOf(
		"type" to "object",
		"properties" to mapOf(
			"headline" to string,
			"sections" to mapOf("type" to "array", "items" to section),
			"strengths" to strings,
			"cautions" to strings,
		),
		"required" to listOf("headline", "sections", "strengths", "cautions"),
		"additionalProperties" to false,
	)
}

/** 생성기의 응답을 글로 읽는다. 필드가 빠졌거나 타입이 틀리면 null. */
fun parseReadingDraft(root: JsonNode): ReadingDraft? {
	val sections = root["sections"]?.takeIf { it.isArray }?.values()?.map { section ->
		DraftSection(
			title = section["title"].stringOrNull() ?: return null,
			body = section["body"].stringOrNull() ?: return null,
			basis = section["basis"].stringsOrNull() ?: return null,
		)
	}
	return ReadingDraft(
		headline = root["headline"].stringOrNull() ?: return null,
		sections = sections ?: return null,
		strengths = root["strengths"].stringsOrNull() ?: return null,
		cautions = root["cautions"].stringsOrNull() ?: return null,
	)
}

/**
 * 생성된 글을 저장해도 되는지 본다. 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 빈 글, 문단·강점·보완점 개수가 틀림, 문단의 근거가 1~2개가 아니거나 같은 id 가 둘
 * - `BASIS_NOT_IN_CHART`: 근거에 그 차트의 후보(`generation.basisCandidates`) id 가 아닌 것이 있다(글자까지 같아야 한다)
 */
fun draftProblem(draft: ReadingDraft, chart: Chart): String? {
	val sectionCount = if (chart.timeKnown) SECTIONS_WITH_TIME else SECTIONS_WITHOUT_TIME
	val texts = listOf(draft.headline) + draft.sections.flatMap { listOf(it.title, it.body) } + draft.strengths + draft.cautions
	val wellFormed = draft.sections.size == sectionCount &&
		draft.strengths.size == STRENGTH_COUNT &&
		draft.cautions.size == CAUTION_COUNT &&
		texts.none { it.isBlank() } &&
		draft.sections.all { it.basis.size in BASIS_PER_SECTION && it.basis.distinct().size == it.basis.size }
	if (!wellFormed) return "MALFORMED"
	val allowed = basisCandidates(chart).map { it.id }.toSet()
	if (draft.sections.any { section -> section.basis.any { it !in allowed } }) return "BASIS_NOT_IN_CHART"
	return null
}

/** 검증을 통과한 글의 근거 id 를 태어날 때의 구조(`Reason`)로 바꿔 저장할 본문을 만든다. */
fun readingBody(draft: ReadingDraft, chart: Chart): ReadingBody {
	val items = basisCandidates(chart).associate { it.id to it.item }
	return ReadingBody(
		headline = draft.headline,
		sections = draft.sections.map { BodySection(it.title, it.body, Reason(ReasonWhen.BIRTH, it.basis.map(items::getValue))) },
		strengths = draft.strengths,
		cautions = draft.cautions,
	)
}
