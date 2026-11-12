package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.web.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

data class InquiryCreated(val id: UUID)

/** 문의하기(결정 013). 누구의 문의인지는 세션에서 얻는다. 읽는 경로는 없다. */
@RestController
class InquiryController(private val owner: CurrentOwner, private val inquiries: InquiryService) {

	@PostMapping("/inquiries")
	fun create(@RequestBody request: JsonNode): ResponseEntity<InquiryCreated> =
		ResponseEntity.status(201).body(InquiryCreated(inquiries.create(owner.id(), parseInquiry(request))))

	@ExceptionHandler(InquiryRateLimitedException::class)
	fun rateLimited(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(429).body(ErrorResponse("RATE_LIMITED"))
}
