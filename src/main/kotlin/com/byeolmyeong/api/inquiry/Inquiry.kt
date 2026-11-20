package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.parseAppVersion
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode
import java.util.Locale
import java.util.UUID

private val CATEGORIES = setOf("BUG", "IDEA", "ACCOUNT", "ETC")

/** 옛 앱(결정 013)만 보내는 종류. 옛 앱의 요청에서만 받는다 — 계획 S⑦ 에서 닫는다(결정 039 의 2). */
private val LEGACY_CATEGORIES = setOf("READING", "SYNASTRY", "OTHER")
private const val BODY_MIN = 10
private const val BODY_MAX = 1000
private const val LEGACY_BODY_MAX = 2000
private val PLATFORMS = setOf("ios", "android")

/** PostgreSQL 의 text 가 받지 못하는 글자. */
private const val NUL = '\u0000'

/** 검증을 통과한 문의. [body] 는 앞뒤 공백을 뗀 값이다. */
class Inquiry(val category: String, val body: String)

/** 문의를 보낸 앱(결정 039 의 2). 헤더가 없거나 읽을 수 없으면 null 이다 — 웹은 보내지 않는다. */
class InquiryApp(val platform: String?, val version: String?)

/**
 * `POST /inquiries` 의 본문을 검증한다. 틀리면 [BadRequestException] 을 던진다
 * (`INVALID_REQUEST` 타입이 틀림 · `INVALID_CATEGORY` · `INVALID_BODY`). 오류에 입력값을 넣지 않는다.
 * 숫자가 글자로 바뀌어 들어오지 않게 타입을 직접 본다.
 *
 * 옛 앱은 언제나 `replyEmail` 키를 보낸다(값이 없으면 null). 그 요청은 옛 규칙(옛 종류도 · 2000자까지)으로 받고,
 * 이메일은 값 · 타입과 상관없이 읽지 않는다(결정 039 의 2).
 */
fun parseInquiry(request: JsonNode): Inquiry {
	if (!request.isObject) throw BadRequestException("INVALID_REQUEST")
	val category = text(request, "category")
	val body = text(request, "body")?.trim()
	val legacy = request.has("replyEmail")

	if (category !in CATEGORIES && !(legacy && category in LEGACY_CATEGORIES)) throw BadRequestException("INVALID_CATEGORY")
	// 글자 수는 코드 포인트로 센다(한글 한 글자도 이모지 한 개도 1).
	val bodyMax = if (legacy) LEGACY_BODY_MAX else BODY_MAX
	if (body == null || NUL in body || body.codePointCount(0, body.length) !in BODY_MIN..bodyMax) throw BadRequestException("INVALID_BODY")
	return Inquiry(category!!, body)
}

/** 앱이 보낸 `X-App-Platform`(대소문자 무시 → 소문자) · `X-App-Version`(결정 016 의 버전 규칙). 모르는 값은 null. */
fun inquiryApp(platformHeader: String?, versionHeader: String?): InquiryApp = InquiryApp(
	platform = platformHeader?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in PLATFORMS },
	version = versionHeader?.trim()?.takeIf { parseAppVersion(it) != null },
)

/** 키가 없거나 `null` 이면 null. 글자가 아닌 값이면 `INVALID_REQUEST`. */
private fun text(request: JsonNode, field: String): String? {
	val node = request[field]
	if (node == null || node.isNull) return null
	if (!node.isString) throw BadRequestException("INVALID_REQUEST")
	return node.stringValue()
}

/**
 * 사용자가 보는 내 문의(`GET /inquiries`, 결정 039 의 3). [status] 는 답이 있으면 `ANSWERED`, 없으면 `OPEN` —
 * 관리자의 처리 단계 · 메모 · 임시 저장 · 앱 버전은 없다. [replyEmail] 은 그 칸이 있는 옛 문의에만 있다. 값이 없는 키는 보내지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MyInquiry(
	val id: UUID,
	val category: String,
	val body: String,
	val status: String,
	val createdAt: String,
	val answer: InquiryAnswer?,
	val replyEmail: String?,
)

/** 관리자의 답. [answeredAt] 은 처음 답한 때다(고쳐도 그대로). */
data class InquiryAnswer(val body: String, val answeredAt: String)
