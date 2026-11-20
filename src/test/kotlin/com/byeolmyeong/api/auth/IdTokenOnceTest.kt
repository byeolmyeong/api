package com.byeolmyeong.api.auth

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class IdTokenOnceTest {

	private val web = "web-client"

	private fun google(audience: String, azp: String?) =
		ProviderIdentity(Provider.GOOGLE, "sub", audience, null, tokenId = "jti", expiresAt = Instant.now(), authorizedParty = azp)

	@Test
	fun `웹 클라이언트가 받은 구글 토큰만 한 번만 받기 대상이다`() {
		assertEquals(true, needsOnce(google(web, azp = web), web))
		assertEquals(false, needsOnce(google(web, azp = null), web), "azp 가 없으면 걸지 않는다 — 앱 토큰일 수 있다(aud 는 앱도 웹)")
		assertEquals(false, needsOnce(google("ios-client", azp = "ios-client"), web), "iOS · 안드로이드는 아니다")
		assertEquals(false, needsOnce(google(web, azp = "ios-client"), web), "발급받은 쪽(azp)이 웹이 아니면 아니다")
		assertEquals(false, needsOnce(google(web, azp = web), ""), "설정이 비면 끈다")
		assertEquals(false, needsOnce(ProviderIdentity(Provider.APPLE, "sub", web, null, authorizedParty = web), web), "구글만")
		assertEquals(false, needsOnce(ProviderIdentity(Provider.KAKAO, "sub", web), web))
	}

	private fun startup(webClientId: String, clientIds: String = "web-client,ios-client") =
		IdTokenOnceService(JdbcTemplate(), webClientId, clientIds).logState()

	@Test
	fun `시작할 때 켜졌는지 · 꺼졌는지 · 설정이 틀렸는지 로그로 남긴다`(output: CapturedOutput) {
		startup("web-client")
		assertTrue("event=id_token_once_on" in output.all)

		startup("")
		assertTrue("event=id_token_once_off reason=NO_WEB_CLIENT_ID" in output.all)

		startup("web-clinet")
		assertTrue("event=id_token_once_off reason=WEB_CLIENT_ID_NOT_IN_CLIENT_IDS" in output.all, "오타면 아무 토큰도 대상이 아니므로 꺼진 것과 같다")
	}
}
