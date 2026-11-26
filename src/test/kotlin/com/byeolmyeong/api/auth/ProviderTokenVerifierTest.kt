package com.byeolmyeong.api.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val KID = "test-key"
private const val AUD = "our-client-id"

// 공급자 ID 토큰 확인(결정 021). 공급자의 키 대신 메모리 키로 서명한 토큰을 쓴다.
class ProviderTokenVerifierTest {

	private val key: RSAKey = RSAKeyGenerator(2048).keyID(KID).generate()
	private val keys: JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(key.toPublicJWK()))

	private fun token(
		iss: String,
		aud: List<String> = listOf(AUD),
		azp: String? = null,
		sub: String? = "provider-user-1",
		expiresAt: Instant = Instant.now().plusSeconds(600),
		signingKey: RSAKey = key,
		email: String? = null,
		emailVerified: Any? = null,
	): String {
		val claims = JWTClaimsSet.Builder()
			.issuer(iss)
			.audience(aud)
			.apply { if (azp != null) claim("azp", azp) }
			.apply { if (sub != null) subject(sub) }
			.apply { if (email != null) claim("email", email) }
			.apply { if (emailVerified != null) claim("email_verified", emailVerified) }
			.issueTime(Date.from(Instant.now().minusSeconds(10)))
			.expirationTime(Date.from(expiresAt))
			.build()
		return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims)
			.apply { sign(RSASSASigner(signingKey)) }
			.serialize()
	}

	private fun verifier(provider: Provider, source: JWKSource<SecurityContext> = keys) =
		providerTokenVerifier(provider, listOf(AUD), source)

	@Test
	fun `공급자마다 자기 발급자의 올바른 토큰을 통과시키고 공급자와 식별자를 돌려준다`() {
		for ((provider, iss) in listOf(
			Provider.GOOGLE to "https://accounts.google.com",
			Provider.GOOGLE to "accounts.google.com",
			Provider.APPLE to "https://appleid.apple.com",
			Provider.KAKAO to "https://kauth.kakao.com",
		)) {
			// 끝나는 때는 아래 테스트가 본다(결정 047 — 웹 구글 토큰 한 번만).
			assertEquals(ProviderIdentity(provider, "provider-user-1", AUD), verifier(provider).verify(token(iss)).copy(expiresAt = null), "$provider $iss")
		}
	}

	@Test
	fun `토큰의 jti · azp · 끝나는 때를 함께 돌려준다(결정 047)`() {
		val exp = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
		val identity = verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", azp = AUD, expiresAt = exp))
		assertEquals(AUD, identity.authorizedParty)
		assertEquals(exp, identity.expiresAt)
		assertEquals(null, identity.tokenId, "jti 가 없는 토큰은 null")
	}

	@Test
	fun `토큰의 이메일을 받은 그대로 꺼낸다 — 구글 · 애플은 확인된 이메일만`() {
		assertEquals("User@Example.COM", verifier(Provider.KAKAO).verify(token("https://kauth.kakao.com", email = "User@Example.COM")).email)
		val apple = "https://appleid.apple.com"
		assertEquals("abc@privaterelay.appleid.com", verifier(Provider.APPLE).verify(token(apple, email = "abc@privaterelay.appleid.com", emailVerified = true)).email)
		assertEquals("Abc@Example.com", verifier(Provider.APPLE).verify(token(apple, email = "Abc@Example.com", emailVerified = "true")).email)
		assertEquals(null, verifier(Provider.APPLE).verify(token(apple, email = "abc@example.com", emailVerified = false)).email)
		assertEquals(null, verifier(Provider.APPLE).verify(token(apple, email = "abc@example.com", emailVerified = "false")).email)
		assertEquals(null, verifier(Provider.APPLE).verify(token(apple, email = "abc@example.com")).email)
		assertEquals("g@gmail.com", verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", email = "g@gmail.com", emailVerified = true)).email)
		assertEquals(null, verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", email = "g@gmail.com", emailVerified = false)).email)
		assertEquals(null, verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", email = "g@gmail.com")).email)
		assertEquals(null, verifier(Provider.KAKAO).verify(token("https://kauth.kakao.com")).email)
		for (bad in listOf("", "   ", "no-at-sign", "a@b@c", "@example.com", "user@", "x".repeat(310) + "@example.com")) {
			assertEquals(null, verifier(Provider.KAKAO).verify(token("https://kauth.kakao.com", email = bad)).email, bad)
		}
	}

	@Test
	fun `다른 공급자의 발급자는 받지 않는다`() {
		assertFailsWith<InvalidIdTokenException> { verifier(Provider.KAKAO).verify(token("https://accounts.google.com")) }
		assertFailsWith<InvalidIdTokenException> { verifier(Provider.APPLE).verify(token("https://evil.example")) }
	}

	@Test
	fun `서명이 틀리면 401 이다`() {
		val other = RSAKeyGenerator(2048).keyID(KID).generate()
		assertFailsWith<InvalidIdTokenException> {
			verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", signingKey = other))
		}
	}

	@Test
	fun `만료된 토큰은 401 이다`() {
		assertFailsWith<InvalidIdTokenException> {
			verifier(Provider.APPLE).verify(token("https://appleid.apple.com", expiresAt = Instant.now().minusSeconds(3600)))
		}
	}

	@Test
	fun `대상이 우리 클라이언트 ID 가 아니면 401 이다`() {
		assertFailsWith<InvalidIdTokenException> {
			verifier(Provider.KAKAO).verify(token("https://kauth.kakao.com", aud = listOf("someone-else")))
		}
	}

	@Test
	fun `대상 목록이 비어 있으면 어떤 토큰도 받지 않는다`() {
		val empty = providerTokenVerifier(Provider.GOOGLE, listOf("", " "), keys)
		assertFailsWith<InvalidIdTokenException> { empty.verify(token("https://accounts.google.com")) }
	}

	@Test
	fun `식별자가 없거나 모양이 JWT 가 아니면 401 이다`() {
		assertFailsWith<InvalidIdTokenException> { verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", sub = null)) }
		assertFailsWith<InvalidIdTokenException> { verifier(Provider.GOOGLE).verify("not-a-jwt") }
	}

	@Test
	fun `대상이 여럿이면 azp 가 우리 클라이언트여야 한다`() {
		assertFailsWith<InvalidIdTokenException> {
			verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", aud = listOf("someone-else", AUD)))
		}
		assertFailsWith<InvalidIdTokenException> {
			verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", aud = listOf("someone-else", AUD), azp = "someone-else"))
		}
		val ok = verifier(Provider.GOOGLE).verify(token("https://accounts.google.com", aud = listOf("someone-else", AUD), azp = AUD))
		assertEquals(AUD, ok.audience)
	}

	@Test
	fun `처음 보는 키로 서명해 키 다시 받기 한도에 걸려도 502 가 아니라 401 이다`() {
		// 실제 키 출처(JWKSourceBuilder)는 30초에 한 번만 다시 받고, 그 사이 모르는 kid 에는 RateLimitReachedException 을 던진다.
		val limited = JWKSource<SecurityContext> { _, _ -> throw RateLimitReachedException() }
		assertFailsWith<InvalidIdTokenException> { verifier(Provider.GOOGLE, limited).verify(token("https://accounts.google.com")) }
	}

	@Test
	fun `공급자 키를 받지 못하면 502 로 알린다`() {
		val down = JWKSource<SecurityContext> { _, _ -> throw KeySourceException("down") }
		assertFailsWith<ProviderUnavailableException> {
			verifier(Provider.KAKAO, down).verify(token("https://kauth.kakao.com"))
		}
	}
}
