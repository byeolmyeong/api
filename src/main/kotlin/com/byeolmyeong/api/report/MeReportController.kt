package com.byeolmyeong.api.report

import com.byeolmyeong.api.auth.CurrentOwner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** 나 탭 풀이(결정 029 · 030). 요청 본문이 없다 — 누구의 것인지는 세션에서, 출생 정보는 저장된 프로필에서 얻는다. */
@RestController
class MeReportController(private val owner: CurrentOwner, private val reports: MeReportService) {

	/** 글을 기다리지 않고 202 로 답한다. 상태는 `GET /me-report` 로 본다. */
	@PostMapping("/me-report")
	fun create(): ResponseEntity<MeReportStatusResponse> =
		ResponseEntity.accepted().body(MeReportStatusResponse(reports.request(owner.id())))

	@GetMapping("/me-report")
	fun mine(): MeReportStatusResponse = reports.current(owner.id())
}
