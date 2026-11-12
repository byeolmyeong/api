package com.byeolmyeong.api.share

import com.byeolmyeong.api.chart.Relation
import java.time.LocalDate
import java.util.UUID

// 공유 카드에 실을 글을, 그 글을 가진 기능에게 묻는 Spring 이벤트(결정 012). 같은 스레드에서 바로 처리된다.
// 듣는 쪽(`reading`·`synastry`·`report` 의 서비스)은 [ownerId] 의 것일 때만 답을 채운다. 답이 비어 있으면 공유할 것이 없는 것이다.
// 기능 패키지 사이의 연결이므로 JDK·Kotlin 표준·`chart` 의 타입만 쓴다. 근거·본문·출생 정보는 싣지 않는다.

/** 지금 차트의 요약 해석: 한 줄 총평과 키워드로 쓸 짧은 글. */
class ReadingCardRequested(val ownerId: UUID) {
	var headline: String? = null
	var keywords: List<String>? = null
}

/** 궁합 하나: 상대를 부를 이름·점수·60점 미만 표시·한 줄 요약. */
class ScoreCardRequested(val ownerId: UUID, val synastryId: UUID) {
	var partnerName: String? = null
	var score: Int? = null
	var scoreLabel: String? = null
	var headline: String? = null
}

/** 그 관계 유형의 랭킹: (부를 이름, 점수)를 순위대로. */
class RankingCardRequested(val ownerId: UUID, val relation: Relation) {
	var entries: List<Pair<String, Int>>? = null
}

/** 지금 차트의 완성된 리포트에서 오늘 뒤의 첫 마디: 그 날과 주제 한 줄. */
class NextTurnCardRequested(val ownerId: UUID) {
	var date: LocalDate? = null
	var theme: String? = null
}
