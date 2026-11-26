package com.byeolmyeong.api.auth

import java.security.MessageDigest
import java.util.UUID

/**
 * 분석 도구에 보내는 사용자 id(앱 결정 040 · 081, `GET /auth/analytics-id`). 계정 id 의 SHA-256 앞 32자(16진수)라 되돌릴 수 없고,
 * 분석 도구의 데이터와 DB 의 계정 id 가 바로 이어지지 않는다. 같은 계정은 늘 같은 값이라 저장하지 않는다.
 */
fun analyticsIdOf(ownerId: UUID): String =
	MessageDigest.getInstance("SHA-256")
		.digest("byeolmyeong-analytics:$ownerId".toByteArray())
		.joinToString("") { "%02x".format(it) }
		.take(32)
