package com.byeolmyeong.api.admin

import java.util.UUID

/**
 * 관리자가 실패한 자세한 풀이를 다시 만들게 했다는 Spring 이벤트(결정 043 의 1). [kind] 는 `ME` · `TIMING`.
 * `report` 가 요청 스레드에서 듣고 그 사람의 지금 차트로 다시 시작한다. 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
data class GenerationRetryRequested(val ownerId: UUID, val kind: String)
