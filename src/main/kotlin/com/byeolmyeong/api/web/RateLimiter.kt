package com.byeolmyeong.api.web

/**
 * 키(클라이언트 주소)별 요청 수를 고정 창으로 센다. 서버 메모리에만 두고, 창이 바뀌면 전부 비운다.
 * 서버가 여러 대가 되면 다시 본다(계획 "내 결정").
 */
class RateLimiter(private val limit: Int, private val windowMillis: Long, private val now: () -> Long) {
	private val counts = HashMap<String, Int>()
	private var window = Long.MIN_VALUE

	@Synchronized
	fun tryAcquire(key: String): Boolean {
		val current = now() / windowMillis
		if (current != window) {
			counts.clear()
			window = current
		}
		return counts.merge(key, 1, Int::plus)!! <= limit
	}
}
