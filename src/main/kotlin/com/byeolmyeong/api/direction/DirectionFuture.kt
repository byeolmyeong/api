package com.byeolmyeong.api.direction

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.Milestone
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.basisVocabulary
import com.byeolmyeong.api.generation.chartFacts
import com.byeolmyeong.api.generation.promptResource
import com.byeolmyeong.api.generation.timelineFact
import java.time.Instant
import java.time.LocalDate

// 미래 주제에만 있는 것: 서버가 계산한 시기에서 "지금의 시기" 를 가리기, 그 재료가 같은 동안(저장의 대상), 지시문. 결정 011.
// 날짜는 모두 한국 달력의 날짜다(리포트의 시기와 같다).

/**
 * 오늘의 "지금의 시기". [lines] 는 모델에게 주는 줄이고 해·날짜·나이가 없다.
 * [from]~[until] 은 이 재료가 그대로인 동안이다(앞이나 뒤에 시작하거나 끝나는 것이 없으면 null).
 */
class FutureSituation(val lines: List<String>, val from: LocalDate?, val until: LocalDate?)

private const val NOW = "지금 지나는 중"
private const val NEXT = "다음에 오는 것"
private const val NONE = "(없음)"

/**
 * [today] 에 걸쳐 있는 마디·구간 전부(`from <= today <= to`)와, 그 뒤에 처음 시작하는 구간과 마디를 고른다.
 * 재료가 바뀌는 날은 무엇이 시작하는 날과 끝난 다음 날뿐이므로, 그 경계 사이가 한 편의 글이 맞는 동안이다.
 */
fun futureSituation(milestones: List<Milestone>, intervals: List<Interval>, today: LocalDate): FutureSituation {
	fun day(instant: Instant): LocalDate = instant.atZone(KOREA_ZONE).toLocalDate()
	fun covers(from: Instant, to: Instant) = day(from) <= today && today <= day(to)

	val current = milestones.filter { covers(it.from, it.to) }.map(::timelineFact) + intervals.filter { covers(it.from, it.to) }.map(::timelineFact)
	val next = listOfNotNull(
		intervals.filter { day(it.from) > today }.minByOrNull { it.from }?.let(::timelineFact),
		milestones.filter { day(it.from) > today }.minByOrNull { it.from }?.let(::timelineFact),
	)
	val boundaries = (milestones.map { it.from to it.to } + intervals.map { it.from to it.to })
		.flatMap { (from, to) -> listOf(day(from), day(to).plusDays(1)) }
	return FutureSituation(
		lines = current.ifEmpty { listOf(NONE) }.map { "- $NOW | $it" } + next.ifEmpty { listOf(NONE) }.map { "- $NEXT | $it" },
		from = boundaries.filter { it <= today }.maxOrNull(),
		until = boundaries.filter { it > today }.minOrNull()?.minusDays(1),
	)
}

/** `directions.target`: 차트 해시 | 그 동안의 첫날 | 마지막 날. 경계가 없는 쪽은 빈 글자다. */
fun futureTarget(chartHash: String, situation: FutureSituation): String =
	"$chartHash|${situation.from ?: ""}|${situation.until ?: ""}"

/** 그 차트의 미래 글을 찾을 때 쓰는 `target` 의 앞머리. */
fun futureTargetPrefix(chartHash: String): String = "$chartHash|"

/** 저장된 글의 [target] 이 [today] 를 덮는가. 천체 계산 없이 날짜 글자만 비교한다. */
fun futureTargetCovers(target: String, today: LocalDate): Boolean {
	val (_, from, until) = target.split("|").takeIf { it.size == 3 } ?: return false
	val day = today.toString()
	return (from.isEmpty() || from <= day) && (until.isEmpty() || day <= until)
}

private val FUTURE_RULES: String by lazy { promptResource("direction-future.md") }

/** 미래 주제의 요청. 차트 글 아래에 지금의 시기를 붙인다. 근거 말은 이성과 같다. */
fun futureDirectionRequest(chart: Chart, situation: FutureSituation): GenerationRequest = GenerationRequest(
	purpose = "direction_future",
	instructions = directionInstructions(FUTURE_RULES, timeKnown = chart.timeKnown),
	userText = chartFacts(chart) + "\n\n[지금의 시기] (서버가 계산했다. 언제인지는 앱이 따로 보여 주므로 여기에는 없다)\n" + situation.lines.joinToString("\n"),
	schema = directionSchema(basisVocabulary(chart)),
)
