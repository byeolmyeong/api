package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.SynastryAspect
import com.byeolmyeong.api.chart.SynastryTarget
import com.byeolmyeong.api.chart.synastryWeight
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.SynastryFacts
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import com.byeolmyeong.api.generation.synastryFactsText
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.math.abs

// 궁합의 모양과, 궁합 글에만 있는 것: 지시문, 돌려받을 구조, 검증. 두 차트 사이의 연결을 글로 옮기는 것은 generation/SynastryFacts.kt.
// 응답 형태의 원본은 docs/architecture.md "API 계약". 무엇을 저장하는지는 docs/decisions/053.

const val KEYWORD_COUNT = 3
const val MAX_NAME_LENGTH = 20

/** 새 궁합에 넣을 수 있는 사이(결정 053 의 1). `Relation` 의 나머지(일 · 가족)는 옛 기록을 읽으려고 남긴다. */
val INPUT_RELATIONS = setOf(Relation.LOVE, Relation.FRIEND)
private const val MAX_KEYWORD_LENGTH = 20
private const val MAX_HEADLINE_LENGTH = 100

/** 궁합의 상태(결정 053 의 3). `NEW_STAR` 는 링크로 온 사람이 사이를 고르기 전(계획 S③ — 아직 만들지 않는다). */
enum class SynastryStatus { WRITING, DONE, FAILED, NEW_STAR }

/** 직접 입력 · 내 궁합 링크(계획 S③). */
enum class SynastrySource { DIRECT, LINK }

/** 이유 한 줄의 결: 서로 힘을 보태는 연결인지, 부딪히는 연결인지(토성 · 화성이 낀 합은 부딪히는 쪽 — 서버의 점수 규칙). */
enum class ReasonTone { EASY, TENSE }

/** 이렇게 본 이유 한 줄: 상대의 천체 [theirs] 가 내 [mine] 에 만드는 각도 [type]. 점수에 쓴 각도와 같다. 황경 · 별자리는 싣지 않는다. */
data class SynastryReason(val mine: SynastryTarget, val theirs: Body, val type: AspectType, val tone: ReasonTone)

/** 점수에 쓴 각도를 센 것부터(세기 = 점수 × 가중치의 크기). [synastryFactsText] 가 모델에게 적는 순서와 같은 세기다. */
fun reasonsOf(aspects: List<SynastryAspect>, relation: Relation): List<SynastryReason> = aspects
	.sortedByDescending { abs(it.points) * synastryWeight(relation, it) }
	.map { SynastryReason(it.mine, it.theirs, it.type, if (it.points >= 0.0) ReasonTone.EASY else ReasonTone.TENSE) }

/**
 * 궁합 하나. 값이 없는 키는 보내지 않는다.
 * - [relation] · [score] 는 `NEW_STAR` 가 아니면 있고, [headline] · [keywords] 는 `DONE` 이면 있다(쓰는 중 · 실패에도 옛 글이 남아 있을 수 있다).
 * - [scoreLabel] 은 60점 미만일 때만(결정 010 의 6 — 1.1.0 앱은 쓰지 않는다).
 * - [reasons] · [report] 는 `GET /synastry/{id}` 가 `DONE` 일 때만 싣는다. [reasons] 는 상대 출생 정보가 저장된 궁합에만 있다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Synastry(
	val id: UUID,
	val name: String,
	val relation: Relation?,
	val status: SynastryStatus,
	val source: SynastrySource,
	val score: Int?,
	val scoreLabel: String?,
	val headline: String?,
	val keywords: List<String>?,
	/** 만든 때(UTC, `2026-11-11T05:12:33Z`). */
	val createdAt: String,
	val reasons: List<SynastryReason>? = null,
	val report: SynastryReport? = null,
)

/** `POST /synastry` · `POST /synastry/{id}/retry` 의 202 응답. */
data class SynastryStarted(val id: UUID, val status: SynastryStatus)

/** 랭킹의 한 줄. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RankingEntry(val id: UUID, val name: String, val score: Int, val scoreLabel: String?, val keywords: List<String>)

/** 모델이 쓴 키워드 3개와 한 줄 요약. */
data class SynastrySummary(val keywords: List<String>, val headline: String)

private val SYNASTRY_RULES: String by lazy { promptResource("synastry.md") }
private val SUMMARY_RULES: String by lazy { promptResource("synastry-summary.md") }

/** 모든 궁합 글의 지시문 머리: 공통 글쓰기 규칙 + 두 사람 글의 규칙. */
internal val SYNASTRY_INSTRUCTIONS: String by lazy { listOf(WRITING_RULES, SYNASTRY_RULES).joinToString("\n\n") }

/** 키워드 3개와 한 줄 요약을 쓰게 하는 요청. 응답 구조에 점수 칸은 없다. */
fun summaryRequest(facts: SynastryFacts): GenerationRequest {
	val string = mapOf("type" to "string")
	return GenerationRequest(
		purpose = "synastry_summary",
		instructions = listOf(SYNASTRY_INSTRUCTIONS, SUMMARY_RULES).joinToString("\n\n"),
		userText = synastryFactsText(facts),
		schema = mapOf(
			"type" to "object",
			"properties" to mapOf("keywords" to mapOf("type" to "array", "items" to string), "headline" to string),
			"required" to listOf("keywords", "headline"),
			"additionalProperties" to false,
		),
	)
}

/** 생성기의 응답을 읽는다. 필드가 빠졌거나 타입이 틀리면 null. */
fun parseSummary(root: JsonNode): SynastrySummary? = SynastrySummary(
	keywords = root["keywords"].stringsOrNull() ?: return null,
	headline = root["headline"].stringOrNull() ?: return null,
)

/** 글에 숫자가 있는가. 점수는 서버만 말한다: 모델이 글 속에 다른 점수를 적지 못하게 한다. */
private fun hasDigit(text: String): Boolean = text.any { it.isDigit() }

/**
 * 저장해도 되는 키워드·한 줄 요약인지 본다. 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 키워드가 3개가 아님, 빈 글, 같은 키워드, 너무 긴 글
 * - `HAS_NUMBER`: 글에 숫자가 있다
 */
fun summaryProblem(summary: SynastrySummary): String? {
	val texts = summary.keywords + summary.headline
	val wellFormed = summary.keywords.size == KEYWORD_COUNT &&
		texts.none { it.isBlank() } &&
		summary.keywords.distinct().size == KEYWORD_COUNT &&
		summary.keywords.none { it.length > MAX_KEYWORD_LENGTH } &&
		summary.headline.length <= MAX_HEADLINE_LENGTH
	if (!wellFormed) return "MALFORMED"
	if (texts.any(::hasDigit)) return "HAS_NUMBER"
	return null
}
