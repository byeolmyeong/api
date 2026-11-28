package com.byeolmyeong.api.synastry

import java.util.UUID

/**
 * 궁합(요약 + 네 영역)이 완성됐는데 정해진 시간(30초) 안에 결과를 받아 가지 않았다 — "○○ 님과의 궁합이 나왔어요" 를 알릴 때다
 * (Spring 이벤트, 결정 035 의 4 · 053 의 8). 알림함과 푸시가 듣는다. 글 · 상대의 정보는 싣지 않는다(푸시는 부를 이름을 자기 SQL 로 읽는다).
 */
data class SynastryReportDone(val ownerId: UUID, val synastryId: UUID)
