package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.SynastryAspect
import com.byeolmyeong.api.chart.SynastryTarget
import com.byeolmyeong.api.chart.signOf
import com.byeolmyeong.api.chart.synastryWeight
import kotlin.math.abs

// 두 차트 사이의 연결을 모델이 읽을 글로 옮긴다. 궁합 글(요약·리포트)과 방향성 제시(궁합)가 같이 쓴다(결정 011).
// 상대의 출생 정보·행성 위치·연결 목록은 요청 안에서만 쓰고 저장하지 않는다(결정 010) — 이 재료를 들고 있는 쪽이 지킨다.

/** 이 점수 미만이면 "다른 결의 사이" 를 붙이고 끌리는 점부터 보여 준다(앱 저장소 release-1.0.0 F5). 앱이 아니라 서버가 정한다. */
const val LOW_SCORE_BELOW = 60
const val LOW_SCORE_LABEL = "다른 결의 사이"

fun scoreLabel(score: Int): String? = LOW_SCORE_LABEL.takeIf { score < LOW_SCORE_BELOW }

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
