package com.byeolmyeong.api.admin

import java.util.UUID

/**
 * 관리자가 "알림 보내기"를 켠 공지를 공개해 알림을 보낼 차례가 됐다는 Spring 이벤트(결정 055 의 4). 한 공지에 한 번만 낸다(`notices.notified_at`).
 * 글은 싣지 않는다. 알림함(`notification` — 모든 계정에 `NOTICE { noticeId }`)과 푸시(`push` — 제목은 보낼 때 읽는다)가 커밋된 뒤에 듣는다.
 * 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
data class NoticePublished(val noticeId: UUID)
