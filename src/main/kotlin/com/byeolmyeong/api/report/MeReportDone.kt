package com.byeolmyeong.api.report

import java.util.UUID

/** 나 탭 풀이가 완성됐다(Spring 이벤트, 결정 029 의 7). 알림함(계획 S⑤)이 듣는다. 글이나 출생 정보는 싣지 않는다. */
data class MeReportDone(val ownerId: UUID)
