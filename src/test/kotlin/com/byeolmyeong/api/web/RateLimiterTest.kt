package com.byeolmyeong.api.web

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

	@Test
	fun `한 주소가 창 안에서 한도를 넘으면 막고 창이 바뀌면 다시 받는다`() {
		var now = 0L
		val limiter = RateLimiter(limit = 2, windowMillis = 60_000, now = { now })
		assertTrue(limiter.tryAcquire("a"))
		assertTrue(limiter.tryAcquire("a"))
		assertFalse(limiter.tryAcquire("a"))
		assertTrue(limiter.tryAcquire("b")) // 다른 주소는 따로 센다
		now = 59_999
		assertFalse(limiter.tryAcquire("a"))
		now = 60_000
		assertTrue(limiter.tryAcquire("a"))
	}
}
