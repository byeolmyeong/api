package com.byeolmyeong.api.admin

import java.util.UUID

/**
 * `push` 가 공지 알림을 다 보냈다는 Spring 이벤트(결정 055 의 4 — 결과를 적는다). [devices] 는 대상 기기 수, [sent] 는 Expo 가 받은 수,
 * [failed] 는 실패(오류 · 지운 기기 — 보내다 오류가 나면 대상 기기 모두), [skipped] 는 보내지 않은 수(발송 꺼짐 · 접근 토큰 없음 · 그 사이 공지가 내려감).
 * `admin` 이 듣고 그 공지 행에 적는다. 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
data class NoticePushSent(val noticeId: UUID, val devices: Int, val sent: Int, val failed: Int, val skipped: Int)

/**
 * `notification` 이 공지 알림을 알림함에 넣었다(또는 넣지 못했다)는 Spring 이벤트(결정 055 의 4). [added] 는 넣은 줄 수, [failed] 면 넣기가 실패했다([added] 0).
 * `admin` 이 듣고 그 공지 행에 적는다. 기능 사이에 허용된 이벤트 목록에 있다.
 */
data class NoticeInboxAdded(val noticeId: UUID, val added: Int, val failed: Boolean)
