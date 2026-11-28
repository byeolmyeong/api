package com.byeolmyeong.api.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 결제 시작 시각(결정 056 의 1): 비었거나 아직이면 시작 전, 그 시각부터 시작. 읽을 수 없는 값이거나 시각이 있는데 RevenueCat 비밀 키가 없으면 서버가 시작하지 않는다. */
class PurchaseStartTest {

	private val KEY = "revenuecat-secret-for-test"

	private fun at(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

	@Test
	fun `비어 있으면 시작 전이다`() {
		assertFalse(PurchaseStart("", "", at("2030-01-01T00:00:00Z")).started())
		assertFalse(PurchaseStart("  ", "", at("2030-01-01T00:00:00Z")).started())
	}

	@Test
	fun `오프셋이 붙은 시각의 그 순간부터 시작이다`() {
		val raw = "2026-12-10T00:00:00+09:00"
		assertFalse(PurchaseStart(raw, KEY, at("2026-12-09T14:59:59Z")).started())
		assertTrue(PurchaseStart(raw, KEY, at("2026-12-09T15:00:00Z")).started())
		assertTrue(PurchaseStart(raw, KEY, at("2027-01-01T00:00:00Z")).started())
	}

	@Test
	fun `읽을 수 없는 값이면 만들지 못한다`() {
		for (raw in listOf("2026-12-10", "2026-12-10T00:00:00", "내일", "2026-13-01T00:00:00+09:00")) {
			assertThrows<IllegalStateException>(raw) { PurchaseStart(raw, KEY, at("2026-12-01T00:00:00Z")) }
		}
	}

	@Test
	fun `시작 시각이 있는데 RevenueCat 비밀 키가 비어 있으면 만들지 못하고 시작 시각이 없으면 키가 없어도 된다`() {
		for (key in listOf("", "   ")) {
			assertThrows<IllegalStateException>("[$key]") { PurchaseStart("2026-12-10T00:00:00+09:00", key, at("2026-12-01T00:00:00Z")) }
		}
		assertFalse(PurchaseStart("", "", at("2026-12-01T00:00:00Z")).started())
	}
}
