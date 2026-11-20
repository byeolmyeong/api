package com.byeolmyeong.api.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 가입에서 받은 이용약관 · 개인정보 처리방침 버전 — 사람이 실제로 보낸 값이다. 계정 행과 감사 기록 `TERMS_AGREED` 에 이것을 남긴다(결정 045). */
data class AgreedVersions(val terms: String, val privacy: String)

/**
 * 가입 때 받는 이용약관 · 개인정보 처리방침의 버전(결정 026 · 045, 설정 `byeolmyeong.legal.*`). 앱은 보여 준 버전을 가입 때 보낸다.
 * 지금 버전과, 버전을 바꾼 뒤 잠깐 함께 받는 옛 버전 목록(쉼표로 여럿, 비어도 된다)을 약관 · 처리방침 각각 둔다.
 * 지금 버전이 비어 있으면 이 빈을 만들 때 예외를 던져 서버가 시작하지 않는다 — 비면 모든 가입이 400 이 되므로 배포의 상태 확인에서 바로 드러나게 한다.
 */
@Component
class LegalVersions(
	@Value("\${byeolmyeong.legal.terms-version}") terms: String,
	@Value("\${byeolmyeong.legal.privacy-version}") privacy: String,
	@Value("\${byeolmyeong.legal.terms-previous-versions:}") termsPrevious: String,
	@Value("\${byeolmyeong.legal.privacy-previous-versions:}") privacyPrevious: String,
) {
	private val termsAccepted = setOf(terms.trim()) + versions(termsPrevious)
	private val privacyAccepted = setOf(privacy.trim()) + versions(privacyPrevious)

	init {
		if (terms.isBlank() || privacy.isBlank()) {
			LoggerFactory.getLogger(LegalVersions::class.java).error("event=startup_refused reason=legal_version_blank")
			throw IllegalStateException("약관 · 처리방침 버전(byeolmyeong.legal.terms-version · privacy-version)이 비어 있다(결정 026)")
		}
	}

	/**
	 * 앱이 보낸 두 버전이 각각 지금 버전이나 함께 받는 옛 버전과 글자 그대로 같으면 그 두 값을 그대로 돌려준다(섞여도 된다).
	 * 하나라도 없거나 목록 밖이면 null.
	 */
	fun agreed(termsVersion: String?, privacyVersion: String?): AgreedVersions? {
		if (termsVersion !in termsAccepted || privacyVersion !in privacyAccepted) return null
		return AgreedVersions(checkNotNull(termsVersion), checkNotNull(privacyVersion))
	}

	private fun versions(list: String): Set<String> = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
