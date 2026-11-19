package com.byeolmyeong.api.web

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.birth.Places
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val MAPO = 1841277

/** 배포 전 확인 4(결정 022 계획): 시각은 `HH:mm` 만, 오늘이면 아직 오지 않은 시각은 거절한다. 시각 모름은 그대로 받는다. */
class BirthRequestParserTest {

	/** 지금은 2026-11-19 10:00(한국 시각). */
	private val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 19, 10, 0, 0, 0, KOREA_ZONE).toInstant(), KOREA_ZONE)
	private val parser = BirthRequestParser(Places(), clock)

	private fun code(date: String, time: String?) =
		assertFailsWith<BadRequestException> { parser.parse(BirthRequest(date, time, MAPO)) }.code

	@Test
	fun `초나 소수 초가 붙은 시각은 INVALID_TIME 이다`() {
		assertEquals("INVALID_TIME", code("1990-03-15", "12:30:00"))
		assertEquals("INVALID_TIME", code("1990-03-15", "12:30:00.5"))
		assertEquals("INVALID_TIME", code("1990-03-15", "9:05"))
	}

	@Test
	fun `24시는 다음 날 0시로 넘기지 않고 INVALID_TIME 이다`() {
		assertEquals("INVALID_TIME", code("1990-03-15", "24:00"))
	}

	@Test
	fun `오늘 이후 날짜는 주입한 시계의 오늘로 판정한다`() {
		// 시계가 2030-01-01 이면 2029-06-01 은 지난날이다(실제 오늘과 상관없이)
		val later = BirthRequestParser(Places(), Clock.fixed(ZonedDateTime.of(2030, 1, 1, 9, 0, 0, 0, KOREA_ZONE).toInstant(), KOREA_ZONE))
		assertEquals("2029-06-01", later.parse(BirthRequest("2029-06-01", null, MAPO)).date.toString())
		assertEquals("INVALID_DATE", assertFailsWith<BadRequestException> { later.parse(BirthRequest("2030-01-02", null, MAPO)) }.code)
	}

	@Test
	fun `HH 콜론 mm 은 받는다`() {
		assertEquals("12:30", parser.parse(BirthRequest("1990-03-15", "12:30", MAPO)).time.toString())
		assertEquals("09:05", parser.parse(BirthRequest("1990-03-15", "09:05", MAPO)).time.toString())
	}

	@Test
	fun `오늘 날짜에 아직 오지 않은 시각은 INVALID_TIME 이다`() {
		assertEquals("INVALID_TIME", code("2026-11-19", "11:00"))
		assertEquals("INVALID_TIME", code("2026-11-19", "10:01"))
	}

	@Test
	fun `오늘의 지난 시각과 어제의 늦은 시각은 받는다`() {
		parser.parse(BirthRequest("2026-11-19", "09:59", MAPO))
		parser.parse(BirthRequest("2026-11-19", "10:00", MAPO))
		parser.parse(BirthRequest("2026-11-18", "23:59", MAPO))
	}

	@Test
	fun `오늘 날짜라도 시각 모름은 받는다`() {
		assertEquals(null, parser.parse(BirthRequest("2026-11-19", null, MAPO)).time)
	}
}
