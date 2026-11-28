package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.SynastryTarget
import com.byeolmyeong.api.chart.synastryAspects
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 이렇게 본 이유의 결과 순서(결정 053 의 3)를 고정한 차트 한 쌍으로 직접 본다.
 * 나: 1990-03-15 14:30 마포, 상대: 1985-01-17 시각 모름. 이 쌍에는 토성 · 화성이 낀 합, 그렇지 않은 합, 토성과의 삼각이 다 있다.
 */
class SynastryReasonsTest {

	private val reasons = reasonsOf(synastryAspects(chartOf(MY_DATE, MY_TIME), chartOf("1985-01-17", null)), Relation.LOVE)

	private fun tone(mine: SynastryTarget, theirs: Body, type: AspectType): ReasonTone =
		reasons.single { it.mine == mine && it.theirs == theirs && it.type == type }.tone

	@Test
	fun `토성 · 화성이 낀 합은 TENSE 이고 그렇지 않은 합과 삼각은 EASY 다`() {
		assertEquals(ReasonTone.TENSE, tone(SynastryTarget.MERCURY, Body.MARS, AspectType.CONJUNCTION), "상대의 화성이 낀 합")
		assertEquals(ReasonTone.TENSE, tone(SynastryTarget.MARS, Body.JUPITER, AspectType.CONJUNCTION), "내 화성이 낀 합")
		assertEquals(ReasonTone.EASY, tone(SynastryTarget.MOON, Body.PLUTO, AspectType.CONJUNCTION), "토성 · 화성이 없는 합")
		assertEquals(ReasonTone.EASY, tone(SynastryTarget.SUN, Body.SATURN, AspectType.TRINE), "토성이어도 삼각은 힘을 보탠다")
		assertEquals(ReasonTone.TENSE, tone(SynastryTarget.ASC, Body.PLUTO, AspectType.SQUARE))
	}

	@Test
	fun `센 것부터 준다`() {
		assertEquals(
			listOf(
				Triple(SynastryTarget.SUN, Body.SATURN, AspectType.TRINE),
				Triple(SynastryTarget.ASC, Body.PLUTO, AspectType.SQUARE),
				Triple(SynastryTarget.ASC, Body.MOON, AspectType.TRINE),
			),
			reasons.take(3).map { Triple(it.mine, it.theirs, it.type) },
		)
	}
}
