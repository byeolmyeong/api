package com.byeolmyeong.api.report

import com.byeolmyeong.api.share.MeAreaCardRequested
import com.byeolmyeong.api.share.NextBigCardRequested
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 공유 v2(결정 036) 카드에 실을 글을 자세한 풀이에서 답한다 — 지금 차트의 완성된(DONE) 풀이일 때만. 근거 · 본문은 주지 않는다.
 */
@Component
class DetailReportCards(private val me: MeReportService, private val timing: TimingReportService) {

	/** 나 탭 영역: 모든 영역에서 그 영역의 한 줄 요약(영역 제목, 결정 036 덧붙임 2026-11-23). */
	@EventListener
	fun on(event: MeAreaCardRequested) {
		val current = me.current(event.ownerId).takeIf { it.status == ReportStatus.DONE } ?: return
		val area = current.report?.areas?.firstOrNull { it.area.name == event.area } ?: return
		event.title = area.title
	}

	/** 다음 큰 변화: 오늘 뒤에 처음 오는 마디의 해 · 만 나이 · 제목. 없으면(모두 지난 마디) 답하지 않는다. */
	@EventListener
	fun on(event: NextBigCardRequested) {
		val current = timing.current(event.ownerId).takeIf { it.status == ReportStatus.DONE } ?: return
		val next = current.report?.current?.nextMilestone ?: return
		event.year = next.year
		event.age = next.age
		event.title = next.title
	}
}
