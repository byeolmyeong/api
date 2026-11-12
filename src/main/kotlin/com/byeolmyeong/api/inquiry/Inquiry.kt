package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.web.BadRequestException
import tools.jackson.databind.JsonNode

private val CATEGORIES = setOf("ACCOUNT", "READING", "SYNASTRY", "BUG", "OTHER")
private const val BODY_MIN = 10
private const val BODY_MAX = 2000
private const val EMAIL_MAX = 254

/** PostgreSQL 의 text 가 받지 못하는 글자. */
private const val NUL = '\u0000'

/** 검증을 통과한 문의. [body]·[replyEmail] 은 앞뒤 공백을 뗀 값이고, 이메일을 남기지 않았으면 null 이다. */
class Inquiry(val category: String, val body: String, val replyEmail: String?)

/**
 * `POST /inquiries` 의 본문을 검증한다. 틀리면 [BadRequestException] 을 던진다
 * (`INVALID_REQUEST` 타입이 틀림 · `INVALID_CATEGORY` · `INVALID_BODY` · `INVALID_EMAIL`). 오류에 입력값을 넣지 않는다.
 * 숫자가 글자로 바뀌어 들어오지 않게 타입을 직접 본다.
 */
fun parseInquiry(request: JsonNode): Inquiry {
	if (!request.isObject) throw BadRequestException("INVALID_REQUEST")
	val category = text(request, "category")
	val body = text(request, "body")?.trim()
	val email = text(request, "replyEmail")?.trim()?.ifEmpty { null }

	if (category !in CATEGORIES) throw BadRequestException("INVALID_CATEGORY")
	// 글자 수는 코드 포인트로 센다(한글 한 글자도 이모지 한 개도 1).
	if (body == null || NUL in body || body.codePointCount(0, body.length) !in BODY_MIN..BODY_MAX) throw BadRequestException("INVALID_BODY")
	if (email != null && !looksLikeEmail(email)) throw BadRequestException("INVALID_EMAIL")
	return Inquiry(category!!, body, email)
}

/** 키가 없거나 `null` 이면 null. 글자가 아닌 값이면 `INVALID_REQUEST`. */
private fun text(request: JsonNode, field: String): String? {
	val node = request[field]
	if (node == null || node.isNull) return null
	if (!node.isString) throw BadRequestException("INVALID_REQUEST")
	return node.stringValue()
}

/** 모양만 본다: `@` 하나, 앞부분이 비지 않음, 뒷부분에 점, 공백 없음, 254자 이하. 실제로 있는 주소인지는 확인하지 않는다. */
private fun looksLikeEmail(email: String): Boolean {
	if (email.length > EMAIL_MAX || email.any { it.isWhitespace() || it == NUL }) return false
	val parts = email.split('@')
	return parts.size == 2 && parts[0].isNotEmpty() && '.' in parts[1]
}
