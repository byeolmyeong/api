package com.byeolmyeong.api.direction

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.basisSchema
import com.byeolmyeong.api.generation.basisVocabulary
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import tools.jackson.databind.JsonNode

// 방향성 제시에만 있는 것: 모양, 지시문, 돌려받을 구조, 응답을 글로 읽기, 검증(개수·근거·조언형). 결정 009.

/** 주제. 지금 받는 것은 이성 하나다. 미래(`FUTURE`)·궁합(`SYNASTRY`)은 리포트 시기·궁합이 들어온 뒤에 더한다. */
const val TOPIC_LOVE = "LOVE"

const val ITEM_COUNT = 3

/** 해 볼 만한 것 하나. [title] 은 "~보기"로 끝나는 실천 한 줄, [basis] 는 바탕이 된 차트 요소의 말. */
data class DirectionItem(val title: String, val body: String, val basis: List<String>)

/** 생성기가 돌려준 글. 검증([draftProblem])을 통과해야 저장한다. 저장하는 본문도 이 모양이다. */
data class DirectionDraft(val title: String, val items: List<DirectionItem>)

/** `POST /directions` 의 응답. 형태의 원본은 docs/architecture.md "API 계약". */
data class Direction(val topic: String, val title: String, val items: List<DirectionItem>)

private val DIRECTION_RULES: String by lazy { promptResource("direction.md") }
private val LOVE_RULES: String by lazy { promptResource("direction-love.md") }
private val TIME_UNKNOWN: String by lazy { promptResource("direction-time-unknown.md") }

fun loveDirectionRequest(chart: Chart): GenerationRequest = GenerationRequest(
	purpose = "direction_love",
	instructions = listOfNotNull(WRITING_RULES, DIRECTION_RULES, LOVE_RULES, TIME_UNKNOWN.takeIf { !chart.timeKnown }).joinToString("\n\n"),
	userText = chartFacts(chart),
	schema = directionSchema(basisVocabulary(chart)),
)

// 근거는 차트에 있는 말 중에서만 고르게 한다(enum). 개수 제약은 구조 지정이 받지 않아 지시문과 서버 검증이 맡는다.
private fun directionSchema(basisVocabulary: List<String>): Map<String, Any> {
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
 * - `BASIS_NOT_IN_CHART`: 근거에 [basisVocabulary] 에 없는 말이 있다(글자까지 같아야 한다)
 * - `NOT_ADVICE_FORM`: [adviceProblem]
 */
fun draftProblem(draft: DirectionDraft, chart: Chart): String? {
	val texts = listOf(draft.title) + draft.items.flatMap { listOf(it.title, it.body) }
	val wellFormed = draft.items.size == ITEM_COUNT && texts.none { it.isBlank() } && draft.items.none { it.basis.isEmpty() }
	if (!wellFormed) return "MALFORMED"
	val allowed = basisVocabulary(chart).toSet()
	if (draft.items.any { item -> item.basis.any { it !in allowed } }) return "BASIS_NOT_IN_CHART"
	return adviceProblem(draft)
}

private const val ADVICE_TITLE_ENDING = "보기"
private val ASSERTION_WORDS = listOf("반드시", "틀림없이", "운명")

// "~게 됩니다"·"~게 돼요": 일이 그렇게 된다고 말하는 끝맺음.
private val BECOMES = Regex("게\\s*(됩니다|돼요)")

// "~ㄹ 거예요" 꼴: 이 말들 바로 앞 글자가 ㄹ 받침이면 앞일(또는 짐작)을 말하는 끝맺음이다. "그런 거예요" 는 아니다.
private val WILL_ENDING = Regex("(\\S)\\s*(거예요|거에요|겁니다|것입니다|것이에요)")
private const val HANGUL_FIRST = 0xAC00
private const val HANGUL_LAST = 0xD7A3
private const val FINAL_COUNT = 28
private const val FINAL_RIEUL = 8

private fun endsWithRieul(c: Char): Boolean = c.code in HANGUL_FIRST..HANGUL_LAST && (c.code - HANGUL_FIRST) % FINAL_COUNT == FINAL_RIEUL

/**
 * 조언형인지 글자로 본다(결정 009). 문제가 없으면 null, 있으면 `NOT_ADVICE_FORM`.
 * 항목 제목은 "보기"로 끝나야 하고, 어느 글에도 앞일을 단정하는 꼴("반드시"·"틀림없이"·"운명", "~ㄹ 거예요"·"~ㄹ 겁니다"·
 * "~ㄹ 것입니다", "~게 됩니다"·"~게 돼요")이 없어야 한다. 흔한 꼴만 막는다 — 돌려 말한 단정은 지시문이 맡는다.
 */
fun adviceProblem(draft: DirectionDraft): String? {
	val texts = listOf(draft.title) + draft.items.flatMap { listOf(it.title, it.body) }
	val asserts = texts.any { text ->
		ASSERTION_WORDS.any { it in text } ||
			BECOMES.containsMatchIn(text) ||
			WILL_ENDING.findAll(text).any { endsWithRieul(it.groupValues[1].single()) }
	}
	val adviceTitles = draft.items.all { it.title.trimEnd().endsWith(ADVICE_TITLE_ENDING) }
	return if (asserts || !adviceTitles) "NOT_ADVICE_FORM" else null
}
