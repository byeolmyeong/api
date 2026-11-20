package com.byeolmyeong.api.report

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.WorkStyle
import com.byeolmyeong.api.chart.computeWorkStyle
import com.byeolmyeong.api.generation.BasisCandidate
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.Reason
import com.byeolmyeong.api.generation.ReasonWhen
import com.byeolmyeong.api.generation.Star
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.adviceProblem
import com.byeolmyeong.api.generation.basisSchema
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.starsOf
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

// 나 탭 풀이(결정 029 · 030)의 모양과, 나 탭 풀이에만 있는 것: 영역별 지시문, 돌려받을 구조, 응답을 글로 읽기, 검증,
// 고른 근거 id 를 구조로 바꾸기(결정 031), 일 영역의 일 방식(결정 038). 응답 형태의 원본은 docs/architecture.md "API 계약".

/** 나 탭 풀이의 영역 다섯. 응답의 `areas` 는 이 순서다(결정 030 의 1). */
enum class MeReportArea { PERSONALITY, LOVE, RELATIONSHIP, WORK, EMOTION }

/** 문단 하나. [reason] 은 모델이 고른 근거 후보 id 를 서버가 태어날 때의 구조로 바꾼 것이다. */
data class MeParagraph(val title: String, val body: String, val reason: Reason)

/** 성격의 한눈에 보기: 빛나는 순간 2 · 지치는 순간 2(짧은 구절). */
data class MeGlance(val shining: List<String>, val tiring: List<String>)

/** 연애의 작은 실천 하나. [title] 은 "~기" 로 끝난다(결정 037). */
data class MePractice(val title: String, val body: String)

/** 관계의 가까워지는 모습: 처음 만날 때 · 친해지면 · 오래된 사이(짧은 한 줄). */
data class MeCloser(val first: String, val close: String, val longtime: String)

/** 감정의 흔들릴 때 · 다시 편해지는 곳. */
data class MeShaken(val `when`: String, val comfort: String)

/**
 * 한 영역. 모든 영역에 [title] · [keywords](3) · [stars](1~3) · [paragraphs](4, 연애는 빈 배열)가 있고,
 * 영역마다 칸 하나씩이 더 있다: 성격 [glance], 연애 [practices] + [reason], 관계 [closer], 일 [workStyle](서버 계산), 감정 [shaken].
 * 그 영역의 칸이 아니면 키가 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MeArea(
	val area: MeReportArea,
	val title: String,
	val keywords: List<String>,
	val stars: List<Star>,
	val paragraphs: List<MeParagraph>,
	val glance: MeGlance? = null,
	val practices: List<MePractice>? = null,
	val reason: Reason? = null,
	val closer: MeCloser? = null,
	val workStyle: WorkStyle? = null,
	val shaken: MeShaken? = null,
)

/** 나 탭 풀이(`detail_reports.body`, kind ME). */
data class MeReport(val areas: List<MeArea>)

/** `POST /me-report` · `GET /me-report` 의 응답. [report] 는 `DONE` 일 때 `GET` 에서만 보낸다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MeReportStatusResponse(val status: ReportStatus, val report: MeReport? = null)

/** 생성기가 돌려준 문단 하나. [basis] 는 프롬프트 "[근거 후보]" 의 id 1~2개. */
data class MeDraftParagraph(val title: String, val body: String, val basis: List<String>)

/** 생성기가 돌려준 한 영역의 글. 그 영역의 칸이 아니면 null 이다. 연애는 문단 대신 실천과 영역의 [basis](id 1~2개)를 받는다. */
data class MeAreaDraft(
	val area: MeReportArea,
	val title: String,
	val keywords: List<String>,
	val paragraphs: List<MeDraftParagraph>,
	val glance: MeGlance? = null,
	val practices: List<MePractice>? = null,
	val basis: List<String>? = null,
	val closer: MeCloser? = null,
	val shaken: MeShaken? = null,
)

private const val KEYWORD_COUNT = 3
private const val PARAGRAPH_COUNT = 4
private const val PRACTICE_COUNT = 3
private const val GLANCE_COUNT = 2

/** 문단(연애는 영역)마다 고르는 근거 id 의 수(결정 031 의 3). */
private val BASIS_COUNT = 1..2

private val ME_RULES: String by lazy { promptResource("me-report.md") }
private val ME_TIME_UNKNOWN: String by lazy { promptResource("report-time-unknown.md") }
private val ME_AREA_RULES: Map<MeReportArea, String> by lazy {
	MeReportArea.entries.associateWith { promptResource("me-report-${it.name.lowercase()}.md") }
}

/** 한 영역의 글을 쓰게 하는 요청. 차트와 근거 후보는 다섯 영역이 같고 지시문 · 구조만 다르다. 일 방식의 수치는 주지 않는다. */
fun meAreaRequest(area: MeReportArea, chart: Chart, candidates: List<BasisCandidate>): GenerationRequest = GenerationRequest(
	purpose = "me_report_${area.name.lowercase()}",
	instructions = listOfNotNull(WRITING_RULES, ME_RULES, ME_AREA_RULES.getValue(area), ME_TIME_UNKNOWN.takeIf { !chart.timeKnown })
		.joinToString("\n\n"),
	userText = chartFacts(chart, candidates),
	schema = meAreaSchema(area, candidates.map { it.id }),
)

// 근거는 그 차트의 후보 id 중에서만 고르게 한다(enum). 개수 제약은 구조 지정이 받지 않아 지시문과 서버 검증이 맡는다.
private fun meAreaSchema(area: MeReportArea, candidateIds: List<String>): Map<String, Any> {
	val string = mapOf("type" to "string")
	val strings = mapOf("type" to "array", "items" to string)
	fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
		"type" to "object",
		"properties" to mapOf(*properties),
		"required" to properties.map { it.first },
		"additionalProperties" to false,
	)
	fun array(items: Any) = mapOf("type" to "array", "items" to items)
	val paragraphs = "paragraphs" to array(obj("title" to string, "body" to string, "basis" to basisSchema(candidateIds)))
	val common = arrayOf<Pair<String, Any>>("title" to string, "keywords" to strings)
	return when (area) {
		MeReportArea.PERSONALITY -> obj(*common, paragraphs, "glance" to obj("shining" to strings, "tiring" to strings))
		MeReportArea.LOVE -> obj(*common, "practices" to array(obj("title" to string, "body" to string)), "basis" to basisSchema(candidateIds))
		MeReportArea.RELATIONSHIP -> obj(*common, paragraphs, "closer" to obj("first" to string, "close" to string, "longtime" to string))
		MeReportArea.WORK -> obj(*common, paragraphs)
		MeReportArea.EMOTION -> obj(*common, paragraphs, "shaken" to obj("when" to string, "comfort" to string))
	}
}

/** 생성기의 응답을 한 영역의 글로 읽는다. 그 영역에 있어야 할 필드가 빠졌거나 타입이 틀리면 null. 다른 영역의 칸은 읽지 않는다. */
fun parseMeArea(area: MeReportArea, root: JsonNode): MeAreaDraft? {
	val paragraphs = if (area == MeReportArea.LOVE) {
		emptyList()
	} else {
		root["paragraphs"]?.takeIf { it.isArray }?.values()?.map { paragraph ->
			MeDraftParagraph(
				title = paragraph["title"].stringOrNull() ?: return null,
				body = paragraph["body"].stringOrNull() ?: return null,
				basis = paragraph["basis"].stringsOrNull() ?: return null,
			)
		} ?: return null
	}
	return MeAreaDraft(
		area = area,
		title = root["title"].stringOrNull() ?: return null,
		keywords = root["keywords"].stringsOrNull() ?: return null,
		paragraphs = paragraphs,
		glance = if (area != MeReportArea.PERSONALITY) null else root["glance"]?.let { glance ->
			MeGlance(shining = glance["shining"].stringsOrNull() ?: return null, tiring = glance["tiring"].stringsOrNull() ?: return null)
		} ?: return null,
		practices = if (area != MeReportArea.LOVE) null else root["practices"]?.takeIf { it.isArray }?.values()?.map { practice ->
			MePractice(title = practice["title"].stringOrNull() ?: return null, body = practice["body"].stringOrNull() ?: return null)
		} ?: return null,
		basis = if (area != MeReportArea.LOVE) null else root["basis"].stringsOrNull() ?: return null,
		closer = if (area != MeReportArea.RELATIONSHIP) null else root["closer"]?.let { closer ->
			MeCloser(
				first = closer["first"].stringOrNull() ?: return null,
				close = closer["close"].stringOrNull() ?: return null,
				longtime = closer["longtime"].stringOrNull() ?: return null,
			)
		} ?: return null,
		shaken = if (area != MeReportArea.EMOTION) null else root["shaken"]?.let { shaken ->
			MeShaken(`when` = shaken["when"].stringOrNull() ?: return null, comfort = shaken["comfort"].stringOrNull() ?: return null)
		} ?: return null,
	)
}

// 글에 숫자 · 퍼센트가 있는가. 일 방식의 수치는 서버만 말한다(결정 030 의 6 · 038 의 5).
private fun hasNumber(text: String): Boolean = text.any { it.isDigit() || it == '%' } || "퍼센트" in text

// 한 영역의 글자 칸 전부(근거 id 는 빼고).
private fun textsOf(draft: MeAreaDraft): List<String> =
	listOf(draft.title) + draft.keywords +
		draft.paragraphs.flatMap { listOf(it.title, it.body) } +
		draft.glance?.let { it.shining + it.tiring }.orEmpty() +
		draft.practices?.flatMap { listOf(it.title, it.body) }.orEmpty() +
		draft.closer?.let { listOf(it.first, it.close, it.longtime) }.orEmpty() +
		draft.shaken?.let { listOf(it.`when`, it.comfort) }.orEmpty()

/**
 * 생성된 한 영역의 글을 저장해도 되는지 본다(결정 030 의 6). 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 빈 글, 키워드가 3개가 아니거나 겹침, 문단이 4개가 아님(연애는 0), 문단 · 영역의 근거가 1~2개가 아니거나 같은 id 가 둘,
 *   한눈에 보기가 2 · 2 가 아님, 실천이 3개가 아님
 * - `BASIS_NOT_IN_CHART`: 근거에 그 차트의 근거 후보 id 가 아닌 것이 있다(글자까지 같아야 한다)
 * - `HAS_NUMBER`: 어느 칸에든 숫자 · 퍼센트가 있다
 * - `NOT_ADVICE_FORM`: 연애 실천의 제목이 "기" 로 끝나지 않거나, 어느 칸에든 단정 · 운명 · 앞일의 끝맺음이 있다(`generation.adviceProblem`)
 */
fun meAreaProblem(draft: MeAreaDraft, candidateIds: Set<String>): String? {
	val texts = textsOf(draft)
	val bases = draft.paragraphs.map { it.basis } + listOfNotNull(draft.basis)
	val wellFormed = draft.keywords.size == KEYWORD_COUNT &&
		draft.keywords.distinct().size == KEYWORD_COUNT &&
		draft.paragraphs.size == (if (draft.area == MeReportArea.LOVE) 0 else PARAGRAPH_COUNT) &&
		texts.none { it.isBlank() } &&
		bases.all { it.size in BASIS_COUNT && it.distinct().size == it.size } &&
		(draft.glance?.let { it.shining.size == GLANCE_COUNT && it.tiring.size == GLANCE_COUNT } ?: true) &&
		(draft.practices?.let { it.size == PRACTICE_COUNT } ?: true)
	if (!wellFormed) return "MALFORMED"
	if (bases.any { basis -> basis.any { it !in candidateIds } }) return "BASIS_NOT_IN_CHART"
	if (texts.any(::hasNumber)) return "HAS_NUMBER"
	return adviceProblem(draft.practices.orEmpty().map { it.title }, texts)
}

/**
 * 검증을 통과한 글을 저장할 영역으로 만든다: 고른 id 를 태어날 때의 근거(`Reason`)로 바꾸고, 그 근거에서 "이 풀이에 쓰인 별"(1~3)을 만들고,
 * 일 영역에는 서버가 차트로 계산한 일 방식을 넣는다(결정 038 — 모델은 쓰지 않는다, 읽을 때 다시 계산하지 않는다).
 */
fun meArea(draft: MeAreaDraft, chart: Chart, candidates: List<BasisCandidate>): MeArea {
	val items = candidates.associate { it.id to it.item }
	fun reasonOf(ids: List<String>) = Reason(ReasonWhen.BIRTH, ids.map(items::getValue))
	val paragraphs = draft.paragraphs.map { MeParagraph(it.title, it.body, reasonOf(it.basis)) }
	val reason = draft.basis?.let(::reasonOf)
	return MeArea(
		area = draft.area,
		title = draft.title,
		keywords = draft.keywords,
		stars = starsOf(paragraphs.map { it.reason } + listOfNotNull(reason)),
		paragraphs = paragraphs,
		glance = draft.glance,
		practices = draft.practices,
		reason = reason,
		closer = draft.closer,
		workStyle = if (draft.area == MeReportArea.WORK) computeWorkStyle(chart) else null,
		shaken = draft.shaken,
	)
}

/** 저장해도 되는 풀이인지: 다섯 영역이 빠짐없이 정해진 순서로 있어야 한다. */
fun isComplete(report: MeReport): Boolean = report.areas.map { it.area } == MeReportArea.entries
