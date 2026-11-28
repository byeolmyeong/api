package com.byeolmyeong.api.report

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * "받을 수 있나"(결정 029 의 4 · 056 의 3). 서비스가 풀이 생성을 새로 시작하기 직전(생성 한도를 세기 전)에 부르고,
 * false 면 402 `PURCHASE_REQUIRED`(`web.PurchaseRequiredException`)로 답한다. 이미 완성됐거나 쓰는 중인 풀이는 묻지 않는다.
 * 결제 시작 시각 전이면 모두 허용(1.0.0 과 같다), 그 뒤면 상품을 가졌는지(`EntitlementService.owns`). 상품은 하나라 [kind] 를 가리지 않는다.
 * RevenueCat 을 묻지 못하면 `web.PurchaseCheckFailedException`(503) 이 그대로 나간다.
 */
@Component
class EntitlementPolicy(private val start: PurchaseStart, private val entitlements: EntitlementService) {

	fun allows(ownerId: UUID, kind: DetailReportKind): Boolean = !start.started() || entitlements.owns(ownerId)
}
