package com.byeolmyeong.api.auth

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

private val KOREA: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 만 14세 이상인가(결정 021). [today] 기준 만 나이. 2월 29일생은 평년에 3월 1일에 나이를 먹는다(`Period` 의 셈).
 * 판정은 서버에서만 한다(golden rules).
 */
fun isAtLeast14(birthDate: LocalDate, today: LocalDate): Boolean = Period.between(birthDate, today).years >= 14

/** 한국 시간의 오늘. 서버가 어느 시간대로 돌든 나이는 한국 날짜로 센다. */
fun koreaToday(now: Instant = Instant.now()): LocalDate = now.atZone(KOREA).toLocalDate()
