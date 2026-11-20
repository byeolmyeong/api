package com.byeolmyeong.api.push

import java.util.UUID

/**
 * 광고 푸시(`admin.PushEventRequested`)를 다 보냈다는 Spring 이벤트(결정 043 의 6). `admin` 이 듣고 발송 내역의 대상 · 성공 · 실패 수를 채운다.
 * 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
data class PushEventSent(val eventId: UUID, val recipients: Int, val succeeded: Int, val failed: Int)
