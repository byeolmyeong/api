package com.byeolmyeong.api.share

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class ShareCardTest {

	@Test
	fun `한글 이름은 음절마다 첫소리 글자로 가린다`() {
		assertEquals("ㅈㅇ", initialsOf("지영"))
		assertEquals("ㄱㅈㅇ", initialsOf("김지영"))
		assertEquals("ㅆㄲㅎ", initialsOf("쌍꽃힘"))
		assertEquals("ㅇㅁ", initialsOf(" 엄 마 "))
	}

	@Test
	fun `이미 낱자인 한글은 그대로 두고 그 밖의 글자는 덩어리의 첫 글자만 대문자로 남긴다`() {
		assertEquals("ㅈㅇ", initialsOf("ㅈㅇ"))
		assertEquals("M", initialsOf("mike"))
		assertEquals("KM", initialsOf("Kim min"))
		assertEquals("KMS", initialsOf("kim min-su"))
		assertEquals("ㄱM", initialsOf("김 Min"))
		assertEquals("ㅈ2", initialsOf("지2"))
		assertEquals("ㅈ2ㅎ", initialsOf("지23호"))
	}

	@Test
	fun `가린 뒤 남는 글자가 없으면 동그라미 하나다`() {
		assertEquals("○", initialsOf("🌟"))
		assertEquals("○", initialsOf("!!"))
	}

	@Test
	fun `계절은 그 날의 달로 정하고 해는 달력 해다`() {
		assertEquals(Season.SPRING, seasonOf(LocalDate.of(2027, 3, 1)))
		assertEquals(Season.SPRING, seasonOf(LocalDate.of(2027, 5, 31)))
		assertEquals(Season.SUMMER, seasonOf(LocalDate.of(2027, 6, 1)))
		assertEquals(Season.SUMMER, seasonOf(LocalDate.of(2027, 8, 31)))
		assertEquals(Season.AUTUMN, seasonOf(LocalDate.of(2027, 9, 1)))
		assertEquals(Season.AUTUMN, seasonOf(LocalDate.of(2027, 11, 30)))
		assertEquals(Season.WINTER, seasonOf(LocalDate.of(2027, 12, 1)))
		assertEquals(Season.WINTER, seasonOf(LocalDate.of(2028, 1, 15)))
		assertEquals(Season.WINTER, seasonOf(LocalDate.of(2028, 2, 29)))
	}
}
