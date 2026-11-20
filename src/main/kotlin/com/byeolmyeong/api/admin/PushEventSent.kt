package com.byeolmyeong.api.admin

import java.util.UUID

/**
 * 광고 푸시(`admin.PushEventRequested`)를 다 보냈다는 Spring 이벤트(결정 043 의 6). `push` 가 내고 `admin` 이 듣고 발송 내역의 대상 · 성공 · 실패 수를 채운다. 요청 이벤트와 함께 `admin` 에 둔다 — `push` 가 `admin` 의 두 이벤트만 알고 `admin` 은 `push` 를 몰라야 패키지 고리가 없다.
 * 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
data class PushEventSent(val eventId: UUID, val recipients: Int, val succeeded: Int, val failed: Int)
