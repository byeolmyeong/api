package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.SynastryAspect
import com.byeolmyeong.api.chart.SynastryTarget
import com.byeolmyeong.api.chart.signOf
import com.byeolmyeong.api.chart.synastryWeight
import com.byeolmyeong.api.generation.ASCENDANT_LABEL
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.WRITING_RULES
import com.byeolmyeong.api.generation.label
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.stringOrNull
import com.byeolmyeong.api.generation.stringsOrNull
import com.byeolmyeong.api.web.longitudeHundredths
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.math.abs

// 궁합의 모양과, 궁합 글에만 있는 것: 두 차트 사이의 연결을 글로 옮기기, 지시문, 돌려받을 구조, 검증.
// 응답 형태의 원본은 docs/architecture.md "API 계약". 무엇을 저장하지 않는지는 docs/decisions/008.

/** 이 점수 미만이면 "다른 결의 사이" 를 붙이고 끌리는 점부터 보여 준다(앱 저장소 release-1.0.0 F5). 앱이 아니라 서버가 정한다. */
const val LOW_SCORE_BELOW = 60
const val LOW_SCORE_LABEL = "다른 결의 사이"

fun scoreLabel(score: Int): String? = LOW_SCORE_LABEL.takeIf { score < LOW_SCORE_BELOW }

const val KEYWORD_COUNT = 3
const val MAX_NAME_LENGTH = 20
private const val MAX_KEYWORD_LENGTH = 20
private const val MAX_HEADLINE_LENGTH = 100

/** `NONE` 은 리포트를 요청한 적이 없다는 뜻이다(행이 없다). */
enum class SynastryReportStatus { NONE, WRITING, FAILED, DONE }

data class OverlayBody(val body: Body, val longitude: Double)

/** 휠 위의 선 하나: 상대의 천체 [theirs] 가 내 [mine] 에 만드는 각도. 점수에 쓴 각도와 같다. */
data class OverlayLine(val mine: SynastryTarget, val theirs: Body, val type: AspectType)

/**
 * 두 차트를 겹친 휠의 자료. 상대의 행성 위치가 들어 있어 저장하지 않고, 계산한 그 응답에만 싣는다(결정 010).
 * [mineAsc] 는 내 태어난 시각을 알 때만 있다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Overlay(val mine: List<OverlayBody>, val mineAsc: Double?, val theirs: List<OverlayBody>, val lines: List<OverlayLine>)

fun overlayOf(mine: Chart, theirs: Chart, aspects: List<SynastryAspect>): Overlay = Overlay(
	mine = mine.positions.map { OverlayBody(it.body, longitudeHundredths(it.longitude)) },
	mineAsc = mine.houses?.let { longitudeHundredths(it.ascendant) },
	theirs = theirs.positions.map { OverlayBody(it.body, longitudeHundredths(it.longitude)) },
	lines = aspects.map { OverlayLine(it.mine, it.theirs, it.type) },
)

/** 궁합 하나. [scoreLabel] 은 60점 미만일 때만, [overlay] 는 `POST /synastry/direct` 의 응답에만 있다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Synastry(
	val id: UUID,
	val name: String,
	val relation: Relation,
	val score: Int,
	val scoreLabel: String?,
	val headline: String,
	val keywords: List<String>,
	val reportStatus: SynastryReportStatus,
	/** 만든 때(UTC, `2026-11-11T05:12:33Z`). */
	val createdAt: String,
	val overlay: Overlay? = null,
)

/** 랭킹의 한 줄. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RankingEntry(val id: UUID, val name: String, val score: Int, val scoreLabel: String?, val keywords: List<String>)

/** 모델이 쓴 키워드 3개와 한 줄 요약. */
data class SynastrySummary(val keywords: List<String>, val headline: String)

private val SYNASTRY_RULES: String by lazy { promptResource("synastry.md") }
private val SUMMARY_RULES: String by lazy { promptResource("synastry-summary.md") }

/** 모든 궁합 글의 지시문 머리: 공통 글쓰기 규칙 + 두 사람 글의 규칙. */
internal val SYNASTRY_INSTRUCTIONS: String by lazy { listOf(WRITING_RULES, SYNASTRY_RULES).joinToString("\n\n") }

val Relation.label: String
	get() = when (this) {
		Relation.LOVE -> "연애"
		Relation.FRIEND -> "친구"
		Relation.WORK -> "일"
		Relation.FAMILY -> "가족"
	}

/** 궁합 글의 재료. 서버가 계산한 것만 담는다. */
class SynastryFacts(val mine: Chart, val theirs: Chart, val aspects: List<SynastryAspect>, val relation: Relation, val score: Int)

/**
 * 두 차트 사이의 연결을 모델이 읽을 글로 옮긴다. 넣는 것은 관계 유형·점수·연결(천체 이름, 별자리, 연결의 결)뿐이다.
 * 부를 이름, 출생 날짜·시각·장소, 도수는 넣지 않는다. 강한 연결부터 적는다(세기는 서버가 계산한 점수 × 가중치).
 */
fun synastryFactsText(facts: SynastryFacts): String {
	fun mineLabel(target: SynastryTarget): String {
		val body = target.body ?: return "$ASCENDANT_LABEL(${signOf(facts.mine.houses!!.ascendant).label})"
		return "${body.label}(${facts.mine.positions.first { it.body == body }.sign.label})"
	}
	fun theirsLabel(body: Body) = "${body.label}(${facts.theirs.positions.first { it.body == body }.sign.label})"
	fun lines(aspects: List<SynastryAspect>): List<String> = aspects
		.sortedByDescending { abs(it.points) * synastryWeight(facts.relation, it) }
		.map { aspect ->
			val important = if (synastryWeight(facts.relation, aspect) > 1.0) " (이 관계에서 특히 중요)" else ""
			"나의 ${mineLabel(aspect.mine)} – 상대의 ${theirsLabel(aspect.theirs)}: ${feel(aspect)}$important"
		}
		.ifEmpty { listOf("(없음)") }

	val (easy, tense) = facts.aspects.partition { it.points >= 0.0 }
	val low = scoreLabel(facts.score)?.let { " — ${LOW_SCORE_BELOW}점 미만: $it" } ?: ""
	val text = mutableListOf("[관계 유형]", facts.relation.label, "")
	text += "[점수] (서버가 계산했다. 읽는 사람 기준이고 40점에서 100점 사이다)"
	text += "${facts.score}점$low"
	text += ""
	text += "[잘 맞는 연결] (강한 것부터)"
	text += lines(easy)
	text += ""
	text += "[부딪히는 연결] (강한 것부터)"
	text += lines(tense)
	return text.joinToString("\n")
}

// 각도 종류를 생활의 말로. 합은 점수가 마이너스면(토성·화성이 낀 합) 부담 쪽이다.
private fun feel(aspect: SynastryAspect): String = when (aspect.type) {
	AspectType.TRINE -> "애쓰지 않아도 자연스럽게 통한다"
	AspectType.SEXTILE -> "조금만 다가가면 잘 통한다"
	AspectType.CONJUNCTION -> if (aspect.points >= 0.0) "하나로 겹쳐 강하게 끌린다" else "겹쳐서 부담이나 마찰이 되기 쉽다"
	AspectType.SQUARE -> "방식이 달라 부딪히기 쉽다"
	AspectType.OPPOSITION -> "서로 반대쪽에서 당기며 어긋나기 쉽다"
}

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
