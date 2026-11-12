package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.BadRequestException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

private const val QUESTION_MAX = 200
private const val ANSWER_MAX = 2000

/** FAQ 편집(결정 014): 만들기·고치기·지우기·순서·공개. 앱은 공개한 것만 `GET /faqs`(`faq` 패키지)로 읽는다. */
@RestController
class AdminFaqController(private val faqs: AdminFaqService, private val current: CurrentAdmin) {

	@GetMapping("/admin/faqs")
	fun list(): List<AdminFaq> = faqs.list()

	/** 끝에 붙는다. `published` 를 보내지 않으면 공개하지 않은 채로 만든다. */
	@PostMapping("/admin/faqs")
	fun create(@RequestBody body: JsonNode): ResponseEntity<AdminFaq> {
		body.requireObject()
		val question = text(body, "question", QUESTION_MAX, "INVALID_QUESTION")
		val answer = text(body, "answer", ANSWER_MAX, "INVALID_ANSWER")
		val published = body.booleanOrNull("published") ?: false
		return ResponseEntity.status(201).body(faqs.create(current.get().id, question, answer, published))
	}

	/** `question`·`answer`·`published` 가운데 보낸 것만 바꾼다. */
	@PatchMapping("/admin/faqs/{id}")
	fun update(@PathVariable id: String, @RequestBody body: JsonNode): AdminFaq {
		val faqId = pathId(id)
		body.requireObject()
		if (!body.has("question") && !body.has("answer") && !body.has("published")) throw BadRequestException("INVALID_REQUEST")
		val published = body.booleanOrNull("published")
		if (body.has("published") && published == null) throw BadRequestException("INVALID_REQUEST")
		val question = if (body.has("question")) text(body, "question", QUESTION_MAX, "INVALID_QUESTION") else null
		val answer = if (body.has("answer")) text(body, "answer", ANSWER_MAX, "INVALID_ANSWER") else null
		return faqs.update(faqId, current.get().id, question, answer, published)
	}

	@DeleteMapping("/admin/faqs/{id}")
	fun delete(@PathVariable id: String): ResponseEntity<Void> {
		faqs.delete(pathId(id))
		return ResponseEntity.noContent().build()
	}

	/** `ids` 는 지금 있는 FAQ 전부를 보일 순서대로 한 번씩 담는다. 빠지거나 겹치거나 없는 id 가 있으면 `INVALID_ORDER`. */
	@PutMapping("/admin/faqs/order")
	fun reorder(@RequestBody body: JsonNode): List<AdminFaq> {
		val ids = body.requireObject()["ids"]
		if (ids == null || !ids.isArray || ids.any { !it.isString }) throw BadRequestException("INVALID_REQUEST")
		val parsed = ids.toList().map {
			try {
				UUID.fromString(it.stringValue())
			} catch (_: IllegalArgumentException) {
				throw BadRequestException("INVALID_ORDER")
			}
		}
		return faqs.reorder(parsed)
	}

	/** 앞뒤 공백을 뗀 1~[max]자(코드 포인트). 없거나 `null` 이거나 길이가 틀리면 [code]. */
	private fun text(body: JsonNode, field: String, max: Int, code: String): String {
		val value = body.textOrNull(field)?.trim()
		if (value == null || NUL in value || value.codePointLength() !in 1..max) throw BadRequestException(code)
		return value
	}
}
