package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.ChartAngle
import com.byeolmyeong.api.chart.PointKind
import com.byeolmyeong.api.chart.Sign
import com.byeolmyeong.api.chart.SynastryTarget
import com.byeolmyeong.api.chart.computeAngleAspects
import com.byeolmyeong.api.chart.signOf
import com.byeolmyeong.api.chart.traditionalRuler
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

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

/**
 * 근거 구조([reason])를 옛 근거 말(`basisVocabulary` 의 낱말, 겹침 없이 순서대로)로 옮긴다. 요약 해석의 옛 칸 `sections[].basis` 를
 * 옛 앱이 읽는 동안만 함께 보낸다(결정 031 의 8 — 계획 S⑦ 에서 뺀다). 통과는 태어날 때의 글에 없으므로 낱말이 없다.
 */
fun basisWords(reason: Reason): List<String> = reason.items.flatMap { item ->
	when (item) {
		is PlacementBasis -> listOfNotNull(pointLabel(item.body), item.sign.label, item.house?.let(::houseLabel))
		is ClusterBasis -> listOf(houseLabel(item.house)) + item.bodies.map { it.label }
		is AspectBasis -> listOf(pointLabel(item.a), pointLabel(item.b))
		is RulerBasis -> listOf(pointLabel(item.body), item.ruler.label)
		is TransitBasis -> emptyList()
	}
}.distinct()

// "천체 또는 상승점·중천" 칸의 글자 열(Body 또는 ChartAngle 의 이름)을 근거 말로.
private fun pointLabel(name: String): String =
	ChartAngle.entries.firstOrNull { it.name == name }?.label ?: Body.valueOf(name).label

/** 궁합 글의 근거로 쓸 수 있는 말: 점수에서 보는 내 쪽 요소(태양·달·수성·금성·화성, 시각을 알면 상승점). */
fun synastryBasisVocabulary(mine: Chart): List<String> =
	SynastryTarget.entries.mapNotNull { target -> target.body?.label ?: ASCENDANT_LABEL.takeIf { mine.houses != null } }

// ── 근거 구조(결정 031) ──
// 서버가 차트에서 근거 후보를 모두 만들어 id 를 붙여 프롬프트("[근거 후보]")로 주고, 모델은 응답의 `basis` 에 id 를 고른다(구조 지정의 enum).
// 서버는 고른 id 를 구조(BasisItem)로 바꿔 응답의 `reason` 에 넣는다. 문장은 앱이 정해진 틀과 용어 사전으로 만든다 — 서버는 보내지 않는다.
// "천체 또는 상승점·중천" 칸(PlacementBasis.body 등)은 글자 열이다: 천체는 `Body` 의 이름, 상승점·중천은 `ChartAngle` 의 이름("ASC"·"MC").

/** 근거 하나. JSON 은 `kind` 로 나뉘고 값이 없는 키는 보내지 않는다. 형태의 원본은 docs/architecture.md "API 계약". */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
	JsonSubTypes.Type(value = PlacementBasis::class, name = "PLACEMENT"),
	JsonSubTypes.Type(value = ClusterBasis::class, name = "CLUSTER"),
	JsonSubTypes.Type(value = AspectBasis::class, name = "ASPECT"),
	JsonSubTypes.Type(value = RulerBasis::class, name = "RULER"),
	JsonSubTypes.Type(value = TransitBasis::class, name = "TRANSIT"),
)
sealed interface BasisItem

/** 천체(또는 상승점·중천)가 놓인 별자리와 하우스. 시각을 모르거나 상승점·중천이면 [house] 가 없다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PlacementBasis(val body: String, val sign: Sign, val house: Int? = null) : BasisItem

/** 한 하우스에 천체가 3개 이상. 시각을 알 때만. */
data class ClusterBasis(val house: Int, val bodies: List<Body>) : BasisItem

/** 태어날 때 두 점의 각도. [a] 가 닿는 쪽(바깥 별 — 토성이 해에), 상승점·중천은 늘 [b]. */
data class AspectBasis(val a: String, val b: String, val type: AspectType) : BasisItem

/** [body] 가 놓인 별자리를 다스리는 별(전통 지배성). */
data class RulerBasis(val body: String, val ruler: Body) : BasisItem

/** 지나가는 별이 내 점에 각도를 이루거나([target] + [type]) 내 하우스를 지난다([house]). 시기 · 그날에만 나온다(계획 S③ · S④). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TransitBasis(val mover: Body, val target: String? = null, val type: AspectType? = null, val house: Int? = null) : BasisItem

/** 언제의 하늘인지: 태어날 때(요약 해석 · 나 탭 풀이), 지금 지나는 시기(시기 풀이), 그날(이번 달 · 그날의 하늘). */
enum class ReasonWhen { BIRTH, NOW, DAY }

/** 문단 · 실천 묶음 하나의 "이렇게 풀이한 이유". [items] 는 1~2개. */
data class Reason(val `when`: ReasonWhen, val items: List<BasisItem>)

/** 프롬프트에 주는 근거 후보 하나: 모델이 고르는 [id], 서버가 응답에 넣는 [item], 모델이 읽는 설명 [text](용어는 모델에게만). */
data class BasisCandidate(val id: String, val item: BasisItem, val text: String)

val ChartAngle.label: String
	get() = when (this) {
		ChartAngle.ASC -> ASCENDANT_LABEL
		ChartAngle.MC -> MIDHEAVEN_LABEL
	}

private val AspectType.feel: String
	get() = when (this) {
		AspectType.CONJUNCTION -> "한곳에 겹침"
		AspectType.SEXTILE -> "가볍게 도움"
		AspectType.SQUARE -> "직각으로 부딪힘"
		AspectType.TRINE -> "서로 도움"
		AspectType.OPPOSITION -> "맞은편에서 마주 봄"
	}

private const val CLUSTER_MIN_BODIES = 3

// 상승점·중천과의 각도는 해·달의 합·충·사분만 후보로 둔다(결정 031 의 4).
private val ANGLE_ASPECT_BODIES = setOf(Body.SUN, Body.MOON)
private val ANGLE_ASPECT_TYPES = setOf(AspectType.CONJUNCTION, AspectType.OPPOSITION, AspectType.SQUARE)

// 다스리는 별은 해·달(그리고 시각을 알면 상승점)의 별자리만 본다.
private val RULED_BODIES = listOf(Body.SUN, Body.MOON)

/**
 * 이 차트의 근거 후보 전부(id 겹침 없음, 순서 고정 — 결정 031 의 3 · 4).
 * 1. 놓인 자리 `P-SUN` … `P-PLUTO`, 시각을 알면 `P-ASC` · `P-MC`
 * 2. 하우스 모임 `C-5` — 시각을 알 때 천체 3개 이상인 하우스, 하우스 순
 * 3. 각도 `A-SATURN-SUN-SQUARE` — 천체 10개 사이(뒤의 별이 앞, Body 순서) + 시각을 알면 해·달과 상승점·중천의 합·충·사분. 정확한 것부터
 * 4. 다스리는 별 `R-SUN` · `R-MOON` · (시각을 알면) `R-ASC` — 다스리는 별이 그 별 자신이면 없다
 * 지점(북쪽 교점 · 릴리스 · 행운점 · 버텍스)은 후보가 아니다. 시각을 모르면 하우스 · 상승점 · 중천이 든 후보가 없다.
 */
fun basisCandidates(chart: Chart): List<BasisCandidate> {
	val houses = chart.houses
	val angleSigns = houses?.let { listOf(ChartAngle.ASC to signOf(it.ascendant), ChartAngle.MC to signOf(it.midheaven)) }.orEmpty()

	val placements = chart.positions.map {
		BasisCandidate("P-${it.body}", PlacementBasis(it.body.name, it.sign, it.house), placedText(it.body.label, it.sign, it.house))
	} + angleSigns.map { (angle, sign) -> BasisCandidate("P-$angle", PlacementBasis(angle.name, sign), placedText(angle.label, sign, null)) }

	val clusters = chart.positions.filter { it.house != null }.groupBy { it.house!! }
		.filterValues { it.size >= CLUSTER_MIN_BODIES }.toSortedMap()
		.map { (house, inHouse) ->
			val bodies = inHouse.map { it.body }
			BasisCandidate("C-$house", ClusterBasis(house, bodies), "${houseLabel(house)}에 모인 천체 — ${bodies.joinToString(", ") { it.label }}")
		}

	val planetAspects = chart.aspects.map { aspect ->
		aspect.orb to aspectCandidate(aspect.b.name, aspect.b.label, aspect.a.name, aspect.a.label, aspect.type)
	}
	val angleAspects = houses?.let { computeAngleAspects(chart.positions.filter { it.body in ANGLE_ASPECT_BODIES }, it) }.orEmpty()
		.filter { it.type in ANGLE_ASPECT_TYPES }
		.map { it.orb to aspectCandidate(it.body.name, it.body.label, it.angle.name, it.angle.label, it.type) }
	val aspects = (planetAspects + angleAspects).sortedBy { it.first }.map { it.second }

	val rulers = chart.positions.filter { it.body in RULED_BODIES }.mapNotNull { position ->
		val ruler = traditionalRuler(position.sign).takeIf { it != position.body } ?: return@mapNotNull null
		BasisCandidate("R-${position.body}", RulerBasis(position.body.name, ruler), "${position.body.label}이 놓인 별자리(${position.sign.label})를 다스리는 별 — ${ruler.label}")
	} + angleSigns.filter { it.first == ChartAngle.ASC }.map { (angle, sign) ->
		val ruler = traditionalRuler(sign)
		BasisCandidate("R-$angle", RulerBasis(angle.name, ruler), "${angle.label}의 별자리(${sign.label})를 다스리는 별 — ${ruler.label}")
	}

	return placements + clusters + aspects + rulers
}

private fun placedText(name: String, sign: Sign, house: Int?): String = listOfNotNull(name, sign.label, house?.let(::houseLabel)).joinToString(" · ")

private fun aspectCandidate(a: String, aLabel: String, b: String, bLabel: String, type: AspectType) =
	BasisCandidate("A-$a-$b-$type", AspectBasis(a, b, type), "$aLabel – $bLabel · ${type.feel}")

/** "이 풀이에 쓰인 별" 하나(결정 031 의 6). 값이 없는 키는 보내지 않는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Star(val body: String? = null, val sign: Sign? = null, val house: Int? = null, val count: Int? = null)

const val MAX_STARS = 3

/**
 * 그 영역 문단들의 [reasons] 에서 "이 풀이에 쓰인 별" 을 앞에서부터 겹치지 않게(같은 별 · 같은 하우스는 한 번) [MAX_STARS] 개까지 만든다.
 * 놓인 자리 → 별 + 별자리, 하우스 모임 → 하우스 + 천체 수, 각도 · 다스리는 별 → 앞쪽 별 하나. 통과는 별이 되지 않는다(태어날 때의 근거만).
 */
fun starsOf(reasons: List<Reason>): List<Star> =
	reasons.flatMap { it.items }.mapNotNull(::starOf).distinctBy { it.body ?: "house-${it.house}" }.take(MAX_STARS)

private fun starOf(item: BasisItem): Star? = when (item) {
	is PlacementBasis -> Star(body = item.body, sign = item.sign)
	is ClusterBasis -> Star(house = item.house, count = item.bodies.size)
	is AspectBasis -> Star(body = item.a)
	is RulerBasis -> Star(body = item.body)
	is TransitBasis -> null
}
