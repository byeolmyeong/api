package com.byeolmyeong.api.report

import com.byeolmyeong.api.auth.CurrentOwner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 심층 리포트. 요청 본문이 없다 — 누구의 것인지는 세션에서, 출생 정보는 저장된 프로필에서 얻는다. */
@RestController
class ReportController(private val owner: CurrentOwner, private val reports: ReportService) {

	/** 글을 기다리지 않고 202 로 답한다. 상태는 `GET /reports/me` 로 본다. 새로 시작할 때 자세한 풀이와 같은 판정(결정 056 의 12). */
	@PostMapping("/reports")
	fun create(@RequestHeader("X-App-Version", required = false) appVersion: String?): ResponseEntity<ReportStatusResponse> =
		ResponseEntity.accepted().body(ReportStatusResponse(reports.request(owner.id(), appVersion)))

	@GetMapping("/reports/me")
	fun mine(): ReportStatusResponse = reports.current(owner.id())
}
