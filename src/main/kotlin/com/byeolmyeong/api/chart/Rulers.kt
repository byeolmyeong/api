package com.byeolmyeong.api.chart

/** 별자리를 다스리는 별 — 전통 지배성(결정 031). 현대 지배성(물병 → 천왕성 등)은 쓰지 않는다. */
fun traditionalRuler(sign: Sign): Body = when (sign) {
	Sign.ARIES, Sign.SCORPIO -> Body.MARS
	Sign.TAURUS, Sign.LIBRA -> Body.VENUS
	Sign.GEMINI, Sign.VIRGO -> Body.MERCURY
	Sign.CANCER -> Body.MOON
	Sign.LEO -> Body.SUN
	Sign.SAGITTARIUS, Sign.PISCES -> Body.JUPITER
	Sign.CAPRICORN, Sign.AQUARIUS -> Body.SATURN
}
