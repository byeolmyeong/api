package com.byeolmyeong.api.generation

import tools.jackson.databind.JsonNode

/**
 * 생성기에 넘기는 것. 출생 날짜·시각·장소는 들어 있지 않다(차트 요소만).
 * [purpose] 는 로그에 남기는 짧은 이름(어느 글인지), [instructions] 는 글쓰기 규칙, [userText] 는 서버가 계산한 차트,
 * [schema] 는 돌려받을 JSON 의 구조(JSON Schema).
 */
data class GenerationRequest(
	val purpose: String,
	val instructions: String,
	val userText: String,
	val schema: Map<String, Any>,
)

/** 글 생성 공급자를 가리는 한 곳. 공급자와 모델을 바꿀 때 구현만 바꾼다(결정 006). */
interface TextGenerator {

	/**
	 * 한 번 생성하고 응답의 JSON 객체를 돌려준다. 내용이 맞는지는 부르는 쪽이 본다.
	 * 응답은 왔지만 쓸 수 없으면(거절·잘림·JSON 객체가 아님) null 을 돌려준다 — 부르는 쪽이 다시 생성할 수 있다.
	 * 호출 자체가 실패하면(키 없음·HTTP 오류·시간 초과) [GenerationException] 을 던진다 — 다시 불러도 같을 실패다.
	 */
	fun generate(request: GenerationRequest): JsonNode?
}

/** 글을 만들지 못했다. [reason] 은 로그에 남기는 짧은 코드이고 글·차트·키를 담지 않는다. */
class GenerationException(val reason: String) : RuntimeException(reason)

// 응답은 외부 입력이다. 필드가 있고 타입이 맞는지 본 뒤에만 쓴다.

fun JsonNode?.stringOrNull(): String? = this?.takeIf { it.isString }?.asString()

fun JsonNode?.stringsOrNull(): List<String>? =
	this?.takeIf { it.isArray }?.values()?.map { it.stringOrNull() ?: return null }

/** 구조 지정에서 근거(`basis`) 필드의 모양: 그 차트에 있는 말 중에서만 고르게 한다. */
fun basisSchema(vocabulary: List<String>): Map<String, Any> =
	mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to vocabulary))
