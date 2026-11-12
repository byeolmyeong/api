package com.byeolmyeong.api.web

import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

private const val OWNER_REF_LENGTH = 12

/**
 * 관리자에게 앱 사용자를 가리키는 말: 계정 id 의 SHA-256 앞 12자(16진수). 계정 id 를 그대로 내보내지 않는다(결정 014·015).
 * 같은 계정이면 값이 같아 문의와 감사 기록을 묶어 볼 수 있을 뿐, 계정을 찾는 열쇠가 아니다.
 */
fun ownerRef(accountId: UUID): String =
	HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(accountId.toString().toByteArray())).take(OWNER_REF_LENGTH)
