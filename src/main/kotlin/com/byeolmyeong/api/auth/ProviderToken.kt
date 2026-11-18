package com.byeolmyeong.api.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.text.ParseException

/** 로그인 공급자. DEV 는 개발용 로그인(결정 005). */
enum class Provider { DEV, GOOGLE, APPLE, KAKAO }

/**
 * 공급자가 확인해 준 사용자. 계정에는 공급자와 식별자만 둔다(V2 — 이메일·이름은 받지 않는다).
 * [audience] 는 토큰이 발급된 우리 클라이언트 ID(애플은 iOS 번들 ID 와 웹 Services ID 가 다르고, 토큰 교환·철회 때 같은 값을 써야 한다).
 */
data class ProviderIdentity(val provider: Provider, val subject: String, val audience: String? = null)

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
		val subject = claims.subject?.takeIf { it.isNotBlank() } ?: throw InvalidIdTokenException()
		return ProviderIdentity(provider, subject, audience)
	}
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

	private fun remote(url: String): JWKSource<SecurityContext> = JWKSourceBuilder.create<SecurityContext>(URI(url).toURL()).build()
}
