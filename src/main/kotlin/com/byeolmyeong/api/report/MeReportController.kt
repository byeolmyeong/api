package com.byeolmyeong.api.report

import com.byeolmyeong.api.auth.CurrentOwner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 나 탭 풀이(결정 029 · 030). 요청 본문이 없다 — 누구의 것인지는 세션에서, 출생 정보는 저장된 프로필에서 얻는다. */
@RestController
class MeReportController(private val owner: CurrentOwner, private val reports: MeReportService) {

	/** 글을 기다리지 않고 202 로 답한다. 상태는 `GET /me-report` 로 본다. 앱의 버전 머리는 옛 앱의 새 받기를 426 으로 막는 데 쓴다(결정 056 의 5). */
	@PostMapping("/me-report")
	fun create(@RequestHeader("X-App-Version", required = false) appVersion: String?): ResponseEntity<MeReportStatusResponse> =
		ResponseEntity.accepted().body(MeReportStatusResponse(reports.request(owner.id(), appVersion)))

	@GetMapping("/me-report")
	fun mine(): MeReportStatusResponse = reports.current(owner.id())
}
