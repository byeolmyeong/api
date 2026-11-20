package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.BadRequestException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

private val STATUSES = setOf("NEW", "IN_PROGRESS", "ANSWERED", "DONE")

/** `status` 로 고를 수 있는 상태. ANSWERED 는 답을 저장해야 된다(결정 039 의 4). */
private val SETTABLE_STATUSES = STATUSES - "ANSWERED"
private const val MEMO_MAX = 2000
private const val ANSWER_MAX = 2000
private const val SIZE_DEFAULT = 20
private const val SIZE_MAX = 100

/** 문의 관리(결정 014 · 039): 목록 · 찾기 · 한 건 · 상태와 메모 · 답 · 임시 저장. 문의 글 자체는 고치지도 지우지도 않는다. */
@RestController
class AdminInquiryController(private val inquiries: AdminInquiryService, private val current: CurrentAdmin) {

	/** 새것부터. 빈 글인 조건은 없는 것으로 본다. [q] 는 글이나 작성자 닉네임의 일부(대소문자 무시). */
	@GetMapping("/admin/inquiries")
	fun list(
		@RequestParam(required = false) status: String?,
		@RequestParam(required = false) category: String?,
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false) page: String?,
		@RequestParam(required = false) size: String?,
	): AdminInquiryPage {
		val statusFilter = status?.ifEmpty { null }
		if (statusFilter != null && statusFilter !in STATUSES) throw BadRequestException("INVALID_STATUS")
		val pageNumber = if (page.isNullOrEmpty()) 0 else page.toIntOrNull()
		val pageSize = if (size.isNullOrEmpty()) SIZE_DEFAULT else size.toIntOrNull()
		if (pageNumber == null || pageNumber < 0 || pageSize == null || pageSize !in 1..SIZE_MAX) throw BadRequestException("INVALID_PAGE")
		val query = q?.trim()?.ifEmpty { null }
		// 저장된 글에는 NUL 이 없으므로 맞는 것이 없다(DB 는 NUL 을 받지 못한다).
		if (query != null && NUL in query) return AdminInquiryPage(emptyList(), pageNumber, pageSize, 0)
		return inquiries.list(statusFilter, category?.ifEmpty { null }, query, pageNumber, pageSize)
	}

	@GetMapping("/admin/inquiries/{id}")
	fun get(@PathVariable id: String): AdminInquiry = inquiries.get(pathId(id))

	/**
	 * `status` · `memo` · `answer` · `answerDraft` 가운데 보낸 것만 바꾼다. `memo` · `answerDraft` 가 `null` 이거나 빈 글이면 지운다.
	 * 답은 상태를 ANSWERED 로 정하고 임시 저장을 비우므로 `status` · `answerDraft` 와 함께 올 수 없다(결정 039 의 4). 누가 바꿨는지는 세션에서 얻는다.
	 */
	@PatchMapping("/admin/inquiries/{id}")
	fun update(@PathVariable id: String, @RequestBody body: JsonNode): AdminInquiry {
		val inquiryId = pathId(id)
		body.requireObject()
		val hasStatus = body.has("status")
		val hasMemo = body.has("memo")
		val hasAnswer = body.has("answer")
		val hasDraft = body.has("answerDraft")
		if (!hasStatus && !hasMemo && !hasAnswer && !hasDraft) throw BadRequestException("INVALID_REQUEST")
		if (hasAnswer && (hasStatus || hasDraft)) throw BadRequestException("INVALID_REQUEST")
		val status = body.textOrNull("status")
		val memo = body.textOrNull("memo")?.trim()?.ifEmpty { null }
		val answer = body.textOrNull("answer")?.trim()?.ifEmpty { null }
		val draft = body.textOrNull("answerDraft")?.trim()?.ifEmpty { null }
		if (hasStatus && status !in SETTABLE_STATUSES) throw BadRequestException("INVALID_STATUS")
		if (memo != null && (memo.codePointLength() > MEMO_MAX || NUL in memo)) throw BadRequestException("INVALID_MEMO")
		// 답은 지울 수 없다(사용자가 이미 받았다) — 빈 답은 틀린 값이다.
		if (hasAnswer && (answer == null || answer.codePointLength() > ANSWER_MAX || NUL in answer)) throw BadRequestException("INVALID_ANSWER")
		if (draft != null && (draft.codePointLength() > ANSWER_MAX || NUL in draft)) throw BadRequestException("INVALID_ANSWER")
		return inquiries.update(inquiryId, current.get().id, InquiryChange(status, hasMemo, memo, answer, hasDraft, draft))
	}
}
