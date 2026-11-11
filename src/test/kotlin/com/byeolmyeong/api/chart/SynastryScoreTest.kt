package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 규칙의 원본: 앱 저장소 docs/release-1.0.0.md F5 "점수 계산 규칙", 가족 가중치는 앱 결정 020.
class SynastryScoreTest {

	private fun position(body: Body, longitude: Double) =
		PlanetPosition(body, longitude, signOf(longitude), degreeInSign(longitude), retrograde = false, speed = 0.0)

	/** 적은 천체만 놓인 차트. [asc] 를 주면 시각을 아는 차트다. */
	private fun chart(vararg bodies: Pair<Body, Double>, asc: Double? = null) = Chart(
		timeKnown = asc != null,
		positions = bodies.map { position(it.first, it.second) },
		points = emptyList(),
		houses = asc?.let { Houses(ascendant = it, midheaven = 0.0, vertex = 0.0, cusps = List(12) { index -> index * 30.0 }) },
		aspects = emptyList(),
		moonSignMayChange = false,
	)

	private fun sum(mine: Chart, theirs: Chart, relation: Relation = Relation.FRIEND) =
		synastrySum(synastryAspects(mine, theirs), relation)

	@Test
	fun `각도마다 정해진 점수를 준다`() {
		val mine = chart(Body.SUN to 10.0)
		assertEquals(8.0, sum(mine, chart(Body.JUPITER to 130.0)), 1e-9, "삼분")
		assertEquals(5.0, sum(mine, chart(Body.JUPITER to 70.0)), 1e-9, "육분")
		assertEquals(6.0, sum(mine, chart(Body.JUPITER to 10.0)), 1e-9, "합")
		assertEquals(-6.0, sum(mine, chart(Body.JUPITER to 100.0)), 1e-9, "사분")
		assertEquals(-4.0, sum(mine, chart(Body.JUPITER to 190.0)), 1e-9, "충")
		assertEquals(8.0, sum(mine, chart(Body.JUPITER to 250.0)), 1e-9, "반대쪽으로 돈 삼분")
		assertEquals(0.0, sum(mine, chart(Body.JUPITER to 40.0)), 1e-9, "각도가 아님")
	}

	@Test
	fun `합에 토성이나 화성이 끼면 마이너스 3점이다`() {
		assertEquals(-3.0, sum(chart(Body.SUN to 10.0), chart(Body.SATURN to 10.0), Relation.FRIEND), 1e-9, "상대의 토성")
		assertEquals(-3.0, sum(chart(Body.SUN to 10.0), chart(Body.MARS to 10.0)), 1e-9, "상대의 화성")
		assertEquals(-3.0, sum(chart(Body.MARS to 10.0), chart(Body.JUPITER to 10.0)), 1e-9, "나의 화성")
		assertEquals(8.0, sum(chart(Body.MOON to 10.0), chart(Body.SATURN to 130.0)), 1e-9, "합이 아니면 그대로")
	}

	@Test
	fun `오차가 클수록 점수가 선형으로 줄고 허용 오차 끝에서 0 이 된다`() {
		val mine = chart(Body.SUN to 0.0)
		assertEquals(4.0, sum(mine, chart(Body.JUPITER to 123.5)), 1e-9, "삼분 7도의 절반")
		assertEquals(0.0, sum(mine, chart(Body.JUPITER to 127.0)), 1e-9, "삼분 7도 끝")
		assertTrue(synastryAspects(mine, chart(Body.JUPITER to 127.1)).isEmpty(), "삼분 7도 밖")
		assertEquals(1.0, sum(mine, chart(Body.JUPITER to 56.0)), 1e-9, "육분 5도 가운데 4도")
		assertTrue(synastryAspects(mine, chart(Body.JUPITER to 54.9)).isEmpty(), "육분 5도 밖")
		assertEquals(3.0, sum(mine, chart(Body.JUPITER to 356.0)), 1e-9, "합 8도의 절반")
		assertTrue(synastryAspects(mine, chart(Body.JUPITER to 8.1)).isEmpty(), "합 8도 밖")
		assertEquals(-3.0, sum(mine, chart(Body.JUPITER to 93.5)), 1e-9, "사분 7도의 절반")
		assertTrue(synastryAspects(mine, chart(Body.JUPITER to 97.1)).isEmpty(), "사분 7도 밖")
		assertEquals(-2.0, sum(mine, chart(Body.JUPITER to 176.0)), 1e-9, "충 8도의 절반")
		assertTrue(synastryAspects(mine, chart(Body.JUPITER to 171.9)).isEmpty(), "충 8도 밖")
	}

	@Test
	fun `내 쪽은 태양 달 수성 금성 화성만 보고 시각을 알면 상승점도 본다`() {
		val theirs = chart(Body.JUPITER to 120.0)
		assertEquals(0.0, sum(chart(Body.JUPITER to 0.0, Body.SATURN to 0.0, Body.PLUTO to 0.0), theirs), 1e-9, "내 바깥 행성은 보지 않는다")
		for (personal in listOf(Body.SUN, Body.MOON, Body.MERCURY, Body.VENUS, Body.MARS)) {
			assertEquals(8.0, sum(chart(personal to 0.0), theirs), 1e-9, "$personal")
		}
		assertEquals(0.0, sum(chart(Body.PLUTO to 50.0), theirs), 1e-9, "시각을 모르면 상승점이 없다")
		val withAsc = synastryAspects(chart(Body.PLUTO to 50.0, asc = 0.0), theirs)
		assertEquals(listOf(SynastryTarget.ASC to Body.JUPITER), withAsc.map { it.mine to it.theirs })
		assertEquals(8.0, synastrySum(withAsc, Relation.LOVE), 1e-9)
	}

	@Test
	fun `상대 쪽은 행성 10개를 모두 본다`() {
		val mine = chart(Body.SUN to 0.0)
		for (body in Body.entries) {
			assertEquals(1, synastryAspects(mine, chart(body to 120.0)).size, "$body")
		}
	}

	@Test
	fun `관계 유형마다 정해진 조합에 가중치를 준다`() {
		fun trine(mine: Body, theirs: Body, relation: Relation) = sum(chart(mine to 0.0), chart(theirs to 120.0), relation)

		// 연애: 금성-화성·태양-달 ×2 (어느 쪽이 누구 것이든)
		assertEquals(16.0, trine(Body.VENUS, Body.MARS, Relation.LOVE), 1e-9)
		assertEquals(16.0, trine(Body.MARS, Body.VENUS, Relation.LOVE), 1e-9)
		assertEquals(16.0, trine(Body.SUN, Body.MOON, Relation.LOVE), 1e-9)
		assertEquals(16.0, trine(Body.MOON, Body.SUN, Relation.LOVE), 1e-9)
		assertEquals(8.0, trine(Body.MERCURY, Body.MERCURY, Relation.LOVE), 1e-9)
		// 친구: 수성-수성 ×2 · 달-달 ×1.5
		assertEquals(16.0, trine(Body.MERCURY, Body.MERCURY, Relation.FRIEND), 1e-9)
		assertEquals(12.0, trine(Body.MOON, Body.MOON, Relation.FRIEND), 1e-9)
		assertEquals(8.0, trine(Body.VENUS, Body.MARS, Relation.FRIEND), 1e-9)
		// 일: 수성-수성 ×2 · (상대) 토성-(나) 태양 ×1.5
		assertEquals(16.0, trine(Body.MERCURY, Body.MERCURY, Relation.WORK), 1e-9)
		assertEquals(12.0, trine(Body.SUN, Body.SATURN, Relation.WORK), 1e-9)
		assertEquals(8.0, trine(Body.MOON, Body.MOON, Relation.WORK), 1e-9)
		// 가족: 달-달 ×2 · 태양-달 ×1.5
		assertEquals(16.0, trine(Body.MOON, Body.MOON, Relation.FAMILY), 1e-9)
		assertEquals(12.0, trine(Body.SUN, Body.MOON, Relation.FAMILY), 1e-9)
		assertEquals(12.0, trine(Body.MOON, Body.SUN, Relation.FAMILY), 1e-9)
		assertEquals(8.0, trine(Body.MERCURY, Body.MERCURY, Relation.FAMILY), 1e-9)
		// 가중치는 마이너스 점수에도 곱한다
		assertEquals(-12.0, sum(chart(Body.VENUS to 0.0), chart(Body.MARS to 90.0), Relation.LOVE), 1e-9)
	}

	// 표본: 1950~2010년의 임의 시각 두 개씩. 씨앗이 고정이라 늘 같은 쌍이다.
	private fun samplePairs(count: Int): List<Pair<Chart, Chart>> {
		val random = Random(20261111)
		val from = Instant.parse("1950-01-01T00:00:00Z").epochSecond
		val to = Instant.parse("2010-12-31T00:00:00Z").epochSecond
		fun person(): Chart {
			val timeKnown = random.nextInt(3) != 0
			return computeChart(Instant.ofEpochSecond(random.nextLong(from, to)), lat = 37.5, lon = 127.0, timeKnown = timeKnown)
		}
		return List(count) { person() to person() }
	}

	@Test
	fun `표본 차트에서 점수는 40 에서 100 사이이고 같은 입력에 같은 값이다`() {
		for ((mine, theirs) in samplePairs(300)) {
			for (relation in Relation.entries) {
				val score = synastryScore(mine, theirs, relation)
				assertTrue(score in 40..100, "$relation $score")
				assertEquals(score, synastryScore(mine, theirs, relation))
			}
		}
	}

	@Test
	fun `내가 본 상대와 상대가 본 나의 점수는 다를 수 있다`() {
		val pairs = samplePairs(100)
		val different = pairs.count { (a, b) -> synastryScore(a, b, Relation.LOVE) != synastryScore(b, a, Relation.LOVE) }
		assertTrue(different > pairs.size / 2, "방향에 따라 다른 쌍: $different")
	}

	@Test
	fun `관계 유형마다 점수가 다를 수 있다`() {
		val pairs = samplePairs(100)
		for (relation in Relation.entries) {
			for (other in Relation.entries.filter { it != relation }) {
				assertTrue(pairs.any { (a, b) -> synastryScore(a, b, relation) != synastryScore(a, b, other) }, "$relation 과 $other 가 늘 같다")
			}
		}
	}

	@Test
	fun `합산값을 40 에서 100 으로 바꾼다 - 마이너스 30 이하는 40 플러스 45 이상은 100 그 사이는 직선`() {
		assertEquals(40, scoreOfSum(-30.0))
		assertEquals(40, scoreOfSum(-200.0))
		assertEquals(100, scoreOfSum(45.0))
		assertEquals(100, scoreOfSum(200.0))
		assertEquals(64, scoreOfSum(0.0))
		assertEquals(72, scoreOfSum(10.0))
		assertEquals(60, scoreOfSum(-5.0))
		assertEquals(59, scoreOfSum(-6.0))
		val sums = (-400..600).map { it / 10.0 }
		assertTrue(sums.zipWithNext().all { (a, b) -> scoreOfSum(a) <= scoreOfSum(b) }, "합산값이 크면 점수도 크거나 같다")
	}

	// 변환 구간을 정한 근거(계획 태스크 9 실행 기록의 결정)가 계속 맞는지 본다.
	@Test
	fun `표본에서 점수가 한쪽 끝에 몰리지 않는다`() {
		val scores = samplePairs(300).flatMap { (a, b) -> Relation.entries.map { synastryScore(a, b, it) } }.sorted()
		val below60 = scores.count { it < 60 } * 100.0 / scores.size
		val atEnds = scores.count { it == 40 || it == 100 } * 100.0 / scores.size
		assertTrue(below60 in 8.0..22.0, "60점 미만 $below60%")
		assertTrue(atEnds < 5.0, "40점·100점 $atEnds%")
		assertTrue(scores[scores.size / 2] in 68..76, "가운데 값 ${scores[scores.size / 2]}")
	}
}
