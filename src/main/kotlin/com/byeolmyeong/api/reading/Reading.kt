package com.byeolmyeong.api.reading

import com.byeolmyeong.api.generation.Reason
import com.byeolmyeong.api.generation.basisWords

/** 생성기가 돌려준 문단 하나. [basis] 는 프롬프트 "[근거 후보]" 의 id 1~2개(결정 031). */
data class DraftSection(val title: String, val body: String, val basis: List<String>)

/** 생성기가 돌려준 글. 검증([draftProblem])을 통과해야 저장한다. */
data class ReadingDraft(
	val headline: String,
	val sections: List<DraftSection>,
	val strengths: List<String>,
	val cautions: List<String>,
)

/** 저장하는 문단: 고른 id 를 서버가 구조로 바꾼 [reason](결정 031). */
data class BodySection(val title: String, val body: String, val reason: Reason)

/** 저장하는 본문(`readings.body`, kind `SUMMARY_V2`). 응답은 여기에 옛 근거 낱말 · 만든 때 · 옛 공유 식별자를 더한다. */
data class ReadingBody(
	val headline: String,
	val sections: List<BodySection>,
	val strengths: List<String>,
	val cautions: List<String>,
) {
	fun toReading(createdAt: String, shareId: String): Reading = Reading(
		headline = headline,
		sections = sections.map { ReadingSection(it.title, it.body, it.reason, basisWords(it.reason)) },
		strengths = strengths,
		cautions = cautions,
		createdAt = createdAt,
		shareId = shareId,
	)
}

/** 응답의 문단. [basis] 는 옛 앱이 읽는 근거 낱말로 [reason] 에서 만든다(결정 030 의 5 — 계획 S⑦ 에서 뺀다). */
data class ReadingSection(val title: String, val body: String, val reason: Reason, val basis: List<String>)

/**
 * `POST /readings` 의 응답. 형태의 원본은 docs/architecture.md "API 계약".
 * [createdAt] 은 그 요약 해석을 만든 때(UTC, 초까지 — 결정 030 의 4), [shareId] 는 더 쓰지 않는 옛 값(계획 S⑦ 에서 뺀다).
 */
data class Reading(
	val headline: String,
	val sections: List<ReadingSection>,
	val strengths: List<String>,
	val cautions: List<String>,
	val createdAt: String,
	val shareId: String,
)
