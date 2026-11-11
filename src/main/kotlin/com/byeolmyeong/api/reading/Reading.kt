package com.byeolmyeong.api.reading

/** 문단 하나. [basis] 는 이 문단의 바탕이 된 차트 요소의 말(`generation.basisVocabulary` 에 있는 것만). */
data class ReadingSection(val title: String, val body: String, val basis: List<String>)

/** 생성기가 돌려준 글. 검증([draftProblem])을 통과해야 저장한다. */
data class ReadingDraft(
	val headline: String,
	val sections: List<ReadingSection>,
	val strengths: List<String>,
	val cautions: List<String>,
)

/** `POST /readings` 의 응답. 형태의 원본은 docs/architecture.md "API 계약". */
data class Reading(
	val headline: String,
	val sections: List<ReadingSection>,
	val strengths: List<String>,
	val cautions: List<String>,
	val shareId: String,
)
