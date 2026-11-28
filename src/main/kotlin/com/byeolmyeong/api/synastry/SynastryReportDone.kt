package com.byeolmyeong.api.synastry

import java.util.UUID

/** 궁합(요약 + 네 영역)이 완성됐다(Spring 이벤트, 결정 035 의 4 · 053). 알림함이 커밋 뒤에 듣는다. 글 · 상대의 정보는 싣지 않는다. */
data class SynastryReportDone(val ownerId: UUID, val synastryId: UUID)
