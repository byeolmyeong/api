package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.SynastryFacts
import com.byeolmyeong.api.generation.basisSchema
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import com.byeolmyeong.api.generation.synastryBasisVocabulary
import com.byeolmyeong.api.generation.synastryFactsText
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

// 궁합 리포트의 모양과, 리포트에만 있는 것: 영역별 지시문, 돌려받을 구조, 응답을 글로 읽기, 검증.
// 심층 리포트(report 패키지)와 같은 방식이지만 기능 패키지는 서로 의존하지 않으므로 따로 둔다.

/** 글로 쓰는 네 영역(끌림·대화·갈등·오래가기). 응답의 `sections` 는 이 순서다 — 끌리는 점이 늘 먼저다. */
enum class SynastryReportArea { ATTRACTION, TALK, CONFLICT, LASTING }

/** 문단 하나. [basis] 는 내 쪽 요소의 말뿐이다([synastryBasisVocabulary]). 상대 쪽 요소는 저장되는 글에 넣지 않는다(결정 010). */
data class SynastryReportParagraph(val title: String, val body: String, val basis: List<String>)

data class SynastryReportSection(val area: SynastryReportArea, val title: String, val paragraphs: List<SynastryReportParagraph>)

data class SynastryReport(val sections: List<SynastryReportSection>)

/** `POST`·`GET /synastry/{id}/report` 의 응답. [report] 는 `DONE` 일 때 `GET` 에서만 보낸다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SynastryReportResponse(val status: SynastryReportStatus, val report: SynastryReport? = null)

/** 영역마다 문단은 3~4개다. */
val SYNASTRY_PARAGRAPHS_PER_AREA = 3..4

private val REPORT_RULES: String by lazy { promptResource("synastry-report.md") }
private val AREA_RULES: Map<SynastryReportArea, String> by lazy {
	SynastryReportArea.entries.associateWith { promptResource("synastry-report-${it.name.lowercase()}.md") }
}

/** 한 영역의 글을 쓰게 하는 요청. 재료는 네 영역이 같고(요약과도 같다) 지시문만 다르다. */
fun synastryAreaRequest(area: SynastryReportArea, facts: SynastryFacts): GenerationRequest {
	val vocabulary = synastryBasisVocabulary(facts.mine)
	val string = mapOf("type" to "string")
	val paragraph = mapOf(
		"type" to "object",
		"properties" to mapOf("title" to string, "body" to string, "basis" to basisSchema(vocabulary)),
		"required" to listOf("title", "body", "basis"),
		"additionalProperties" to false,
	)
	return GenerationRequest(
		purpose = "synastry_report_${area.name.lowercase()}",
		instructions = listOf(SYNASTRY_INSTRUCTIONS, REPORT_RULES, AREA_RULES.getValue(area)).joinToString("\n\n"),
		userText = synastryFactsText(facts) + "\n\n[근거로 쓸 수 있는 말]\n" + vocabulary.joinToString(", "),
		schema = mapOf(
			"type" to "object",
			"properties" to mapOf("title" to string, "paragraphs" to mapOf("type" to "array", "items" to paragraph)),
			"required" to listOf("title", "paragraphs"),
			"additionalProperties" to false,
		),
	)
}

/** 생성기의 응답을 한 영역의 글로 읽는다. 필드가 빠졌거나 타입이 틀리면 null. */
fun parseSynastrySection(area: SynastryReportArea, root: JsonNode): SynastryReportSection? {
	val paragraphs = root["paragraphs"]?.takeIf { it.isArray }?.values()?.map { paragraph ->
		SynastryReportParagraph(
			title = paragraph["title"].stringOrNull() ?: return null,
			body = paragraph["body"].stringOrNull() ?: return null,
			basis = paragraph["basis"].stringsOrNull() ?: return null,
		)
	}
	return SynastryReportSection(area, title = root["title"].stringOrNull() ?: return null, paragraphs = paragraphs ?: return null)
}

/**
 * 생성된 한 영역의 글을 저장해도 되는지 본다. 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 빈 글, 문단이 3~4개가 아님, 근거가 없는 문단
 * - `BASIS_NOT_ALLOWED`: 근거에 내 쪽 요소의 말이 아닌 것이 있다
 * - `HAS_SCORE`: 글에 점수나 퍼센트가 적혀 있다(점수는 서버만 말한다). 긴 글이라 "2~3일" 같은 다른 숫자까지 막지는 않는다.
 */
fun synastrySectionProblem(section: SynastryReportSection, mine: Chart): String? {
	val texts = listOf(section.title) + section.paragraphs.flatMap { listOf(it.title, it.body) }
	val wellFormed = section.paragraphs.size in SYNASTRY_PARAGRAPHS_PER_AREA &&
		texts.none { it.isBlank() } &&
		section.paragraphs.none { it.basis.isEmpty() }
	if (!wellFormed) return "MALFORMED"
	val allowed = synastryBasisVocabulary(mine).toSet()
	if (section.paragraphs.any { paragraph -> paragraph.basis.any { it !in allowed } }) return "BASIS_NOT_ALLOWED"
	if (texts.any(SCORE_LIKE::containsMatchIn)) return "HAS_SCORE"
	return null
}

private val SCORE_LIKE = Regex("""\d\s*(점|%|퍼센트)""")

/** 저장해도 되는 리포트인지: 네 영역이 빠짐없이 정해진 순서로 있어야 한다. */
fun isComplete(report: SynastryReport): Boolean = report.sections.map { it.area } == SynastryReportArea.entries
