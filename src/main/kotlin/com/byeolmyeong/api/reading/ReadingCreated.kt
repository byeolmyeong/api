package com.byeolmyeong.api.reading

import java.util.UUID

/** 그 차트의 요약 해석(별명)을 처음 저장했다(Spring 이벤트, 결정 035 의 4). 알림함 · 푸시가 커밋 뒤에 듣는다. 글 · 출생 정보는 싣지 않는다. */
data class ReadingCreated(val ownerId: UUID)
