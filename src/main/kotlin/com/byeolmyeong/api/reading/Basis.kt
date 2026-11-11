package com.byeolmyeong.api.reading

import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.PointKind
import com.byeolmyeong.api.chart.Sign
import com.byeolmyeong.api.chart.signOf

// 근거(basis)에 쓰는 말. 프롬프트의 차트와 응답의 근거 대조가 같은 말을 쓰도록 여기 한 곳에서만 정한다.
// 원본 표는 docs/architecture.md "글 생성 규칙". 앱이 문단 머리에 그대로 보여 준다.

const val ASCENDANT_LABEL = "상승점"
const val MIDHEAVEN_LABEL = "중천"

/** 시각을 알 때 정확히 5문단, 모르면 "첫인상과 지향"이 빠져 4문단. */
const val SECTIONS_WITH_TIME = 5
const val SECTIONS_WITHOUT_TIME = 4
const val STRENGTH_COUNT = 3
const val CAUTION_COUNT = 3

val Body.label: String
	get() = when (this) {
		Body.SUN -> "태양"
		Body.MOON -> "달"
		Body.MERCURY -> "수성"
		Body.VENUS -> "금성"
		Body.MARS -> "화성"
		Body.JUPITER -> "목성"
		Body.SATURN -> "토성"
		Body.URANUS -> "천왕성"
		Body.NEPTUNE -> "해왕성"
		Body.PLUTO -> "명왕성"
	}

val PointKind.label: String
	get() = when (this) {
		PointKind.NORTH_NODE -> "북쪽 교점"
		PointKind.LILITH -> "릴리스"
		PointKind.FORTUNE -> "행운점"
		PointKind.VERTEX -> "버텍스"
	}

val Sign.label: String
	get() = when (this) {
		Sign.ARIES -> "양"
		Sign.TAURUS -> "황소"
		Sign.GEMINI -> "쌍둥이"
		Sign.CANCER -> "게"
		Sign.LEO -> "사자"
		Sign.VIRGO -> "처녀"
		Sign.LIBRA -> "천칭"
		Sign.SCORPIO -> "전갈"
		Sign.SAGITTARIUS -> "사수"
		Sign.CAPRICORN -> "염소"
		Sign.AQUARIUS -> "물병"
		Sign.PISCES -> "물고기"
	}

fun houseLabel(house: Int): String = "${house}하우스"

/**
 * 이 차트에서 근거로 쓸 수 있는 말 전부(겹침 없음, 순서 고정).
 * - 차트에 있는 천체(10개)와 지점(북쪽 교점·릴리스, 시각을 알면 행운점·버텍스)의 이름
 * - 시각을 알면 "상승점"·"중천"
 * - 천체·지점·상승점·중천 가운데 하나라도 놓인 별자리
 * - 천체가 하나라도 놓인 하우스("N하우스"). 시각을 모르면 없다.
 */
fun basisVocabulary(chart: Chart): List<String> {
	val angles = chart.houses?.let { listOf(ASCENDANT_LABEL to signOf(it.ascendant), MIDHEAVEN_LABEL to signOf(it.midheaven)) }.orEmpty()
	val names = chart.positions.map { it.body.label } + chart.points.map { it.kind.label } + angles.map { it.first }
	val occupiedSigns = (chart.positions.map { it.sign } + chart.points.map { it.sign } + angles.map { it.second }).toSet()
	val occupiedHouses = chart.positions.mapNotNull { it.house }.toSet()
	return names + Sign.entries.filter { it in occupiedSigns }.map { it.label } + (1..12).filter { it in occupiedHouses }.map(::houseLabel)
}

/**
 * 생성된 글을 저장해도 되는지 본다. 문제가 없으면 null, 있으면 로그용 코드를 준다.
 * - `MALFORMED`: 빈 글, 문단·강점·보완점 개수가 틀림, 근거가 없는 문단
 * - `BASIS_NOT_IN_CHART`: 근거에 [basisVocabulary] 에 없는 말이 있다(글자까지 같아야 한다)
 */
fun draftProblem(draft: ReadingDraft, chart: Chart): String? {
	val sectionCount = if (chart.timeKnown) SECTIONS_WITH_TIME else SECTIONS_WITHOUT_TIME
	val texts = listOf(draft.headline) + draft.sections.flatMap { listOf(it.title, it.body) } + draft.strengths + draft.cautions
	val wellFormed = draft.sections.size == sectionCount &&
		draft.strengths.size == STRENGTH_COUNT &&
		draft.cautions.size == CAUTION_COUNT &&
		texts.none { it.isBlank() } &&
		draft.sections.none { it.basis.isEmpty() }
	if (!wellFormed) return "MALFORMED"
	val allowed = basisVocabulary(chart).toSet()
	if (draft.sections.any { section -> section.basis.any { it !in allowed } }) return "BASIS_NOT_IN_CHART"
	return null
}
