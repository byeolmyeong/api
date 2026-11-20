package com.byeolmyeong.api.auth

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class IdTokenOnceTest {

	private val web = "web-client"

	private fun google(audience: String, azp: String?) =
		ProviderIdentity(Provider.GOOGLE, "sub", audience, null, tokenId = "jti", expiresAt = Instant.now(), authorizedParty = azp)

	@Test
	fun `웹 클라이언트가 받은 구글 토큰만 한 번만 받기 대상이다`() {
		assertEquals(true, needsOnce(google(web, azp = web), web))
		assertEquals(true, needsOnce(google(web, azp = null), web), "azp 가 없으면 대상(aud)으로")
		assertEquals(false, needsOnce(google("ios-client", azp = "ios-client"), web), "iOS · 안드로이드는 아니다")
		assertEquals(false, needsOnce(google(web, azp = "ios-client"), web), "발급받은 쪽(azp)이 웹이 아니면 아니다")
		assertEquals(false, needsOnce(google(web, azp = web), ""), "설정이 비면 끈다")
		assertEquals(false, needsOnce(ProviderIdentity(Provider.APPLE, "sub", web, null, authorizedParty = web), web), "구글만")
		assertEquals(false, needsOnce(ProviderIdentity(Provider.KAKAO, "sub", web), web))
	}
}
