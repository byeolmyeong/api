package com.byeolmyeong.api.report

import java.util.UUID

/** 리포트가 완성됐다(Spring 이벤트). 다른 기능(알림)이 듣는다. 글이나 출생 정보는 싣지 않는다. */
data class ReportDone(val ownerId: UUID)
