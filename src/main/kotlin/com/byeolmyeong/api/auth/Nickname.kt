package com.byeolmyeong.api.auth

/** 한글(완성형 가~힣) · 영문(a~z, A~Z)만 2~10자. 한글 완성형은 한 글자가 한 칸(UTF-16)이라 글자 수가 곧 칸 수다. */
private val NICKNAME = Regex("^[가-힣a-zA-Z]{2,10}$")

/** 닉네임 유일 색인(V20). 영문 대소문자는 같은 이름으로 본다. */
const val NICKNAME_UNIQUE_INDEX = "accounts_nickname_lower_key"

/**
 * 닉네임 규칙(결정 025, 소유자 2026-11-19): 앞뒤 공백을 뗀 뒤 한글 · 영문만 2~10자. 숫자 · 공백 · 기호 · 이모지는 안 된다.
 * 중복은 안 된다(유일 색인 [NICKNAME_UNIQUE_INDEX]). 맞으면 앞뒤 공백을 뗀 값, 틀리면 null. 값은 로그에 남기지 않는다.
 */
fun normalizeNickname(raw: String?): String? = raw?.trim()?.takeIf { NICKNAME.matches(it) }

/** 다른 계정이 이미 쓰는 닉네임이다. 409 `NICKNAME_TAKEN`(결정 025). */
class NicknameTakenException : RuntimeException("NICKNAME_TAKEN")
