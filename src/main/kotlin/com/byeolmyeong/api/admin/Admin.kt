package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.ErrorResponse
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// 관리자 API(결정 014)가 함께 쓰는 것: 로그인한 관리자, 400·404 가 아닌 거절, 요청 본문을 타입까지 보고 읽는 함수.

/**
 * 로그인한 관리자. 응답의 `{ id, loginId, name, mustChangePassword }` 이기도 하다.
 * [mustChangePassword] 가 참이면 임시 비밀번호로 들어온 것이라 바꾸기 전에는 거의 모든 경로가 403 이다(결정 043 의 5).
 */
data class Admin(val id: UUID, val loginId: String, val name: String, val mustChangePassword: Boolean = false)

/** 400·404 가 아닌 거절(401·409·429). [code] 는 응답의 `error` 가 된다. 메시지에 입력값을 넣지 않는다. */
class AdminRejectedException(val status: Int, val code: String) : RuntimeException(code)

@RestControllerAdvice(basePackages = ["com.byeolmyeong.api.admin"])
class AdminErrors {

	@ExceptionHandler(AdminRejectedException::class)
	fun rejected(e: AdminRejectedException): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(e.status).body(ErrorResponse(e.code))
}

/** PostgreSQL 의 text 가 받지 못하는 글자. */
internal const val NUL = '\u0000'

/** 응답의 시각: UTC `YYYY-MM-DDTHH:mm:ssZ`. */
internal fun OffsetDateTime.utcSeconds(): String = toInstant().truncatedTo(ChronoUnit.SECONDS).toString()

/** 본문이 JSON 객체가 아니면 `INVALID_REQUEST`. */
internal fun JsonNode.requireObject(): JsonNode = if (isObject) this else throw BadRequestException("INVALID_REQUEST")

/** 키가 없거나 `null` 이면 null. 글자가 아닌 값이면 `INVALID_REQUEST`(숫자가 글자로 바뀌어 들어오지 않게 타입을 직접 본다). */
internal fun JsonNode.textOrNull(field: String): String? {
	val node = this[field]
	if (node == null || node.isNull) return null
	if (!node.isString) throw BadRequestException("INVALID_REQUEST")
	return node.stringValue()
}

/** 키가 없거나 `null` 이면 null. 참·거짓이 아닌 값이면 `INVALID_REQUEST`. */
internal fun JsonNode.booleanOrNull(field: String): Boolean? {
	val node = this[field]
	if (node == null || node.isNull) return null
	if (!node.isBoolean) throw BadRequestException("INVALID_REQUEST")
	return node.booleanValue()
}

/** 경로의 id. UUID 가 아니면 없는 것(404 `NOT_FOUND`)이다. */
internal fun pathId(raw: String): UUID =
	try {
		UUID.fromString(raw).takeIf { it.toString() == raw.lowercase() }
	} catch (_: IllegalArgumentException) {
		null
	} ?: throw NotFoundException("NOT_FOUND")

internal fun String.codePointLength(): Int = codePointCount(0, length)
