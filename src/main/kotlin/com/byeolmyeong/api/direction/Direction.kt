package com.byeolmyeong.api.direction

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.adviceProblem
import com.byeolmyeong.api.generation.basisSchema
import com.byeolmyeong.api.generation.basisVocabulary
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import tools.jackson.databind.JsonNode

// 방향성 제시에만 있는 것: 모양, 지시문, 돌려받을 구조, 응답을 글로 읽기, 검증(개수·근거·조언형). 결정 009.

/** 주제. 이성은 내 차트, 미래는 내 차트의 지금의 시기, 궁합은 궁합 한 건에 대한 글이다(결정 009·011). */
const val TOPIC_LOVE = "LOVE"
const val TOPIC_FUTURE = "FUTURE"
const val TOPIC_SYNASTRY = "SYNASTRY"

const val ITEM_COUNT = 3

/** 해 볼 만한 것 하나. [title] 은 "~기"로 끝나는 실천 한 줄(지시문은 "보기" 로 쓰게 한다, 결정 037), [basis] 는 바탕이 된 차트 요소의 말. */
data class DirectionItem(val title: String, val body: String, val basis: List<String>)

/** 생성기가 돌려준 글. 검증([draftProblem])을 통과해야 저장한다. 저장하는 본문도 이 모양이다. */
data class DirectionDraft(val title: String, val items: List<DirectionItem>)

/** `POST /directions` 의 응답. 형태의 원본은 docs/architecture.md "API 계약". */
data class Direction(val topic: String, val title: String, val items: List<DirectionItem>)

private val DIRECTION_RULES: String by lazy { promptResource("direction.md") }
private val LOVE_RULES: String by lazy { promptResource("direction-love.md") }
private val TIME_UNKNOWN: String by lazy { promptResource("direction-time-unknown.md") }

/** 지시문: 공통 글쓰기 규칙 + 방향성 제시의 규칙 + 주제의 규칙(+ 시각을 모르면 그 안내). */
internal fun directionInstructions(topicRules: String, timeKnown: Boolean): String =
	listOfNotNull(WRITING_RULES, DIRECTION_RULES, topicRules, TIME_UNKNOWN.takeIf { !timeKnown }).joinToString("\n\n")

fun loveDirectionRequest(chart: Chart): GenerationRequest = GenerationRequest(
	purpose = "direction_love",
	instructions = directionInstructions(LOVE_RULES, timeKnown = chart.timeKnown),
	userText = chartFacts(chart),
	schema = directionSchema(basisVocabulary(chart)),
)

// 근거는 차트에 있는 말 중에서만 고르게 한다(enum). 개수 제약은 구조 지정이 받지 않아 지시문과 서버 검증이 맡는다.
internal fun directionSchema(basisVocabulary: List<String>): Map<String, Any> {
	val string = mapOf("type" to "string")
	val item = mapOf(
		"type" to "object",
		"properties" to mapOf("title" to string, "body" to string, "basis" to basisSchema(basisVocabulary)),
		"required" to listOf("title", "body", "basis"),
		"additionalProperties" to false,
	)
	return mapOf(
		"type" to "object",
		"properties" to mapOf("title" to string, "items" to mapOf("type" to "array", "items" to item)),
		"required" to listOf("title", "items"),
		"additionalProperties" to false,
	)
}

/** 생성기의 응답을 글로 읽는다. 필드가 빠졌거나 타입이 틀리면 null. */
fun parseDirectionDraft(root: JsonNode): DirectionDraft? {
	val items = root["items"]?.takeIf { it.isArray }?.values()?.map { item ->
		DirectionItem(
			title = item["title"].stringOrNull() ?: return null,
			body = item["body"].stringOrNull() ?: return null,
			basis = item["basis"].stringsOrNull() ?: return null,
		)
	}
	return DirectionDraft(title = root["title"].stringOrNull() ?: return null, items = items ?: return null)
}

/**
 * 생성된 글을 저장해도 되는지 본다. 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 빈 글, 항목이 3개가 아님, 근거가 없는 항목
 * - `BASIS_NOT_IN_CHART`: 근거에 [vocabulary](그 글에서 근거로 쓸 수 있는 말. 궁합은 내 쪽 요소뿐)에 없는 말이 있다(글자까지 같아야 한다)
 * - `HAS_NUMBER`: [numbers] 가 false 인데 글에 숫자가 있다. 미래는 받지 않은 해·나이를 지어내지 못하게, 궁합은 점수를 적지 못하게(결정 011)
 * - `NOT_ADVICE_FORM`: [adviceProblem]
 */
fun draftProblem(draft: DirectionDraft, vocabulary: List<String>, numbers: Boolean): String? {
	val texts = listOf(draft.title) + draft.items.flatMap { listOf(it.title, it.body) }
	val wellFormed = draft.items.size == ITEM_COUNT && texts.none { it.isBlank() } && draft.items.none { it.basis.isEmpty() }
	if (!wellFormed) return "MALFORMED"
	val allowed = vocabulary.toSet()
	if (draft.items.any { item -> item.basis.any { it !in allowed } }) return "BASIS_NOT_IN_CHART"
	if (!numbers && texts.any { text -> text.any(Char::isDigit) }) return "HAS_NUMBER"
	return adviceProblem(draft)
}

/**
 * 조언형인지 글자로 본다(결정 009 · 037). 검사의 단일 출처는 `generation.adviceProblem` 이고, 여기서는 방향성 제시의 어느 글을 보는지만 정한다:
 * 항목 제목이 실천 한 줄이고, 맨 위 제목 · 항목 제목 · 항목 본문 모두에 단정 표현 검사를 건다.
 */
fun adviceProblem(draft: DirectionDraft): String? =
	adviceProblem(draft.items.map { it.title }, listOf(draft.title) + draft.items.flatMap { listOf(it.title, it.body) })
