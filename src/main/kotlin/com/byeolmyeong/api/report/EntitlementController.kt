package com.byeolmyeong.api.report

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.ErrorResponse
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/** 고지 판: 영문 · 숫자 · `.` · `_` · `-` 1~40자(앱이 정하는 짧은 이름 — 예 `P1-2026-11-27`). */
private val NOTICE_VERSION = Regex("[0-9A-Za-z._-]{1,40}")

/** `GET /entitlements` 의 답: 자세한 풀이를 받을 수 있나(결정 056 의 8). 앱은 화면("샀음")만 바꾸고 받기 판정은 여전히 402 를 따른다. */
data class EntitlementResponse(val detail: Boolean)

/** `POST /entitlements/confirm` 의 답. 스토어 구매가 없으면 `{ detail: false }` 만. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EntitlementConfirmResponse(val detail: Boolean, val me: ReportStatus? = null, val timing: ReportStatus? = null)

/** 자세한 풀이 상품(결정 056). 누구의 것인지는 세션에서 얻는다. */
@RestController
class EntitlementController(
	private val owner: CurrentOwner,
	private val policy: EntitlementPolicy,
	private val entitlements: EntitlementService,
	private val me: MeReportService,
	private val timing: TimingReportService,
) {

	/** 받기와 같은 판정(결제 시작 전이면 true). RevenueCat 을 묻지 못하면 503. */
	@GetMapping("/entitlements")
	fun read(): EntitlementResponse = EntitlementResponse(policy.hasDetail(owner.id()))

	/**
	 * 결제 · 복원 직후 앱이 부른다(결정 056 의 7). 스토어 구매가 있으면 `STORE` 로 적고 나 탭 풀이와 시기 풀이를 함께 시작한다(앱 결정 101 의 5-1) —
	 * 받기 경로와 같은 길이라 이미 완성 · 쓰는 중이면 그 상태다. 다시 불러도 같다: 시작에서 404 · 429 가 나도 구매는 적혀 있고, 다시 부르면 남은 것을 시작한다.
	 * 본문은 없어도 되고, 있으면 `{ noticeVersion? }` — 결제 시트가 보여 준 고지의 판(앱 결정 101 의 5-1). RevenueCat 을 묻기 전에 검사한다.
	 */
	@PostMapping("/entitlements/confirm")
	fun confirm(
		@RequestHeader("X-App-Version", required = false) appVersion: String?,
		@RequestBody(required = false) body: JsonNode?,
	): EntitlementConfirmResponse {
		val ownerId = owner.id()
		if (!entitlements.confirm(ownerId, noticeVersion(body))) return EntitlementConfirmResponse(detail = false)
		return EntitlementConfirmResponse(detail = true, me = me.request(ownerId, appVersion), timing = timing.request(ownerId, appVersion))
	}

	/** 키가 없거나 null 이면 null. 글자가 아니면 `INVALID_REQUEST`, 꼴이 틀리면 `INVALID_NOTICE_VERSION`. */
	private fun noticeVersion(body: JsonNode?): String? {
		if (body == null) return null
		if (!body.isObject) throw BadRequestException("INVALID_REQUEST")
		val node = body["noticeVersion"]
		if (node == null || node.isNull) return null
		if (!node.isString) throw BadRequestException("INVALID_REQUEST")
		return node.stringValue().takeIf(NOTICE_VERSION::matches) ?: throw BadRequestException("INVALID_NOTICE_VERSION")
	}

	@ExceptionHandler(ConfirmRateLimitedException::class)
	fun rateLimited(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(429).body(ErrorResponse("RATE_LIMITED"))
}
