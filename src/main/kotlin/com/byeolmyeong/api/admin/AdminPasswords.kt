package com.byeolmyeong.api.admin

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

private const val MIN_LENGTH = 10

/** bcrypt 는 72바이트까지만 본다. 넘는 비밀번호는 잘라 받지 않고 거절한다. */
private const val MAX_BYTES = 72

/** 관리자 비밀번호의 규칙과 해시(bcrypt). 비밀번호 원문은 저장하지도 로그에 남기지도 않는다. */
@Component
class AdminPasswords(@Value("\${byeolmyeong.admin.bcrypt-strength}") strength: Int) {
	private val encoder = BCryptPasswordEncoder(strength)

	/** 없는 아이디에도 같은 계산을 하려고 두는 해시(걸리는 시간으로 아이디가 있는지 알 수 없게). */
	private val dummyHash = encoder.encode("no-such-admin-placeholder")!!

	/** 10자 이상(코드 포인트)이고 UTF-8 로 72바이트 이하인가. */
	fun acceptable(password: String): Boolean =
		password.codePointLength() >= MIN_LENGTH && password.toByteArray().size <= MAX_BYTES

	fun hash(password: String): String = encoder.encode(password)!!

	/** [hash] 가 null(없는 아이디)이어도 같은 만큼 계산하고 false 를 준다. 비교는 라이브러리의 일정 시간 비교다. */
	fun matches(password: String, hash: String?): Boolean {
		val candidate = if (password.toByteArray().size <= MAX_BYTES) password else ""
		val matched = encoder.matches(candidate, hash ?: dummyHash)
		return matched && hash != null && candidate === password
	}
}
