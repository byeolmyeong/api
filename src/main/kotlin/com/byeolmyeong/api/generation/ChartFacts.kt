package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.Aspect
import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.signOf

// 프롬프트의 재료. 지시문은 리소스 파일(src/main/resources/prompts/)에, 차트는 아래 함수가 글로 옮긴다.
// 넣는 것은 차트 요소(천체·별자리·하우스·각도 관계)뿐이다. 출생 날짜·시각·장소와 도수는 넣지 않는다.

/** `src/main/resources/prompts/` 의 지시문 파일 하나를 읽는다. */
fun promptResource(name: String): String {
	val path = "/prompts/$name"
	val stream = checkNotNull(TextGenerator::class.java.getResourceAsStream(path)) { "$path 가 없다" }
	return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
}

/** 모든 글에 공통인 글쓰기 규칙(말투·용어 금지·단정 금지·근거). 글마다의 지시문 앞에 붙인다. */
val WRITING_RULES: String by lazy { promptResource("writing-rules.md") }

/**
 * 서버가 계산한 차트를 모델이 읽을 글로 옮긴다. 시각을 모르면 하우스·상승점·중천이 들어가지 않는다.
 * [candidates] 를 주면 끝에 "[근거 후보]"(id: 설명, 결정 031)를, 없으면 옛 "[근거로 쓸 수 있는 말]" 을 붙인다 —
 * 근거를 낱말로 받는 옛 글(리포트 · 방향성 제시)이 남아 있는 동안만 둘이다(계획 S⑦).
 */
fun chartFacts(chart: Chart, candidates: List<BasisCandidate>? = null): String {
	fun placed(name: String, sign: String, house: Int?) = "$name: $sign" + (house?.let { " · ${houseLabel(it)}" } ?: "")
	val lines = mutableListOf("[천체]")
	lines += chart.positions.map { placed(it.body.label, it.sign.label, it.house) }
	if (chart.moonSignMayChange) lines += "(달의 별자리는 태어난 시각에 따라 이웃 별자리일 수 있다. 달을 바탕으로 쓰는 문단은 이 점을 감안해 너무 좁게 단정하지 않는다.)"
	lines += ""
	lines += "[그 밖의 지점]"
	lines += chart.points.map { placed(it.kind.label, it.sign.label, it.house) }
	chart.houses?.let {
		lines += ""
		lines += "[상승점·중천]"
		lines += "$ASCENDANT_LABEL: ${signOf(it.ascendant).label}"
		lines += "$MIDHEAVEN_LABEL: ${signOf(it.midheaven).label}"
	}
	lines += ""
	lines += "[천체가 가장 많이 모인 곳]"
	lines += crowded(chart)
	lines += ""
	lines += "[서로 부딪히는 천체 쌍]"
	lines += pairs(chart, AspectType.SQUARE, AspectType.OPPOSITION)
	lines += ""
	lines += "[서로 돕는 천체 쌍]"
	lines += pairs(chart, AspectType.TRINE, AspectType.SEXTILE)
	lines += ""
	lines += "[한곳에 겹친 천체 쌍]"
	lines += pairs(chart, AspectType.CONJUNCTION)
	lines += ""
	if (candidates == null) {
		lines += "[근거로 쓸 수 있는 말]"
		lines += basisVocabulary(chart).joinToString(", ")
	} else {
		lines += "[근거 후보]"
		lines += candidates.map { "${it.id}: ${it.text}" }
	}
	return lines.joinToString("\n")
}

// 시각을 알면 하우스, 모르면 별자리로 센다. 가장 많은 곳이 여럿이면 모두 적는다. 세는 일을 모델에게 맡기지 않는다.
private fun crowded(chart: Chart): List<String> {
	val groups = chart.positions.groupBy({ it.house?.let(::houseLabel) ?: it.sign.label }, { it.body.label })
	val most = groups.values.maxOf { it.size }
	return groups.filterValues { it.size == most }.map { (place, bodies) -> "$place: ${bodies.joinToString(", ")}" }
}

// 정확한 각도에 가까운 쌍부터 적는다.
private fun pairs(chart: Chart, vararg types: AspectType): List<String> =
	chart.aspects.filter { it.type in types }.sortedBy(Aspect::orb).map { "${it.a.label} – ${it.b.label}" }.ifEmpty { listOf("(없음)") }
