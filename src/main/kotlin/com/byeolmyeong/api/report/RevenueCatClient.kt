package com.byeolmyeong.api.report

import com.byeolmyeong.api.web.PurchaseCheckFailedException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * `GET {base-url}/v1/subscribers/{계정 id}`(RevenueCat REST v1 "Get or create a customer" — 없으면 만들고 201). 결정 056 의 4.
 * `subscriber.entitlements` 에 상품 이름이 있고 `expires_date` 가 null(평생 — 비소모성)이거나 지금보다 뒤면 있다. 이 사전은 지난 것도 담는다.
 * 비밀 키 없음 · 연결 실패 · 시간 초과 · 2xx 가 아님 · 읽을 수 없는 본문은 모두 묻지 못한 것이다 — 다시 부르지 않는다.
 * 비밀 키 · 계정 id · 본문은 로그에 남기지 않는다(사유 코드만).
 */
@Component
class RevenueCatClient(
	@Value("\${byeolmyeong.purchase.revenuecat-secret-key}") private val secretKey: String,
	@Value("\${byeolmyeong.purchase.revenuecat-base-url}") baseUrl: String,
	@Value("\${byeolmyeong.purchase.revenuecat-entitlement}") private val entitlement: String,
	private val clock: Clock,
) : RevenueCat {
	private val log = LoggerFactory.getLogger(javaClass)
	private val mapper = JsonMapper()

	// 받기 요청 안에서 부르므로 짧게 기다린다.
	private val client = RestClient.builder()
		.baseUrl(baseUrl)
		.requestFactory(SimpleClientHttpRequestFactory().apply {
			setConnectTimeout(Duration.ofSeconds(3))
			setReadTimeout(Duration.ofSeconds(5))
		})
		.build()

	override fun hasDetail(accountId: UUID): Boolean {
		if (secretKey.isBlank()) fail("NO_SECRET_KEY")
		val body = try {
			client.get().uri("/v1/subscribers/{id}", accountId.toString())
				.accept(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer $secretKey")
				.retrieve()
				.body(String::class.java)
		} catch (e: RestClientException) {
			fail("REQUEST_${e.javaClass.simpleName}")
		}
		val entitlements = try {
			body?.let(mapper::readTree)?.get("subscriber")?.get("entitlements")
		} catch (_: JacksonException) {
			null
		}
		if (entitlements == null || !entitlements.isObject) fail("UNREADABLE_RESPONSE")
		val detail = entitlements.get(entitlement) ?: return false
		if (!detail.isObject) fail("UNREADABLE_RESPONSE")
		val expires = detail.get("expires_date")
		if (expires == null || expires.isNull) return true
		if (!expires.isString) fail("UNREADABLE_RESPONSE")
		val until = try {
			Instant.parse(expires.stringValue())
		} catch (_: DateTimeParseException) {
			fail("UNREADABLE_RESPONSE")
		}
		return until.isAfter(clock.instant())
	}

	private fun fail(reason: String): Nothing {
		log.warn("event=purchase_check_failed reason={}", reason)
		throw PurchaseCheckFailedException(reason)
	}
}
