package com.byeolmyeong.api.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.ParseException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

private const val APPLE_ISSUER = "https://appleid.apple.com"

/** client_secret 의 수명. 부를 때마다 새로 만든다(애플은 최대 6개월까지 받는다). */
private val CLIENT_SECRET_TTL = Duration.ofMinutes(5)

/** 애플 서버 알림 하나. [type] 은 `consent-revoked` · `account-deleted` · `email-enabled` · `email-disabled` 등이고 [eventTime] 은 일어난 때(없으면 null)다. */
data class AppleEvent(val type: String, val subject: String, val eventTime: Instant? = null)

/** 애플 서버 알림이 아니다(서명 · 발급자 · 대상 · 모양). 400 `INVALID_NOTIFICATION`. */
class InvalidNotificationException : RuntimeException("INVALID_NOTIFICATION")

/** 애플 서버 호출(토큰 교환 · 철회)과 서버 알림 확인. 외부 호출이라 인터페이스 뒤에 둔다(architecture "의존 규칙"). */
interface AppleSignIn {

	/**
	 * 로그인 때 받은 authorization code(5분 · 1회용)를 refresh token 으로 바꾼다. [clientId] 는 그 로그인이 쓴 클라이언트
	 * (iOS 번들 ID 또는 웹 Services ID), [redirectUri] 는 웹 로그인이 쓴 돌아올 주소(앱은 null). 실패하면 null — 로그인은 계속된다.
	 */
	fun exchange(code: String, clientId: String, redirectUri: String?): String?

	/** 탈퇴 때 refresh token 을 철회한다(심사 지침 5.1.1(v)). 성공하면 true, 실패하면 false — 계정 삭제는 계속된다. */
	fun revoke(refreshToken: String, clientId: String): Boolean

	/** 서버 알림의 `payload`(애플이 서명한 JWT)를 확인한다. 틀리면 [InvalidNotificationException], 애플 키를 못 받으면 [ProviderUnavailableException]. */
	fun notification(payload: String): AppleEvent
}

/**
 * 애플 REST API(`/auth/token` · `/auth/revoke`)와 서버 알림 확인(결정 021). client_secret 은 .p8 키로 서명한 ES256 JWT 다.
 * 키(`APPLE_TEAM_ID` · `APPLE_KEY_ID` · `APPLE_PRIVATE_KEY`)가 없으면 애플을 부르지 않는다. 키 · 코드 · 토큰을 로그에 남기지 않는다.
 */
@Component
class AppleSignInClient(
	@Value("\${byeolmyeong.auth.apple.team-id}") private val teamId: String,
	@Value("\${byeolmyeong.auth.apple.key-id}") private val keyId: String,
	@Value("\${byeolmyeong.auth.apple.private-key}") privateKey: String,
	@Value("\${byeolmyeong.auth.apple.client-ids}") clientIds: List<String>,
	@Value("\${byeolmyeong.auth.apple.auth-url}") authUrl: String,
	@Qualifier("appleKeys") keys: JWKSource<SecurityContext>,
) : AppleSignIn {

	private val log = LoggerFactory.getLogger(javaClass)
	private val mapper = JsonMapper()
	private val clientIds = clientIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
	private val signer: ECDSASigner? = parsePrivateKey(privateKey)?.takeIf { teamId.isNotBlank() && keyId.isNotBlank() }?.let(::ECDSASigner)
	private val http = RestClient.builder()
		.baseUrl(authUrl)
		.requestFactory(
			SimpleClientHttpRequestFactory().apply {
				setConnectTimeout(Duration.ofSeconds(3))
				setReadTimeout(Duration.ofSeconds(5))
			},
		)
		.build()

	// 알림에는 sub · exp 가 없다(애플 문서의 예). 서명 · 발급자만 여기서 보고 대상은 아래에서 목록으로 본다.
	private val notifications = DefaultJWTProcessor<SecurityContext>().apply {
		jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
		jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(JWTClaimsSet.Builder().issuer(APPLE_ISSUER).build(), setOf("aud", "events"))
	}

	override fun exchange(code: String, clientId: String, redirectUri: String?): String? {
		val form = form(clientId) ?: return null
		form.add("code", code)
		form.add("grant_type", "authorization_code")
		if (redirectUri != null) form.add("redirect_uri", redirectUri)
		val body = try {
			http.post().uri("/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String::class.java)
		} catch (e: RestClientException) {
			log.warn("event=apple_token_failed reason={}", e.javaClass.simpleName)
			return null
		}
		val refresh = try {
			body?.let { mapper.readTree(it).path("refresh_token").asString(null) }
		} catch (_: JacksonException) {
			null
		}
		if (refresh.isNullOrBlank()) log.warn("event=apple_token_failed reason=NoRefreshToken")
		return refresh?.takeIf { it.isNotBlank() }
	}

	override fun revoke(refreshToken: String, clientId: String): Boolean {
		val form = form(clientId) ?: run {
			log.warn("event=apple_revoke_failed reason=NotConfigured")
			return false
		}
		form.add("token", refreshToken)
		form.add("token_type_hint", "refresh_token")
		return try {
			http.post().uri("/auth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity()
			true
		} catch (e: RestClientException) {
			log.warn("event=apple_revoke_failed reason={}", e.javaClass.simpleName)
			false
		}
	}

	override fun notification(payload: String): AppleEvent {
		val claims = try {
			notifications.process(payload, null)
		} catch (_: RateLimitReachedException) {
			throw InvalidNotificationException()
		} catch (e: KeySourceException) {
			throw ProviderUnavailableException(e)
		} catch (_: ParseException) {
			throw InvalidNotificationException()
		} catch (_: BadJOSEException) {
			throw InvalidNotificationException()
		} catch (_: JOSEException) {
			throw InvalidNotificationException()
		}
		if (claims.audience.none { it in clientIds }) throw InvalidNotificationException()
		// events 는 객체로 오기도 하고 JSON 글자로 오기도 한다.
		val events = try {
			when (val raw = claims.getClaim("events")) {
				is String -> mapper.readTree(raw)
				is Map<*, *> -> mapper.valueToTree(raw)
				else -> null
			}
		} catch (_: JacksonException) {
			null
		} ?: throw InvalidNotificationException()
		val type = events.path("type").asString(null)?.takeIf { it.isNotBlank() } ?: throw InvalidNotificationException()
		val subject = events.path("sub").asString(null)?.takeIf { it.isNotBlank() } ?: throw InvalidNotificationException()
		val eventTime = events.path("event_time").takeIf { it.isNumber }?.let { Instant.ofEpochSecond(it.asLong()) }
		return AppleEvent(type, subject, eventTime)
	}

	/** client_id · client_secret 이 든 폼. 키가 없으면 null(애플을 부르지 않는다). */
	private fun form(clientId: String): LinkedMultiValueMap<String, String>? {
		val signer = signer ?: run {
			log.warn("event=apple_not_configured")
			return null
		}
		val now = Instant.now()
		val secret = SignedJWT(
			JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(),
			JWTClaimsSet.Builder()
				.issuer(teamId)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plus(CLIENT_SECRET_TTL)))
				.audience(APPLE_ISSUER)
				.subject(clientId)
				.build(),
		).apply { sign(signer) }.serialize()
		return LinkedMultiValueMap<String, String>().apply {
			add("client_id", clientId)
			add("client_secret", secret)
		}
	}

	/** .p8 파일의 내용(PEM, PKCS#8). 환경 변수에 한 줄로 넣느라 줄바꿈이 `\n` 글자로 왔어도 읽는다. 없거나 틀리면 null. */
	private fun parsePrivateKey(pem: String): ECPrivateKey? {
		if (pem.isBlank()) return null
		val base64 = pem.replace("\\n", "\n")
			.lineSequence()
			.filterNot { it.startsWith("-----") }
			.joinToString("") { it.trim() }
		if (base64.isEmpty()) {
			// 값은 있는데 키 본문이 없다. .env 에 여러 줄로 넣으면 첫 줄만 값이 된다(.env.example 의 안내).
			log.warn("event=apple_private_key_invalid reason=Empty")
			return null
		}
		return try {
			KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))) as ECPrivateKey
		} catch (e: Exception) {
			log.warn("event=apple_private_key_invalid reason={}", e.javaClass.simpleName)
			null
		}
	}
}
