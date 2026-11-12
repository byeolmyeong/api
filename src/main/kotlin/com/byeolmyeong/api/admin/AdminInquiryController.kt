package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.BadRequestException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

private val STATUSES = setOf("NEW", "IN_PROGRESS", "DONE")
private const val MEMO_MAX = 2000
private const val SIZE_DEFAULT = 20
private const val SIZE_MAX = 100

/** 문의 관리(결정 014): 목록·한 건·상태와 메모 바꾸기. 문의 글 자체는 고치지도 지우지도 않는다. */
@RestController
class AdminInquiryController(private val inquiries: AdminInquiryService, private val current: CurrentAdmin) {

	/** 새것부터. 빈 글인 조건은 없는 것으로 본다. */
	@GetMapping("/admin/inquiries")
	fun list(
		@RequestParam(required = false) status: String?,
		@RequestParam(required = false) category: String?,
		@RequestParam(required = false) page: String?,
		@RequestParam(required = false) size: String?,
	): AdminInquiryPage {
		val statusFilter = status?.ifEmpty { null }
		if (statusFilter != null && statusFilter !in STATUSES) throw BadRequestException("INVALID_STATUS")
		val pageNumber = if (page.isNullOrEmpty()) 0 else page.toIntOrNull()
		val pageSize = if (size.isNullOrEmpty()) SIZE_DEFAULT else size.toIntOrNull()
		if (pageNumber == null || pageNumber < 0 || pageSize == null || pageSize !in 1..SIZE_MAX) throw BadRequestException("INVALID_PAGE")
		return inquiries.list(statusFilter, category?.ifEmpty { null }, pageNumber, pageSize)
	}

	@GetMapping("/admin/inquiries/{id}")
	fun get(@PathVariable id: String): AdminInquiry = inquiries.get(pathId(id))

	/** `status`·`memo` 가운데 보낸 것만 바꾼다. `memo` 가 `null` 이거나 빈 글이면 메모를 지운다. 누가 바꿨는지는 세션에서 얻는다. */
	@PatchMapping("/admin/inquiries/{id}")
	fun update(@PathVariable id: String, @RequestBody body: JsonNode): AdminInquiry {
		val inquiryId = pathId(id)
		body.requireObject()
		val hasStatus = body.has("status")
		val hasMemo = body.has("memo")
		if (!hasStatus && !hasMemo) throw BadRequestException("INVALID_REQUEST")
		val status = body.textOrNull("status")
		val memo = body.textOrNull("memo")?.trim()?.ifEmpty { null }
		if (hasStatus && status !in STATUSES) throw BadRequestException("INVALID_STATUS")
		if (memo != null && (memo.codePointLength() > MEMO_MAX || NUL in memo)) throw BadRequestException("INVALID_MEMO")
		return inquiries.update(inquiryId, current.get().id, status, hasMemo, memo)
	}
}
