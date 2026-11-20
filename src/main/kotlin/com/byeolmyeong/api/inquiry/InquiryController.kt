package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.web.ErrorResponse
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

data class InquiryCreated(val id: UUID)

/** 문의하기와 내 문의(결정 013 · 039). 누구의 문의인지는 세션에서 얻는다. */
@RestController
class InquiryController(private val owner: CurrentOwner, private val inquiries: InquiryService) {

	/** 보낸 앱의 헤더(`X-App-Platform` · `X-App-Version`)를 함께 적어 둔다 — 관리자가 어느 앱에서 온 문의인지 본다. */
	@PostMapping("/inquiries")
	fun create(
		@RequestBody request: JsonNode,
		@RequestHeader("X-App-Platform", required = false) platform: String?,
		@RequestHeader("X-App-Version", required = false) version: String?,
	): ResponseEntity<InquiryCreated> =
		ResponseEntity.status(201).body(InquiryCreated(inquiries.create(owner.id(), parseInquiry(request), inquiryApp(platform, version))))

	/** 내 문의 전부, 새것부터. */
	@GetMapping("/inquiries")
	fun list(): List<MyInquiry> = inquiries.mine(owner.id())

	@GetMapping("/inquiries/{id}")
	fun get(@PathVariable id: String): MyInquiry = inquiries.mine(owner.id(), id(id))

	@ExceptionHandler(InquiryRateLimitedException::class)
	fun rateLimited(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(429).body(ErrorResponse("RATE_LIMITED"))

	// id 모양이 아닌 값도 "없는 것" 이다.
	private fun id(value: String): UUID = try {
		UUID.fromString(value)
	} catch (e: IllegalArgumentException) {
		throw NotFoundException("NOT_FOUND")
	}
}
