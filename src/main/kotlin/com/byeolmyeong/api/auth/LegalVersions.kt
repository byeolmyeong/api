package com.byeolmyeong.api.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 지금 이용약관 · 개인정보 처리방침의 버전(결정 026, 설정 `byeolmyeong.legal.*`). 앱은 보여 준 버전을 가입 때 보낸다.
 * 비어 있으면 이 빈을 만들 때 예외를 던져 서버가 시작하지 않는다 — 비면 모든 가입이 400 이 되므로 배포의 상태 확인에서 바로 드러나게 한다.
 */
@Component
class LegalVersions(
	@Value("\${byeolmyeong.legal.terms-version}") terms: String,
	@Value("\${byeolmyeong.legal.privacy-version}") privacy: String,
) {
	val terms = terms.trim()
	val privacy = privacy.trim()

	init {
		if (this.terms.isEmpty() || this.privacy.isEmpty()) {
			LoggerFactory.getLogger(LegalVersions::class.java).error("event=startup_refused reason=legal_version_blank")
			throw IllegalStateException("약관 · 처리방침 버전(byeolmyeong.legal.terms-version · privacy-version)이 비어 있다(결정 026)")
		}
	}

	/** 앱이 보낸 두 버전이 지금 버전과 글자 그대로 같은가. 하나라도 없거나 다르면 false. */
	fun agreed(termsVersion: String?, privacyVersion: String?): Boolean = termsVersion == terms && privacyVersion == privacy
}
