package com.byeolmyeong.api.birth

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val KOREA_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

private val EARLIEST_BIRTH_DATE: LocalDate = LocalDate.of(1900, 1, 1)

class InvalidBirthDateException(message: String) : IllegalArgumentException(message)

/**
 * 출생지 현지 날짜·시각을 UTC 로 바꾼다. 그때의 표준시와 서머타임은 `java.time` 의 시간대 이력을 따른다
 * (고정 +9시간이 아니다). 시각이 없으면 정오로 본다. 1900-01-01 이전과 [today] 이후 날짜는 거절한다.
 * 서머타임이 시작돼 건너뛴 시각(없는 시각)은 `java.time` 규칙대로 건너뛴 만큼 뒤로 밀고,
 * 끝나며 두 번 온 시각은 앞의 것(서머타임 쪽)으로 본다.
 */
fun toUtc(
	date: LocalDate,
	time: LocalTime?,
	zone: ZoneId = KOREA_ZONE,
	today: LocalDate = LocalDate.now(zone),
): Instant {
	if (date.isBefore(EARLIEST_BIRTH_DATE)) throw InvalidBirthDateException("1900-01-01 이전 날짜는 받지 않는다")
	if (date.isAfter(today)) throw InvalidBirthDateException("오늘 이후 날짜는 받지 않는다")
	return date.atTime(time ?: LocalTime.NOON).atZone(zone).toInstant()
}
