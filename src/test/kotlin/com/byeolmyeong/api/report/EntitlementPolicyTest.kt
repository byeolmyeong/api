package com.byeolmyeong.api.report

import com.byeolmyeong.api.web.ErrorResponse
import com.byeolmyeong.api.web.ErrorResponses
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "받을 수 있나" 확인 자리(결정 029 의 4). 1.0.0 은 두 풀이 모두 무료라 언제나 허용하고, 거절의 답은 402 `PURCHASE_REQUIRED` 로 예약만 한다. */
class EntitlementPolicyTest {

	@Test
	fun `1점0점0 은 누구에게나 두 풀이 모두 허용한다`() {
		val policy = EntitlementPolicy()
		repeat(3) {
			val owner = UUID.randomUUID()
			for (kind in DetailReportKind.entries) assertTrue(policy.allows(owner, kind), "$kind")
		}
	}

	@Test
	fun `거절하면 402 PURCHASE_REQUIRED 로 답한다`() {
		val response = ErrorResponses().purchaseRequired()
		assertEquals(402, response.statusCode.value())
		assertEquals(ErrorResponse("PURCHASE_REQUIRED"), response.body)
	}
}
