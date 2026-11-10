package com.byeolmyeong.api.birth

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// 기대값의 출처: IANA 시간대 데이터베이스의 Asia/Seoul (`zdump -v Asia/Seoul` 출력, 2026-11-10 확인).
//   1954-03-21 ~ 1961-08-09  표준시 UTC+8:30 (gmtoff=30600)
//   1955-05-05 ~ 1955-09-08  서머타임 UTC+9:30 (gmtoff=34200). 1956~1960년 여름도 같다
//   1961-08-10 ~             표준시 UTC+9 (gmtoff=32400)
//   1987-05-10 ~ 1987-10-11, 1988-05-08 ~ 1988-10-09  서머타임 UTC+10 (gmtoff=36000)
class BirthTimeTest {

	private val today = LocalDate.of(2026, 11, 10)
	private val noon = LocalTime.NOON

	private fun utc(date: String, time: LocalTime? = noon) = toUtc(LocalDate.parse(date), time, today = today)

	@Test
	fun `정오가 그때의 표준시와 서머타임으로 바뀐다`() {
		assertEquals(Instant.parse("1955-06-01T02:30:00Z"), utc("1955-06-01")) // UTC+8:30 + 서머타임 = +9:30
		assertEquals(Instant.parse("1960-01-15T03:30:00Z"), utc("1960-01-15")) // UTC+8:30
		assertEquals(Instant.parse("1987-07-01T02:00:00Z"), utc("1987-07-01")) // UTC+9 + 서머타임 = +10
		assertEquals(Instant.parse("1988-12-01T03:00:00Z"), utc("1988-12-01")) // UTC+9
		assertEquals(Instant.parse("2000-01-01T03:00:00Z"), utc("2000-01-01")) // UTC+9
	}

	@Test
	fun `시각이 없으면 정오로 본다`() {
		assertEquals(utc("1990-03-15", noon), utc("1990-03-15", null))
	}

	@Test
	fun `자정 직후 출생은 UTC 로 전날이 된다`() {
		// 1955-01-01 은 UTC+8:30 → 00:10 은 전날 15:40 UTC
		assertEquals(Instant.parse("1954-12-31T15:40:00Z"), utc("1955-01-01", LocalTime.of(0, 10)))
	}

	@Test
	fun `1900년 이전과 오늘 이후 날짜는 거절한다`() {
		assertFailsWith<InvalidBirthDateException> { utc("1899-12-31") }
		assertFailsWith<InvalidBirthDateException> { utc("2026-11-11") }
		assertEquals(Instant.parse("2026-11-10T03:00:00Z"), utc("2026-11-10")) // 오늘은 받는다
		utc("1900-01-01") // 첫날은 받는다
	}
}
