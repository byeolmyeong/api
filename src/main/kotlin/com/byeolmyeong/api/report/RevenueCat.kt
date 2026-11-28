package com.byeolmyeong.api.report

import java.util.UUID

/** RevenueCat 구독자 조회(결정 056 의 4). 외부 호출이라 인터페이스 뒤에 둔다 — 테스트는 가짜로 바꾼다. */
interface RevenueCat {

	/** 그 계정(앱의 RevenueCat 사용자 id = 계정 id)에 자세한 풀이 상품이 지금 있는지. 묻지 못했으면 `web.PurchaseCheckFailedException`. */
	fun hasDetail(accountId: UUID): Boolean
}
