package com.byeolmyeong.api.report

import org.springframework.stereotype.Component
import java.util.UUID

/** 자세한 풀이의 종류(결정 029). 글자는 `detail_reports.kind` 와 같다. */
enum class DetailReportKind { ME, TIMING }

/**
 * "받을 수 있나" 확인 자리(결정 029 의 4). 서비스가 풀이 생성을 시작하기 직전(생성 한도를 세기 전)에 부르고,
 * false 면 402 `PURCHASE_REQUIRED`(`web.PurchaseRequiredException`)로 답한다. 이미 완성됐거나 쓰는 중인 풀이는 묻지 않는다.
 * 1.0.0 은 두 풀이 모두 무료라 언제나 허용한다. 1.1.0 결제 계획에서 이 함수 안에 결제 확인만 끼운다 — 인터페이스 · 설정 스위치를 두지 않는다.
 */
@Component
class EntitlementPolicy {

	fun allows(ownerId: UUID, kind: DetailReportKind): Boolean = true
}
