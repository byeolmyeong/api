package com.byeolmyeong.api.report

import com.byeolmyeong.api.web.ErrorResponse
import com.byeolmyeong.api.web.ErrorResponses
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * "받을 수 있나" 의 답 모양(결정 029 의 4 · 056). 판정 자체는 결제 시작 시각 · 표 · RevenueCat 이 있어야 해서
 * `EntitlementCheckIntegrationTest` · `EntitlementNoStartIntegrationTest` 가 본다.
 */
class EntitlementPolicyTest {

	@Test
	fun `거절하면 402 PURCHASE_REQUIRED 로 답한다`() {
		val response = ErrorResponses().purchaseRequired()
		assertEquals(402, response.statusCode.value())
		assertEquals(ErrorResponse("PURCHASE_REQUIRED"), response.body)
	}

	@Test
	fun `묻지 못하면 503 PURCHASE_CHECK_FAILED 로 답한다`() {
		val response = ErrorResponses().purchaseCheckFailed()
		assertEquals(503, response.statusCode.value())
		assertEquals(ErrorResponse("PURCHASE_CHECK_FAILED"), response.body)
	}
}
