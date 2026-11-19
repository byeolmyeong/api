package com.byeolmyeong.api.web

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 결정 022·023: 생성은 한 계정이 합쳐서 분당·시간당 몇 번까지. 서비스가 모델을 부르기 직전에 센다. */
class GenerationQuotaTest {

	private var clock = 0L
	private val quota = GenerationQuota(perMinute = 5, perHour = 30) { clock }
	private val alice = UUID.randomUUID()
	private val bob = UUID.randomUUID()

	@Test
	fun `같은 계정의 여섯 번째 생성은 한도를 넘는다`() {
		repeat(5) { quota.acquire(alice) }
		assertFailsWith<GenerationRateLimitedException> { quota.acquire(alice) }
	}

	@Test
	fun `다른 계정은 따로 센다`() {
		repeat(5) { quota.acquire(alice) }
		assertFailsWith<GenerationRateLimitedException> { quota.acquire(alice) }
		quota.acquire(bob)
	}

	@Test
	fun `분이 바뀌면 다시 받지만 한 시간에 서른 번을 넘으면 넘는다`() {
		var accepted = 0
		for (minute in 0 until 10) {
			clock = minute * 60_000L
			repeat(5) { runCatching { quota.acquire(alice) }.onSuccess { accepted++ } }
		}
		assertEquals(30, accepted)
		clock = 60L * 60_000L
		quota.acquire(alice)
	}
}
