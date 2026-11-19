package com.byeolmyeong.api.auth

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 만 14세 판정(결정 021). 한국 시간 오늘 기준 만 나이.
class AgeTest {

	@Test
	fun `14번째 생일 당일부터 만 14세다`() {
		assertTrue(isAtLeast14(LocalDate.of(2012, 11, 19), LocalDate.of(2026, 11, 19)))
		assertFalse(isAtLeast14(LocalDate.of(2012, 11, 20), LocalDate.of(2026, 11, 19)))
	}

	@Test
	fun `2월 29일생은 평년에 3월 1일에 나이를 먹는다`() {
		assertFalse(isAtLeast14(LocalDate.of(2012, 2, 29), LocalDate.of(2026, 2, 28)))
		assertTrue(isAtLeast14(LocalDate.of(2012, 2, 29), LocalDate.of(2026, 3, 1)))
	}

	@Test
	fun `오늘은 서버 시간대가 아니라 한국 시간으로 센다`() {
		// UTC 11-18 15:30 = 한국 11-19 00:30. 서버가 UTC 로 돌아도 한국의 날짜를 쓴다.
		assertEquals(LocalDate.of(2026, 11, 19), koreaToday(Instant.parse("2026-11-18T15:30:00Z")))
		assertEquals(LocalDate.of(2026, 11, 18), koreaToday(Instant.parse("2026-11-18T14:59:59Z")))
	}
}
