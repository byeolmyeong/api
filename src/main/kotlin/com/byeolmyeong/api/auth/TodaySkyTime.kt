package com.byeolmyeong.api.auth

import java.time.LocalTime

private val HH_MM = Regex("([01][0-9]|2[0-3]):([0-5][0-9])")

/** 오늘의 하늘 알림을 받는 한국 시각(결정 042 의 3): `HH:mm`, 5분 단위. 아니면 null. */
fun parseTodaySkyTime(raw: String): LocalTime? {
	val match = HH_MM.matchEntire(raw) ?: return null
	val (hour, minute) = match.destructured
	return LocalTime.of(hour.toInt(), minute.toInt()).takeIf { it.minute % 5 == 0 }
}
