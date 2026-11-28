package com.byeolmyeong.api.report

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 결제 시작 시각(결정 056 의 1, 설정 `byeolmyeong.purchase.starts-at` — 오프셋이 붙은 ISO-8601). 비어 있거나 아직이면 1.0.0 처럼 모두 받는다.
 * 읽을 수 없는 값이면 이 빈을 만들 때 예외를 던져 서버가 시작하지 않는다 — 잘못 넣은 값이 조용히 "시작 전" 으로 읽히지 않게.
 */
@Component
class PurchaseStart(@Value("\${byeolmyeong.purchase.starts-at:}") raw: String, private val clock: Clock) {

	/** 결제 시작 시각. 비어 있으면 null. */
	val at: Instant? = raw.trim().takeIf { it.isNotEmpty() }?.let {
		try {
			OffsetDateTime.parse(it).toInstant()
		} catch (_: DateTimeParseException) {
			LoggerFactory.getLogger(PurchaseStart::class.java).error("event=startup_refused reason=purchase_starts_at_unreadable")
			throw IllegalStateException("결제 시작 시각(byeolmyeong.purchase.starts-at)을 읽을 수 없다 — 예 2026-12-10T00:00:00+09:00(결정 056)")
		}
	}

	/** 지금이 결제 시작 시각이거나 그 뒤면 true. */
	fun started(): Boolean = at != null && !clock.instant().isBefore(at)
}
