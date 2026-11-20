package com.byeolmyeong.api.sky

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.computeChart
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** 이번 달의 평생 시기 구간 캐시(S③ 리뷰): 차트 해시마다 잠깐 기억해 같은 차트는 요청마다 다시 계산하지 않는다. */
class IntervalCacheTest {

	private val utc = Instant.parse("1990-03-15T05:30:00Z")
	private val chart: Chart = computeChart(utc, 37.5665, 126.9780, timeKnown = true)
	private var now = 0L
	private val computed = mutableListOf<Instant>()

	private fun cache(maxEntries: Int = 3, ttlMillis: Long = 600_000) =
		IntervalCache(maxEntries, ttlMillis, now = { now }, compute = { _: Chart, birthUtc: Instant -> computed += birthUtc; emptyList<Interval>() })

	@Test
	fun `같은 차트는 정해진 시간 안에 다시 계산하지 않고 다른 차트는 따로 계산한다`() {
		val cache = cache()
		val first = cache.intervals("hash-a", chart, utc)
		now += 599_999
		assertSame(first, cache.intervals("hash-a", chart, utc), "같은 차트 · 10분 안 → 기억한 값")
		assertEquals(1, computed.size)

		cache.intervals("hash-b", chart, utc)
		assertEquals(2, computed.size, "다른 차트는 따로")

		now += 2
		cache.intervals("hash-a", chart, utc)
		assertEquals(3, computed.size, "시간이 지나면 다시 계산")
	}

	@Test
	fun `크기를 넘으면 가장 오래 안 쓴 차트부터 잊는다`() {
		val cache = cache(maxEntries = 2)
		cache.intervals("a", chart, utc)
		cache.intervals("b", chart, utc)
		cache.intervals("a", chart, utc) // a 를 최근에 썼다
		cache.intervals("c", chart, utc) // b 를 잊는다
		assertEquals(3, computed.size)

		cache.intervals("a", chart, utc)
		assertEquals(3, computed.size, "a 는 남아 있다")
		cache.intervals("b", chart, utc)
		assertEquals(4, computed.size, "b 는 잊었다")
	}

	@Test
	fun `기본 계산은 chart 의 평생 구간이다`() {
		val real = IntervalCache()
		assertEquals(com.byeolmyeong.api.chart.computeIntervals(chart, utc), real.intervals("hash", chart, utc))
	}
}
