package com.byeolmyeong.api.chart

import kotlin.math.roundToInt

/**
 * 나의 일 방식 막대 셋(결정 038). 값은 왼쪽 끝에서 잰 점의 위치(정수 10~90)다.
 * [together] 혼자서 → 함께, [flexible] 계획대로 → 그때그때, [fast] 꼼꼼하게 → 빠르게.
 */
data class WorkStyle(val together: Int, val flexible: Int, val fast: Int)

// 공식의 원본은 docs/drafts/2026-11-20-work-style-formula.md(소유자 2026-11-20 "초안 그대로 써"). 값을 바꾸면 초안 · 기준값 테스트를 함께 바꾼다.

// 점 하나의 무게: 해 · 달 3, 수성 · 금성 · 화성 2, 목성 · 토성 1, 바깥 행성 0.5. 시각을 알면 상승점 2 · 중천 1.
private fun weightOf(body: Body): Double = when (body) {
	Body.SUN, Body.MOON -> 3.0
	Body.MERCURY, Body.VENUS, Body.MARS -> 2.0
	Body.JUPITER, Body.SATURN -> 1.0
	Body.URANUS, Body.NEPTUNE, Body.PLUTO -> 0.5
}

private const val ASCENDANT_WEIGHT = 2.0
private const val MIDHEAVEN_WEIGHT = 1.0

private enum class Element { FIRE, EARTH, AIR, WATER }

private enum class Modality { CARDINAL, FIXED, MUTABLE }

// 별자리 순서(양 · 황소 · 쌍둥이 · 게 …)가 원소는 불 · 흙 · 공기 · 물, 성질은 시작 · 고정 · 변화로 돈다.
private val Sign.element: Element get() = Element.entries[ordinal % Element.entries.size]
private val Sign.modality: Modality get() = Modality.entries[ordinal % Modality.entries.size]

// 일하는 별(화성 · 수성)의 원소 값: 불 1 · 공기 ½ · 물 −½ · 흙 −1.
private val Element.pace: Double
	get() = when (this) {
		Element.FIRE -> 1.0
		Element.AIR -> 0.5
		Element.WATER -> -0.5
		Element.EARTH -> -1.0
	}

private const val MIN_VALUE = 10.0
private const val MAX_VALUE = 90.0

/** −1~1 의 기울기를 막대의 점 위치로: round(clamp(50 + 50·r, 10, 90)), 0.5 는 올림. */
private fun toBar(r: Double): Int = (50.0 + 50.0 * r).coerceIn(MIN_VALUE, MAX_VALUE).roundToInt()

/**
 * 차트로 일 방식 막대 셋을 계산한다. 같은 차트면 늘 같은 값이다.
 * 시각을 모르면(`houses` 가 없으면) 상승점 · 중천과 하우스 항을 빼고 남은 항의 가중치를 합이 1 이 되게 다시 나눈다
 * (축 2 는 초안의 반올림한 0.70 · 0.30). 이때 천체의 하우스 값은 읽지 않는다.
 */
fun computeWorkStyle(chart: Chart): WorkStyle {
	val houses = chart.houses
	val points = chart.positions.map { it.sign to weightOf(it.body) } +
		houses?.let { listOf(signOf(it.ascendant) to ASCENDANT_WEIGHT, signOf(it.midheaven) to MIDHEAVEN_WEIGHT) }.orEmpty()
	val total = points.sumOf { it.second }
	fun share(element: Element) = points.filter { it.first.element == element }.sumOf { it.second } / total
	fun share(modality: Modality) = points.filter { it.first.modality == modality }.sumOf { it.second } / total

	val fire = share(Element.FIRE)
	val earth = share(Element.EARTH)
	val air = share(Element.AIR)
	val water = share(Element.WATER)
	val cardinal = share(Modality.CARDINAL)
	val fixed = share(Modality.FIXED)
	val mutable = share(Modality.MUTABLE)

	val e1 = (air + fire / 2) - (water + earth / 2)
	val m2 = mutable - fixed
	val e2 = (fire + air - earth - water) / 2
	val e3 = (fire + air / 2) - (earth + water / 2)
	val m3 = cardinal - fixed
	val p3 = (chart.positions.single { it.body == Body.MARS }.sign.element.pace + chart.positions.single { it.body == Body.MERCURY }.sign.element.pace) / 2

	if (houses == null) {
		return WorkStyle(
			together = toBar(e1),
			flexible = toBar(0.70 * m2 + 0.30 * e2),
			fast = toBar((0.40 * e3 + 0.25 * m3 + 0.20 * p3) / 0.85),
		)
	}

	// 하우스 몫: 그 하우스들에 놓인 행성(상승점 · 중천 빼고)의 무게 합 ÷ 행성 무게 합.
	val planetTotal = chart.positions.sumOf { weightOf(it.body) }
	fun houseShare(vararg set: Int) = chart.positions.filter { it.house != null && it.house in set }.sumOf { weightOf(it.body) } / planetTotal
	val h1 = houseShare(3, 7, 11) - houseShare(4, 8, 12)
	val h2 = houseShare(3, 9) - houseShare(2, 6, 10)
	val h3 = houseShare(1, 3) - houseShare(6, 8)

	return WorkStyle(
		together = toBar(0.65 * e1 + 0.35 * h1),
		flexible = toBar(0.55 * m2 + 0.25 * e2 + 0.20 * h2),
		fast = toBar(0.40 * e3 + 0.25 * m3 + 0.20 * p3 + 0.15 * h3),
	)
}
