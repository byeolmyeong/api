package com.byeolmyeong.api.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jose.util.DefaultResourceRetriever
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.text.ParseException

/** 로그인 공급자. DEV 는 개발용 로그인(결정 005). */
enum class Provider { DEV, GOOGLE, APPLE, KAKAO }

/**
 * 공급자가 확인해 준 사용자. 계정에는 공급자 · 식별자 · 이메일을 둔다(결정 024 — 이름은 받지 않는다).
 * [audience] 는 토큰이 발급된 우리 클라이언트 ID(애플은 iOS 번들 ID 와 웹 Services ID 가 다르고, 토큰 교환·철회 때 같은 값을 써야 한다).
 * [email] 은 토큰의 `email`(구글은 `email_verified` 가 참일 때만)을 다듬은 값이고, 없거나 모양이 틀리면 null 이다. 로그에 남기지 않는다.
 */
data class ProviderIdentity(
	val provider: Provider,
	val subject: String,
	val audience: String? = null,
	val email: String? = null,
	/** 토큰의 `jti`(웹 구글 토큰 한 번만 — 결정 047). 저장 · 로그에 남기지 않는다. */
	val tokenId: String? = null,
	/** 토큰이 끝나는 때(`exp`). */
	val expiresAt: java.time.Instant? = null,
	/** 토큰을 발급받은 클라이언트(`azp`). 없으면 null. */
	val authorizedParty: String? = null,
)

/** 공급자 ID 토큰이 틀렸다(서명·발급자·대상·만료·모양). 401 `INVALID_ID_TOKEN`. 토큰은 메시지에 넣지 않는다. */
class InvalidIdTokenException : RuntimeException("INVALID_ID_TOKEN")

/** 공급자의 키나 토큰 경로에 닿지 못했다. 502 `PROVIDER_UNAVAILABLE`. */
class ProviderUnavailableException(cause: Throwable? = null) : RuntimeException("PROVIDER_UNAVAILABLE", cause)

/** 공급자 ID 토큰 확인. 공급자의 키를 받아 오는 외부 호출이라 인터페이스 뒤에 둔다(architecture "의존 규칙"). */
interface ProviderTokenVerifier {
	val provider: Provider

	/** 서명(공급자 JWKS, RS256)·발급자·대상·만료를 확인한다. 틀리면 [InvalidIdTokenException], 키를 못 받으면 [ProviderUnavailableException]. */
	fun verify(idToken: String): ProviderIdentity
}

/**
 * 공급자별 발급자(iss)와 키 주소. 공급자의 OpenID 설정 문서(`/.well-known/openid-configuration`)에서 확인했다(2026-11-18, 결정 021).
 * 구글은 `https://` 없는 발급자도 쓴다(구글 ID 토큰 검증 문서).
 */
private val ISSUERS = mapOf(
	Provider.GOOGLE to setOf("https://accounts.google.com", "accounts.google.com"),
	Provider.APPLE to setOf("https://appleid.apple.com"),
	Provider.KAKAO to setOf("https://kauth.kakao.com"),
)
private const val GOOGLE_KEYS = "https://www.googleapis.com/oauth2/v3/certs"
private const val APPLE_KEYS = "https://appleid.apple.com/auth/keys"
private const val KAKAO_KEYS = "https://kauth.kakao.com/.well-known/jwks.json"

/** 공급자가 준 이메일을 받은 그대로 돌려준다(공백 · 대소문자를 바꾸지 않는다). 비었거나 320자를 넘거나 `@` 가 하나가 아니거나 그 앞뒤가 비면 null. */
fun normalizeEmail(raw: String?): String? {
	if (raw.isNullOrBlank()) return null
	val parts = raw.split('@')
	if (raw.length > 320 || parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
	return raw
}

/** [provider] 의 발급자로 확인하는 검증기. [audiences] 는 우리 앱의 클라이언트 ID 목록이고 빈 값은 버린다(비면 아무것도 통과하지 않는다). */
fun providerTokenVerifier(provider: Provider, audiences: List<String>, keys: JWKSource<SecurityContext>): ProviderTokenVerifier =
	JwksTokenVerifier(provider, ISSUERS.getValue(provider), audiences.map { it.trim() }.filter { it.isNotEmpty() }.toSet(), keys)

private class JwksTokenVerifier(
	override val provider: Provider,
	private val issuers: Set<String>,
	private val audiences: Set<String>,
	keys: JWKSource<SecurityContext>,
) : ProviderTokenVerifier {

	private val processor = DefaultJWTProcessor<SecurityContext>().apply {
		jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
		// 만료(exp)는 여기서 본다(시계 차이 60초까지). 발급자·대상은 아래에서 목록으로 본다.
		jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(JWTClaimsSet.Builder().build(), setOf("iss", "aud", "sub", "exp"))
	}

	override fun verify(idToken: String): ProviderIdentity {
		val claims = try {
			processor.process(idToken, null)
		} catch (_: RateLimitReachedException) {
			// 모르는 kid 로 키를 다시 받으려다 한도(30초에 한 번)에 걸렸다. 공급자가 아니라 토큰이 틀린 것으로 본다.
			throw InvalidIdTokenException()
		} catch (e: KeySourceException) {
			throw ProviderUnavailableException(e)
		} catch (_: ParseException) {
			throw InvalidIdTokenException()
		} catch (_: BadJOSEException) {
			throw InvalidIdTokenException()
		} catch (_: JOSEException) {
			throw InvalidIdTokenException()
		}
		if (claims.issuer !in issuers) throw InvalidIdTokenException()
		val audience = claims.audience.firstOrNull { it in audiences } ?: throw InvalidIdTokenException()
		// 대상이 여럿이면 누구에게 발급했는지(azp)가 우리여야 한다(OpenID Connect Core 3.1.3.7).
		if (claims.audience.size > 1 && claims.getStringClaim("azp") !in audiences) throw InvalidIdTokenException()
		val subject = claims.subject?.takeIf { it.isNotBlank() } ?: throw InvalidIdTokenException()
		return ProviderIdentity(
			provider, subject, audience, emailOf(claims),
			tokenId = claims.jwtid?.takeIf { it.isNotBlank() },
			expiresAt = claims.expirationTime?.toInstant(),
			// azp 가 문자열이 아니면 없는 것으로 본다(대상이 하나일 때는 위에서 보지 않는다).
			authorizedParty = runCatching { claims.getStringClaim("azp") }.getOrNull(),
		)
	}

	/**
	 * 구글 · 애플은 확인된 이메일만 받는다(`email_verified` 가 참 — 애플은 문자열 `"true"` 로도 온다).
	 * 카카오 ID 토큰에는 확인 여부 클레임이 없어 공급자가 준 값으로 본다(결정 024).
	 */
	private fun emailOf(claims: JWTClaimsSet): String? {
		if (provider != Provider.KAKAO && !isTrue(claims.getClaim("email_verified"))) return null
		return normalizeEmail(claims.getClaim("email") as? String)
	}

	private fun isTrue(value: Any?): Boolean = value == true || value == "true"
}

/**
 * 공급자 셋의 검증기. 키는 처음 쓸 때 받아 오고 캐시한다(카카오 문서: 공개키는 캐시하고 자주 받지 않는다).
 * 키 출처는 빈으로 따로 둬서 테스트가 메모리 키로 바꿔 끼운다.
 */
@Configuration
class ProviderTokenConfig {

	@Bean
	fun googleKeys(): JWKSource<SecurityContext> = remote(GOOGLE_KEYS)

	@Bean
	fun appleKeys(): JWKSource<SecurityContext> = remote(APPLE_KEYS)

	@Bean
	fun kakaoKeys(): JWKSource<SecurityContext> = remote(KAKAO_KEYS)

	@Bean
	fun googleTokenVerifier(
		@Value("\${byeolmyeong.auth.google.client-ids}") audiences: List<String>,
		@Qualifier("googleKeys") keys: JWKSource<SecurityContext>,
	): ProviderTokenVerifier = providerTokenVerifier(Provider.GOOGLE, audiences, keys)

	@Bean
	fun appleTokenVerifier(
		@Value("\${byeolmyeong.auth.apple.client-ids}") audiences: List<String>,
		@Qualifier("appleKeys") keys: JWKSource<SecurityContext>,
	): ProviderTokenVerifier = providerTokenVerifier(Provider.APPLE, audiences, keys)

	@Bean
	fun kakaoTokenVerifier(
		@Value("\${byeolmyeong.auth.kakao.client-ids}") audiences: List<String>,
		@Qualifier("kakaoKeys") keys: JWKSource<SecurityContext>,
	): ProviderTokenVerifier = providerTokenVerifier(Provider.KAKAO, audiences, keys)

	// 기본 타임아웃(0.5초)은 짧아 502 가 잦을 수 있다. 연결 2초 · 읽기 3초, 크기 한도는 기본(50KB).
	private fun remote(url: String): JWKSource<SecurityContext> =
		JWKSourceBuilder.create<SecurityContext>(URI(url).toURL(), DefaultResourceRetriever(2_000, 3_000, 50 * 1024)).build()
}
