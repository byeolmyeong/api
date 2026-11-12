package com.byeolmyeong.api.direction

import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.SynastryFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.synastryBasisVocabulary
import com.byeolmyeong.api.generation.synastryFactsText

// 궁합 주제에만 있는 것: 글이 붙는 궁합에서 읽는 것, 지시문과 재료. 결정 011.
// 상대의 출생 정보·행성 위치·연결 목록은 그 요청 안에서만 쓰고 저장하지 않는다(결정 010). 저장하는 것은 글뿐이다.

/** 글이 붙는 궁합 한 건에서 읽는 것(`synastries` 표). 점수와 관계 유형은 앱에서 받지 않는다. [ownerChartHash] 는 그 궁합을 계산한 때의 내 차트다. */
data class SynastryRef(val relation: Relation, val score: Int, val ownerChartHash: String)

/** 내 출생 정보가 그 궁합을 계산한 때와 다르다(409 `PROFILE_CHANGED`). 궁합을 새로 계산해야 한다. */
class ProfileChangedException : RuntimeException("PROFILE_CHANGED")

private val TWO_PEOPLE_RULES: String by lazy { promptResource("synastry.md") }
private val SYNASTRY_RULES: String by lazy { promptResource("direction-synastry.md") }

/** 궁합 주제의 요청. 재료는 궁합 글과 같고(관계 유형·점수·연결), 근거로 고를 수 있는 말은 내 쪽 요소뿐이다. */
fun synastryDirectionRequest(facts: SynastryFacts): GenerationRequest {
	val vocabulary = synastryBasisVocabulary(facts.mine)
	return GenerationRequest(
		purpose = "direction_synastry",
		// 두 차트의 연결에는 하우스가 없어 시각 모름 안내를 붙이지 않는다(내 시각을 모르면 상승점이 재료와 근거 말에서 빠질 뿐이다).
		instructions = directionInstructions(TWO_PEOPLE_RULES + "\n\n" + SYNASTRY_RULES, timeKnown = true),
		userText = synastryFactsText(facts) + "\n\n[근거로 쓸 수 있는 말]\n" + vocabulary.joinToString(", "),
		schema = directionSchema(vocabulary),
	)
}
