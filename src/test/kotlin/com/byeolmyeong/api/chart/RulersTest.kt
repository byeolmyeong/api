package com.byeolmyeong.api.chart

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RulersTest {

	// 전통 지배성(결정 031): 화성 양·전갈 / 금성 황소·천칭 / 수성 쌍둥이·처녀 / 달 게 / 해 사자 / 목성 사수·물고기 / 토성 염소·물병
	@Test
	fun `별자리마다 전통 지배성의 별이 다스린다`() {
		val expected = mapOf(
			Sign.ARIES to Body.MARS,
			Sign.TAURUS to Body.VENUS,
			Sign.GEMINI to Body.MERCURY,
			Sign.CANCER to Body.MOON,
			Sign.LEO to Body.SUN,
			Sign.VIRGO to Body.MERCURY,
			Sign.LIBRA to Body.VENUS,
			Sign.SCORPIO to Body.MARS,
			Sign.SAGITTARIUS to Body.JUPITER,
			Sign.CAPRICORN to Body.SATURN,
			Sign.AQUARIUS to Body.SATURN,
			Sign.PISCES to Body.JUPITER,
		)
		assertEquals(expected, Sign.entries.associateWith(::traditionalRuler))
	}
}
