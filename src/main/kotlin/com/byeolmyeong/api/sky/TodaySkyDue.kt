package com.byeolmyeong.api.sky

import java.time.LocalDate
import java.util.UUID

/**
 * 오늘의 하늘 알림을 보낼 때가 됐다는 Spring 이벤트(결정 042 의 6). `sky` 의 아침 일정이 그날 한 번만(먼저 적은 서버만) 내고,
 * `push` 가 같은 스레드에서 듣고 보낸다. [title] 은 그날의 하늘 제목(푸시 본문). 출생 정보는 싣지 않는다.
 */
data class TodaySkyDue(val ownerId: UUID, val date: LocalDate, val title: String)
