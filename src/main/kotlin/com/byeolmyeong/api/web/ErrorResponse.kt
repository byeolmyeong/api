package com.byeolmyeong.api.web

import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 오류 응답의 모양: `{ error }`. 값은 계약(docs/architecture.md)에 적힌 코드다. */
data class ErrorResponse(val error: String)

/** 요청이 틀렸다(400). [code] 는 응답의 `error` 가 된다. 메시지에 입력값을 넣지 않는다. */
class BadRequestException(val code: String) : RuntimeException(code)

/** 찾는 것이 없다(404). 남의 것도 없는 것으로 답한다. */
class NotFoundException(val code: String) : RuntimeException(code)

/** 이 풀이를 받을 수 없다(402 `PURCHASE_REQUIRED`, 결정 029 의 4 — 예약). 1.0.0 에는 나오지 않는다. */
class PurchaseRequiredException : RuntimeException("PURCHASE_REQUIRED")

@RestControllerAdvice
class ErrorResponses {

	@ExceptionHandler(BadRequestException::class)
	fun badRequest(e: BadRequestException): ResponseEntity<ErrorResponse> =
		ResponseEntity.badRequest().body(ErrorResponse(e.code))

	@ExceptionHandler(NotFoundException::class)
	fun notFound(e: NotFoundException): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(404).body(ErrorResponse(e.code))

	@ExceptionHandler(PurchaseRequiredException::class)
	fun purchaseRequired(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(402).body(ErrorResponse("PURCHASE_REQUIRED"))

	@ExceptionHandler(GenerationRateLimitedException::class)
	fun generationRateLimited(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(429).body(ErrorResponse("RATE_LIMITED"))

	// 본문이 JSON 이 아니거나 타입이 틀린 경우. 기본 처리기는 입력 조각이 든 메시지를 경고 로그로 남기므로 여기서 받는다.
	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun unreadable(): ResponseEntity<ErrorResponse> =
		ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST"))
}
