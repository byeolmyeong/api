package com.byeolmyeong.api.report

import com.byeolmyeong.api.auth.CurrentOwner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** 시기 풀이(결정 029 · 033). 요청 본문이 없다 — 누구의 것인지는 세션에서, 출생 정보는 저장된 프로필에서 얻는다. */
@RestController
class TimingReportController(private val owner: CurrentOwner, private val reports: TimingReportService) {

	/** 글을 기다리지 않고 202 로 답한다. 상태는 `GET /timing-report` 로 본다. */
	@PostMapping("/timing-report")
	fun create(): ResponseEntity<TimingReportStatusResponse> =
		ResponseEntity.accepted().body(TimingReportStatusResponse(reports.request(owner.id())))

	@GetMapping("/timing-report")
	fun mine(): TimingReportStatusResponse = reports.current(owner.id())
}
