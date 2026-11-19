package com.byeolmyeong.api.auth

private const val MAX_NICKNAME_CODE_POINTS = 20

/** 줄바꿈·제어 문자(Cc)·줄·문단 구분자(Zl·Zp)는 닉네임에 쓸 수 없다. */
private fun forbidden(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
	Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
	else -> false
}

/**
 * 닉네임 규칙(결정 025): 앞뒤 공백을 뗀 뒤 유니코드 코드 포인트 1~20개(이모지 하나 = 1), 줄바꿈·제어 문자 없음, 중복은 된다.
 * 맞으면 앞뒤 공백을 뗀 값, 틀리면 null. 값은 로그에 남기지 않는다.
 */
fun normalizeNickname(raw: String?): String? {
	val nickname = raw?.trim() ?: return null
	val length = nickname.codePointCount(0, nickname.length)
	if (length !in 1..MAX_NICKNAME_CODE_POINTS) return null
	if (nickname.codePoints().anyMatch(::forbidden)) return null
	return nickname
}
