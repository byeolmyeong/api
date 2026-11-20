package com.byeolmyeong.api.sky

import java.time.LocalDate
import java.util.UUID

/**
 * 오늘의 하늘 알림을 보낼 때가 됐다는 Spring 이벤트(결정 042 의 6). `sky` 의 아침 일정이 한 번 돌 때 그때 보낼 사람을 모아 한 번 내고,
 * `push` 가 같은 스레드에서 듣고 묶어 보낸다(100건씩). 각 사람은 그날 한 번만(먼저 적은 서버만) 들어온다. 출생 정보는 싣지 않는다.
 */
data class TodaySkyDue(val items: List<Item>) {
	/** [title] 은 그날의 하늘 제목(푸시 본문). */
	data class Item(val ownerId: UUID, val date: LocalDate, val title: String)
}
