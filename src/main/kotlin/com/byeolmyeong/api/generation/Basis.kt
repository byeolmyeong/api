package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.PointKind
import com.byeolmyeong.api.chart.Sign
import com.byeolmyeong.api.chart.SynastryTarget
import com.byeolmyeong.api.chart.signOf

// 근거(basis)에 쓰는 말. 프롬프트의 차트와 응답의 근거 대조가 같은 말을 쓰도록 여기 한 곳에서만 정한다.
// 원본 표는 docs/architecture.md "글 생성 규칙". 앱이 문단 머리에 그대로 보여 준다.

const val ASCENDANT_LABEL = "상승점"
const val MIDHEAVEN_LABEL = "중천"

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

/** 궁합 글의 근거로 쓸 수 있는 말: 점수에서 보는 내 쪽 요소(태양·달·수성·금성·화성, 시각을 알면 상승점). */
fun synastryBasisVocabulary(mine: Chart): List<String> =
	SynastryTarget.entries.mapNotNull { target -> target.body?.label ?: ASCENDANT_LABEL.takeIf { mine.houses != null } }
