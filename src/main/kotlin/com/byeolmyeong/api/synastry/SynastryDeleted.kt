package com.byeolmyeong.api.synastry

import java.util.UUID

/**
 * 소유자가 궁합 한 건을 지웠다는 Spring 이벤트(결정 019). 그 궁합에 붙은 글을 가진 기능(`direction`)이 듣고 자기 행을 지운다.
 * 궁합을 지우는 트랜잭션 안에서 낸다 — 듣는 쪽이 실패하면 궁합도 지워지지 않는다.
 */
class SynastryDeleted(val ownerId: UUID, val synastryId: UUID)
