package com.byeolmyeong.api.admin

import java.util.UUID

/**
 * 관리자가 광고 푸시를 보냈다는 Spring 이벤트(결정 043 의 6). [title] 앞의 "(광고) " 와 [body] 끝의 수신 거부 줄은 admin 이 붙여 둔다.
 * [target] 은 앱의 이동 화면 키(HOME · TODAY_SKY · ME · TIMING_MONTH · TIMING_YEAR · MY). `push`(S⑩)가 듣고 대상에게 보낸 뒤 `push.PushEventSent` 로 결과를 알린다.
 * 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
data class PushEventRequested(val eventId: UUID, val title: String, val body: String, val target: String)
