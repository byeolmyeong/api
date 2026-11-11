package com.byeolmyeong.api.reading

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.basisSchema
import com.byeolmyeong.api.generation.basisVocabulary
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import tools.jackson.databind.JsonNode

// 요약 해석에만 있는 것: 지시문, 돌려받을 구조, 응답을 글로 읽기, 개수 검증.

/** 시각을 알 때 정확히 5문단, 모르면 "첫인상과 지향"이 빠져 4문단. */
const val SECTIONS_WITH_TIME = 5
const val SECTIONS_WITHOUT_TIME = 4
const val STRENGTH_COUNT = 3
const val CAUTION_COUNT = 3

private val COMMON: String by lazy { promptResource("reading-summary.md") }
private val TIME_KNOWN: String by lazy { promptResource("reading-summary-time-known.md") }
private val TIME_UNKNOWN: String by lazy { promptResource("reading-summary-time-unknown.md") }

fun readingRequest(chart: Chart): GenerationRequest = GenerationRequest(
	purpose = "reading_summary",
	instructions = COMMON + "\n\n" + if (chart.timeKnown) TIME_KNOWN else TIME_UNKNOWN,
	userText = chartFacts(chart),
	schema = readingSchema(basisVocabulary(chart)),
)

// 근거는 차트에 있는 말 중에서만 고르게 한다(enum). 개수 제약은 구조 지정이 받지 않아 지시문과 서버 검증이 맡는다.
private fun readingSchema(basisVocabulary: List<String>): Map<String, Any> {
	val string = mapOf("type" to "string")
	val strings = mapOf("type" to "array", "items" to string)
	val section = mapOf(
		"type" to "object",
		"properties" to mapOf("title" to string, "body" to string, "basis" to basisSchema(basisVocabulary)),
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
		ReadingSection(
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
 * - `MALFORMED`: 빈 글, 문단·강점·보완점 개수가 틀림, 근거가 없는 문단
 * - `BASIS_NOT_IN_CHART`: 근거에 [basisVocabulary] 에 없는 말이 있다(글자까지 같아야 한다)
 */
fun draftProblem(draft: ReadingDraft, chart: Chart): String? {
	val sectionCount = if (chart.timeKnown) SECTIONS_WITH_TIME else SECTIONS_WITHOUT_TIME
	val texts = listOf(draft.headline) + draft.sections.flatMap { listOf(it.title, it.body) } + draft.strengths + draft.cautions
	val wellFormed = draft.sections.size == sectionCount &&
		draft.strengths.size == STRENGTH_COUNT &&
		draft.cautions.size == CAUTION_COUNT &&
		texts.none { it.isBlank() } &&
		draft.sections.none { it.basis.isEmpty() }
	if (!wellFormed) return "MALFORMED"
	val allowed = basisVocabulary(chart).toSet()
	if (draft.sections.any { section -> section.basis.any { it !in allowed } }) return "BASIS_NOT_IN_CHART"
	return null
}
