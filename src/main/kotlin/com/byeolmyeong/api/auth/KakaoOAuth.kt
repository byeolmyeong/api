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

	/**
	 * 탈퇴한 사용자와 앱의 연결을 끊는다(카카오 문서: 탈퇴 과정에 연결 해제를 반드시 포함). [userId] 는 카카오 회원번호(ID 토큰의 `sub`).
	 * 어드민 키로 서버에서 부른다. 성공하면 true, 실패하거나 키가 없으면 false — 계정 삭제는 계속된다.
	 */
	fun unlink(userId: String): Boolean
}

/** 카카오 REST API. 키·시크릿·코드·토큰·회원번호를 로그에 남기지 않는다. 어드민 키는 모든 사용자의 연결을 끊을 수 있는 키다. */
@Component
class KakaoOAuthClient(
	@Value("\${byeolmyeong.auth.kakao.rest-api-key}") private val restApiKey: String,
	@Value("\${byeolmyeong.auth.kakao.client-secret}") private val clientSecret: String,
	@Value("\${byeolmyeong.auth.kakao.redirect-uris}") redirectUris: List<String>,
	@Value("\${byeolmyeong.auth.kakao.auth-url}") authUrl: String,
	@Value("\${byeolmyeong.auth.kakao.admin-key}") private val adminKey: String,
	@Value("\${byeolmyeong.auth.kakao.api-url}") apiUrl: String,
) : KakaoOAuth {


	private val log = LoggerFactory.getLogger(javaClass)
	private val mapper = JsonMapper()
	private val redirectUris = redirectUris.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
	private val auth = client(authUrl)
	private val api = client(apiUrl)

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

	override fun unlink(userId: String): Boolean {
		if (adminKey.isBlank()) {
			log.warn("event=kakao_unlink_failed reason=NoAdminKey")
			return false
		}
		val form = LinkedMultiValueMap<String, String>().apply {
			add("target_id_type", "user_id")
			add("target_id", userId)
		}
		return try {
			api.post().uri("/v1/user/unlink")
				.header("Authorization", "KakaoAK $adminKey")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity()
			true
		} catch (e: RestClientException) {
			log.warn("event=kakao_unlink_failed reason={}", e.javaClass.simpleName)
			false
		}
	}

	private fun client(baseUrl: String): RestClient = RestClient.builder()
		.baseUrl(baseUrl)
		.requestFactory(
			SimpleClientHttpRequestFactory().apply {
				setConnectTimeout(Duration.ofSeconds(3))
				setReadTimeout(Duration.ofSeconds(5))
			},
		)
		.build()
}
