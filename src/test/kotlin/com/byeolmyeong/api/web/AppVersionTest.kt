package com.byeolmyeong.api.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 버전 규칙(결정 016): 점으로 나눈 1~3개의 0 이상 정수, 빠진 자리는 0, 숫자로 견준다. */
class AppVersionTest {

	@Test
	fun `읽을 수 있는 값`() {
		val cases = mapOf(
			"1" to listOf(1, 0, 0),
			"1.2" to listOf(1, 2, 0),
			"1.2.3" to listOf(1, 2, 3),
			"0" to listOf(0, 0, 0),
			"0.0.0" to listOf(0, 0, 0),
			"10.20.30" to listOf(10, 20, 30),
			"01.002.0003" to listOf(1, 2, 3),
			"999999999.999999999.999999999" to listOf(999999999, 999999999, 999999999),
		)
		for ((raw, expected) in cases) {
			val version = assertNotNull(parseAppVersion(raw), raw)
			assertEquals(expected, listOf(version.major, version.minor, version.patch), raw)
		}
	}

	@Test
	fun `읽을 수 없는 값은 null 이다`() {
		val cases = listOf(
			"", " ", "1.2.3.4", "1.", ".1", "1..2", "1.2.", "v1.2", "1.2-beta", "1.2.3+4", "1,2", "1.2 ", " 1.2", "1. 2",
			"-1", "1.-2", "+1", "1e3", "0x10", "1.2.x", "latest", "１.２", "1234567890", "1.1234567890", "1\n", "1.2\u0000",
		)
		for (raw in cases) assertNull(parseAppVersion(raw), "'$raw'")
	}

	@Test
	fun `숫자로 견준다`() {
		// (낮은 것, 높은 것)
		val ordered = listOf(
			"1.9.9" to "1.10.0",
			"1.2" to "1.2.1",
			"1" to "1.0.1",
			"0.9.9" to "1",
			"1.2.3" to "1.2.4",
			"1.2.9" to "1.3",
			"9.9.9" to "10",
			"2.0.0" to "10.0.0",
			"1.09" to "1.10",
		)
		for ((low, high) in ordered) {
			assertTrue(parseAppVersion(low)!! < parseAppVersion(high)!!, "$low < $high")
			assertTrue(parseAppVersion(high)!! > parseAppVersion(low)!!, "$high > $low")
		}
	}

	@Test
	fun `빠진 자리는 0 이라 같은 버전이다`() {
		for ((a, b) in listOf("1" to "1.0.0", "1.2" to "1.2.0", "1.0" to "1", "01.2" to "1.02.0")) {
			assertEquals(0, parseAppVersion(a)!!.compareTo(parseAppVersion(b)!!), "$a = $b")
			assertEquals(parseAppVersion(a), parseAppVersion(b))
		}
	}
}
