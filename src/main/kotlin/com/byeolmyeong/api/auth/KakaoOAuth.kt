package com.byeolmyeong.api.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** 카카오 인가 코드를 교환하지 못했다(틀렸거나 썼거나 등록 안 된 돌아올 주소). 401 `INVALID_CODE`. */
class InvalidCodeException : RuntimeException("INVALID_CODE")

/** 카카오 서버 호출. 외부 호출이라 인터페이스 뒤에 둔다(architecture "의존 규칙"). */
interface KakaoOAuth {

	/**
	 * 웹 로그인의 인가 코드를 ID 토큰으로 바꾼다(결정 021 — 카카오 문서: 토큰 요청은 서비스 서버가 한다).
	 * [redirectUri] 가 등록한 목록에 없거나 카카오가 4xx 면 [InvalidCodeException], 닿지 못하거나 ID 토큰이 없으면 [ProviderUnavailableException].
	 * 돌려준 ID 토큰은 [ProviderTokenVerifier] 로 다시 확인한다. 함께 오는 액세스 토큰은 버린다.
	 */
	fun exchange(code: String, redirectUri: String): String
}

/** 카카오 REST API. 키·시크릿·코드·토큰을 로그에 남기지 않는다. */
@Component
class KakaoOAuthClient(
	@Value("\${byeolmyeong.auth.kakao.rest-api-key}") private val restApiKey: String,
	@Value("\${byeolmyeong.auth.kakao.client-secret}") private val clientSecret: String,
	@Value("\${byeolmyeong.auth.kakao.redirect-uris}") redirectUris: List<String>,
	@Value("\${byeolmyeong.auth.kakao.auth-url}") authUrl: String,
) : KakaoOAuth {

	private val log = LoggerFactory.getLogger(javaClass)
	private val mapper = JsonMapper()
	private val redirectUris = redirectUris.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
	private val auth = RestClient.builder()
		.baseUrl(authUrl)
		.requestFactory(
			SimpleClientHttpRequestFactory().apply {
				setConnectTimeout(Duration.ofSeconds(3))
				setReadTimeout(Duration.ofSeconds(5))
			},
		)
		.build()

	override fun exchange(code: String, redirectUri: String): String {
		if (redirectUri !in redirectUris) throw InvalidCodeException()
		val form = LinkedMultiValueMap<String, String>().apply {
			add("grant_type", "authorization_code")
			add("client_id", restApiKey)
			add("redirect_uri", redirectUri)
			add("code", code)
			if (clientSecret.isNotBlank()) add("client_secret", clientSecret)
		}
		val body = try {
			auth.post().uri("/oauth/token")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.onStatus({ it.is4xxClientError }) { _, _ -> throw InvalidCodeException() }
				.body(String::class.java)
		} catch (e: RestClientException) {
			log.warn("event=kakao_token_failed reason={}", e.javaClass.simpleName)
			throw ProviderUnavailableException(e)
		}
		val idToken = try {
			body?.let { mapper.readTree(it).path("id_token").asString(null) }
		} catch (_: JacksonException) {
			null
		}
		if (idToken.isNullOrBlank()) {
			// 카카오 콘솔에서 OpenID Connect 가 꺼져 있으면 ID 토큰이 오지 않는다. 사용자의 잘못이 아니다.
			log.warn("event=kakao_no_id_token")
			throw ProviderUnavailableException()
		}
		return idToken
	}
}
