package com.byeolmyeong.api.auth

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodaySkyTimeTest {

	@Test
	fun `5분 단위 HH mm 만 읽는다`() {
		assertEquals(LocalTime.of(8, 0), parseTodaySkyTime("08:00"))
		assertEquals(LocalTime.of(0, 0), parseTodaySkyTime("00:00"))
		assertEquals(LocalTime.of(23, 55), parseTodaySkyTime("23:55"))
		for (bad in listOf("08:01", "24:00", "8:00", "08:00:00", "", " 08:00", "08:0", "12:60", "-1:00", "０８:００")) assertNull(parseTodaySkyTime(bad), bad)
	}
}
