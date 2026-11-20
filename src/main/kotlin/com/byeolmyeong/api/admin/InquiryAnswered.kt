package com.byeolmyeong.api.admin

import java.util.UUID

/**
 * 관리자가 문의에 처음 답했다는 Spring 이벤트(결정 039 의 5). 답을 고칠 때는 내지 않는다. 글은 싣지 않는다.
 * 알림함(계획 S⑤ — `INQUIRY_ANSWERED { inquiryId }`)과 푸시(S⑩)가 커밋된 뒤에 듣는다. 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
data class InquiryAnswered(val ownerId: UUID, val inquiryId: UUID)
