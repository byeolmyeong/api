package com.byeolmyeong.api.sky

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.computeIntervals
import java.time.Instant

/** 기억하는 차트 수의 상한. 넘으면 가장 오래 안 쓴 것부터 잊는다. */
private const val MAX_ENTRIES = 500

/** 한 차트의 구간을 기억하는 시간(10분). 시기 탭을 열면 석 달을 잇달아 부르므로 그동안만 버틴다. */
private const val TTL_MILLIS = 600_000L

/**
 * 이번 달 내내를 고르는 데 쓰는 평생 시기 구간을 차트 해시마다 잠깐 기억한다(S③ 리뷰 — 계산이 요청당 약 0.26초 CPU라
 * 시기 탭이 석 달을 부르면 약 0.8초가 든다). 구간은 차트만으로 정해지므로 같은 해시면 같은 값이다. 서버 메모리에만 둔다(크기 · 시간 제한).
 * 결정 032 의 "요청마다 계산한다" 에서 이 한 가지만 다르다 — 응답은 같다.
 */
class IntervalCache(
	private val maxEntries: Int = MAX_ENTRIES,
	private val ttlMillis: Long = TTL_MILLIS,
	private val now: () -> Long = System::currentTimeMillis,
	private val compute: (Chart, Instant) -> List<Interval> = ::computeIntervals,
) {
	private class Entry(val intervals: List<Interval>, val at: Long)

	// 쓴 순서(access order)로 두어 넘치면 가장 오래 안 쓴 것을 지운다.
	private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > maxEntries
	}

	/** [chartHash] 의 평생 구간. 기억한 지 [ttlMillis] 안이면 계산하지 않는다. [chart] 는 [birthUtc] 의 차트여야 한다. */
	fun intervals(chartHash: String, chart: Chart, birthUtc: Instant): List<Interval> {
		synchronized(entries) {
			entries[chartHash]?.takeIf { now() - it.at < ttlMillis }?.let { return it.intervals }
		}
		// 계산은 잠금 밖에서(다른 차트의 요청을 막지 않게). 같은 차트가 동시에 오면 두 번 계산할 수 있지만 값은 같다.
		val computed = compute(chart, birthUtc)
		synchronized(entries) { entries[chartHash] = Entry(computed, now()) }
		return computed
	}
}
