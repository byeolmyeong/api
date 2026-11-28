package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.BadRequestException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

private const val NOTICE_TITLE_MAX = 60
private const val NOTICE_BODY_MAX = 3000
private val NOTICE_CATEGORIES = setOf(NOTICE_CATEGORY_NOTICE, NOTICE_CATEGORY_UPDATE)

/**
 * 공지사항 편집(결정 055 의 3): 만들기 · 고치기 · 지우기 · 공개 · 내리기. 앱은 공개한 것만 `GET /notices`(`notice` 패키지)로 읽는다.
 * "알림 보내기"를 켜고 공개하면 한 번만 알림함 · 푸시로 간다(055 의 4).
 */
@RestController
class AdminNoticeController(private val notices: AdminNoticeService, private val current: CurrentAdmin) {

	@GetMapping("/admin/notices")
	fun list(): List<AdminNotice> = notices.list()

	/** 지금 알림을 보내면 푸시를 받을 사람 수(확인 창 — 외부 발송 전 예상 수). */
	@GetMapping("/admin/notices/audience")
	fun audience(): NoticeAudience = NoticeAudience(notices.audience())

	@GetMapping("/admin/notices/{id}")
	fun get(@PathVariable id: String): AdminNotice = notices.get(pathId(id))

	/** 임시 저장으로 만든다. `notify` 를 보내지 않으면 꺼진 채로. */
	@PostMapping("/admin/notices")
	fun create(@RequestBody body: JsonNode): ResponseEntity<AdminNotice> {
		val fields = NoticeFields.of(body)
		return ResponseEntity.status(201).body(notices.create(current.get().id, fields, fields.notify ?: false))
	}

	/** 갈래 · 제목 · 본문을 바꾼다(셋 다 있어야 한다). `notify` 를 빼면 그대로 둔다. 공개 중이면 공개한 채로 바뀐다. */
	@PutMapping("/admin/notices/{id}")
	fun update(@PathVariable id: String, @RequestBody body: JsonNode): AdminNotice {
		val noticeId = pathId(id)
		return notices.update(noticeId, current.get().id, NoticeFields.of(body))
	}

	/** `notify`(꼭 있어야 하는 참 · 거짓)를 적고 공개한다. 알림은 한 공지에 한 번만 간다. */
	@PostMapping("/admin/notices/{id}/publish")
	fun publish(@PathVariable id: String, @RequestBody body: JsonNode): AdminNotice {
		val noticeId = pathId(id)
		val notify = body.requireObject().booleanOrNull("notify") ?: throw BadRequestException("INVALID_REQUEST")
		return notices.publish(noticeId, current.get().id, notify)
	}

	@PostMapping("/admin/notices/{id}/unpublish")
	fun unpublish(@PathVariable id: String): AdminNotice = notices.unpublish(pathId(id), current.get().id)

	@DeleteMapping("/admin/notices/{id}")
	fun delete(@PathVariable id: String): ResponseEntity<Void> {
		notices.delete(pathId(id))
		return ResponseEntity.noContent().build()
	}
}

/** 만들기 · 고치기의 본문. 제목 · 본문은 앞뒤 공백을 뗀 1 ~ 60자 · 1 ~ 3000자(코드 포인트). [notify] 는 보낸 때만 있다. */
data class NoticeFields(val category: String, val title: String, val body: String, val notify: Boolean?) {
	companion object {
		fun of(body: JsonNode): NoticeFields {
			body.requireObject()
			val category = body.textOrNull("category")?.takeIf { it in NOTICE_CATEGORIES } ?: throw BadRequestException("INVALID_CATEGORY")
			val title = text(body, "title", NOTICE_TITLE_MAX, "INVALID_TITLE")
			val text = text(body, "body", NOTICE_BODY_MAX, "INVALID_BODY")
			return NoticeFields(category, title, text, body.booleanOrNull("notify"))
		}

		/** 앞뒤 공백을 뗀 1~[max]자(코드 포인트). 없거나 `null` 이거나 길이가 틀리면 [code]. */
		private fun text(body: JsonNode, field: String, max: Int, code: String): String {
			val value = body.textOrNull(field)?.trim()
			if (value == null || NUL in value || value.codePointLength() !in 1..max) throw BadRequestException(code)
			return value
		}
	}
}
