package com.byeolmyeong.api.report

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.basisSchema
import com.byeolmyeong.api.generation.basisVocabulary
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

// 심층 리포트의 모양과, 리포트에만 있는 것: 영역별 지시문, 돌려받을 구조, 응답을 글로 읽기, 검증.
// 응답 형태의 원본은 docs/architecture.md "API 계약". 시기 영역(timing)은 태스크 8b 에서 더한다.

/** 글로 쓰는 네 영역. 응답의 `sections` 는 이 순서다. */
enum class ReportArea { PERSONALITY, RELATIONSHIP, WORK, EMOTION }

/** 문단 하나. [basis] 는 이 문단의 바탕이 된 차트 요소의 말(`generation.basisVocabulary` 에 있는 것만). */
data class ReportParagraph(val title: String, val body: String, val basis: List<String>)

data class ReportSection(val area: ReportArea, val title: String, val paragraphs: List<ReportParagraph>)

data class Report(val sections: List<ReportSection>)

/** `NONE` 은 행이 없다는 뜻이라 저장하지 않는다. `FAILED` 는 만들지 못했거나 쓰다가 멈췄다는 뜻이고 다시 요청하면 새로 시작한다. */
enum class ReportStatus { NONE, WRITING, FAILED, DONE }

/** `POST /reports` · `GET /reports/me` 의 응답. [report] 는 `DONE` 일 때 `GET` 에서만 보낸다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReportStatusResponse(val status: ReportStatus, val report: Report? = null)

/** 영역마다 문단은 4~6개다(앱 저장소 release-1.0.0 F4). */
val PARAGRAPHS_PER_AREA = 4..6

private val REPORT_RULES: String by lazy { promptResource("report.md") }
private val TIME_UNKNOWN: String by lazy { promptResource("report-time-unknown.md") }
private val AREA_RULES: Map<ReportArea, String> by lazy {
	ReportArea.entries.associateWith { promptResource("report-${it.name.lowercase()}.md") }
}

/** 한 영역의 글을 쓰게 하는 요청. 차트는 네 영역이 같고 지시문만 다르다. */
fun reportAreaRequest(area: ReportArea, chart: Chart): GenerationRequest = GenerationRequest(
	purpose = "report_${area.name.lowercase()}",
	instructions = listOfNotNull(WRITING_RULES, REPORT_RULES, AREA_RULES.getValue(area), TIME_UNKNOWN.takeIf { !chart.timeKnown })
		.joinToString("\n\n"),
	userText = chartFacts(chart),
	schema = areaSchema(basisVocabulary(chart)),
)

// 근거는 차트에 있는 말 중에서만 고르게 한다(enum). 개수 제약은 구조 지정이 받지 않아 지시문과 서버 검증이 맡는다.
private fun areaSchema(basisVocabulary: List<String>): Map<String, Any> {
	val string = mapOf("type" to "string")
	val paragraph = mapOf(
		"type" to "object",
		"properties" to mapOf("title" to string, "body" to string, "basis" to basisSchema(basisVocabulary)),
		"required" to listOf("title", "body", "basis"),
		"additionalProperties" to false,
	)
	return mapOf(
		"type" to "object",
		"properties" to mapOf("title" to string, "paragraphs" to mapOf("type" to "array", "items" to paragraph)),
		"required" to listOf("title", "paragraphs"),
		"additionalProperties" to false,
	)
}

/** 생성기의 응답을 한 영역의 글로 읽는다. 필드가 빠졌거나 타입이 틀리면 null. */
fun parseReportSection(area: ReportArea, root: JsonNode): ReportSection? {
	val paragraphs = root["paragraphs"]?.takeIf { it.isArray }?.values()?.map { paragraph ->
		ReportParagraph(
			title = paragraph["title"].stringOrNull() ?: return null,
			body = paragraph["body"].stringOrNull() ?: return null,
			basis = paragraph["basis"].stringsOrNull() ?: return null,
		)
	}
	return ReportSection(area, title = root["title"].stringOrNull() ?: return null, paragraphs = paragraphs ?: return null)
}

/**
 * 생성된 한 영역의 글을 저장해도 되는지 본다. 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 빈 글, 문단이 4~6개가 아님, 근거가 없는 문단
 * - `BASIS_NOT_IN_CHART`: 근거에 그 차트의 근거 말에 없는 말이 있다(글자까지 같아야 한다)
 */
fun sectionProblem(section: ReportSection, chart: Chart): String? {
	val texts = listOf(section.title) + section.paragraphs.flatMap { listOf(it.title, it.body) }
	val wellFormed = section.paragraphs.size in PARAGRAPHS_PER_AREA &&
		texts.none { it.isBlank() } &&
		section.paragraphs.none { it.basis.isEmpty() }
	if (!wellFormed) return "MALFORMED"
	val allowed = basisVocabulary(chart).toSet()
	if (section.paragraphs.any { paragraph -> paragraph.basis.any { it !in allowed } }) return "BASIS_NOT_IN_CHART"
	return null
}

/** 저장해도 되는 리포트인지: 네 영역이 빠짐없이 정해진 순서로 있어야 한다. */
fun isComplete(report: Report): Boolean = report.sections.map { it.area } == ReportArea.entries
