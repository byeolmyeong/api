package com.byeolmyeong.api.auth

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 결정 022: 생성 기능은 한 계정이 합쳐서 분당·시간당 몇 번까지. 소유자는 세션 검사가 요청에 넣어 둔 값이다. */
class GenerationRateLimitInterceptorTest {

	private var clock = 0L
	private val interceptor = GenerationRateLimitInterceptor(perMinute = 5, perHour = 30) { clock }
	private val alice = UUID.randomUUID()
	private val bob = UUID.randomUUID()

	private fun call(owner: UUID?, method: String = "POST"): Pair<Boolean, MockHttpServletResponse> {
		val request = MockHttpServletRequest(method, "/readings")
		if (owner != null) markOwner(request, owner)
		val response = MockHttpServletResponse()
		return interceptor.preHandle(request, response, Any()) to response
	}

	@Test
	fun `같은 계정의 여섯 번째 생성 요청은 429 다`() {
		repeat(5) { assertTrue(call(alice).first) }
		val (passed, response) = call(alice)
		assertFalse(passed)
		assertEquals(429, response.status)
		assertEquals("""{"error":"RATE_LIMITED"}""", response.contentAsString)
	}

	@Test
	fun `다른 계정은 따로 센다`() {
		repeat(5) { call(alice) }
		assertFalse(call(alice).first)
		assertTrue(call(bob).first)
	}

	@Test
	fun `GET 과 CORS 사전 요청은 세지 않는다`() {
		repeat(20) { assertTrue(call(alice, "GET").first) }
		repeat(20) { assertTrue(call(alice, "OPTIONS").first) }
		assertTrue(call(alice).first)
	}

	@Test
	fun `분이 바뀌면 다시 받지만 한 시간에 서른 번을 넘으면 429 다`() {
		var accepted = 0
		for (minute in 0 until 10) {
			clock = minute * 60_000L
			repeat(5) { if (call(alice).first) accepted++ }
		}
		assertEquals(30, accepted)
		clock = 60L * 60_000L
		assertTrue(call(alice).first)
	}

	@Test
	fun `소유자가 없으면 세지 않고 넘긴다 — 세션 검사가 먼저 401 로 막는다`() {
		repeat(10) { assertTrue(call(null).first) }
	}
}
