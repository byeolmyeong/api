package com.byeolmyeong.api.report

import com.byeolmyeong.api.admin.GenerationRetryRequested
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 관리자의 재생성(결정 043 의 1)을 사용자가 다시 누른 것과 같은 길로 시작한다 — 자리만 잡고 돌아오고 글은 뒤에서 쓴다.
 * 요청 스레드에서 듣는다: 생성 한도(결정 023)를 넘으면 `GenerationRateLimitedException` 이 관리자에게 429 `RATE_LIMITED` 로,
 * 출생 정보가 지워졌으면 404 `NO_PROFILE` 로 그대로 간다(관리자가 결과를 알 수 있게).
 */
@Component
class GenerationRetryListener(private val me: MeReportService, private val timing: TimingReportService) {

	@EventListener
	fun on(event: GenerationRetryRequested) {
		when (event.kind) {
			"ME" -> me.request(event.ownerId)
			"TIMING" -> timing.request(event.ownerId)
		}
	}
}
