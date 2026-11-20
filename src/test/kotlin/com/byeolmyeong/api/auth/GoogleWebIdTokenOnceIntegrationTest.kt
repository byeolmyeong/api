package com.byeolmyeong.api.auth

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.http
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.convention.TestBean
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val WEB = "test-google-web-client"
private const val IOS = "test-google-ios-client"
private val KEY: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()

/** 구글이 준 것처럼 테스트 키로 서명한 ID 토큰. [azp] 가 null 이면 넣지 않는다. */
private fun googleToken(aud: String, azp: String? = aud, jti: String? = UUID.randomUUID().toString(), sub: String = "google-user"): String =
	SignedJWT(
		JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
		JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(aud).subject(sub)
			.claim("email", "$sub@example.com").claim("email_verified", true)
			.apply { if (azp != null) claim("azp", azp) }
			.apply { if (jti != null) jwtID(jti) }
			.issueTime(Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build(),
	).apply { sign(RSASSASigner(KEY)) }.serialize()

/** 웹 클라이언트가 받은 구글 ID 토큰은 만료까지 한 번만(결정 047). iOS · 안드로이드 토큰은 그대로. */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		"byeolmyeong.auth.google.client-ids=$WEB,$IOS",
		"byeolmyeong.auth.google.web-client-id=$WEB",
		"byeolmyeong.rate-limit.auth-per-minute=10000",
	],
)
class GoogleWebIdTokenOnceIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var once: IdTokenOnceService

	@TestBean(name = "googleKeys", methodName = "testKeys")
	private lateinit var googleKeys: JWKSource<SecurityContext>

	companion object {
		@JvmStatic
		fun testKeys(): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(KEY.toPublicJWK()))
	}

	@BeforeEach
	fun clean() {
		jdbc.update("delete from used_id_tokens")
		jdbc.update("delete from signup_tickets")
		jdbc.update("delete from accounts")
	}

	private fun login(token: String): Int = http(port, "POST", "/auth/login", """{"provider":"GOOGLE","idToken":"$token"}""").statusCode()

	@Test
	fun `웹 토큰은 처음엔 되고 같은 토큰을 다시 보내면 401 이다`() {
		val token = googleToken(WEB)

		assertEquals(200, login(token))
		val again = http(port, "POST", "/auth/login", """{"provider":"GOOGLE","idToken":"$token"}""")
		assertEquals(401, again.statusCode())
		assertEquals("""{"error":"INVALID_ID_TOKEN"}""", again.body())
		assertEquals(200, login(googleToken(WEB)), "새 토큰(다른 jti)은 된다")
	}

	@Test
	fun `azp 가 없으면 대상(aud)으로 웹인지 가린다`() {
		val token = googleToken(WEB, azp = null)

		assertEquals(200, login(token))
		assertEquals(401, login(token))
	}

	@Test
	fun `iOS · 안드로이드 토큰은 같은 토큰을 다시 보내도 된다 — SDK 가 저장본을 다시 준다`() {
		val token = googleToken(IOS)

		assertEquals(200, login(token))
		assertEquals(200, login(token))
		assertEquals(0, jdbc.queryForObject("select count(*) from used_id_tokens", Int::class.java))
	}

	@Test
	fun `앱 토큰(대상은 웹 · 발급받은 쪽은 앱)은 같은 토큰을 다시 보내도 된다 — 앱은 serverClientId 로 웹 대상 토큰을 받는다`() {
		val token = googleToken(WEB, azp = IOS)

		assertEquals(200, login(token))
		assertEquals(200, login(token))
		assertEquals(0, jdbc.queryForObject("select count(*) from used_id_tokens", Int::class.java))
	}

	@Test
	fun `jti 가 없거나 비어 있는 웹 토큰은 401 이다`() {
		assertEquals(401, login(googleToken(WEB, jti = null)))
		assertEquals(401, login(googleToken(WEB, jti = "")))
		assertEquals(401, login(googleToken(WEB, jti = " ")))
	}

	@Test
	fun `서명이 틀린 토큰은 줄을 만들지 않는다`() {
		val other = RSAKeyGenerator(2048).keyID("test").generate()
		val forged = SignedJWT(
			JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
			JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(WEB).claim("azp", WEB).subject("x").jwtID("forged-jti")
				.expirationTime(Date.from(Instant.now().plusSeconds(600))).build(),
		).apply { sign(RSASSASigner(other)) }.serialize()

		assertEquals(401, login(forged))
		assertEquals(0, jdbc.queryForObject("select count(*) from used_id_tokens", Int::class.java))
	}

	@Test
	fun `끝난 줄은 날마다 지운다`() {
		jdbc.update(
			"insert into used_id_tokens (jti, expires_at) values ('old', now() - interval '1 hour'), ('live', now() + interval '1 hour'), " +
				"('skew', now() - interval '30 seconds')",
		)

		once.deleteExpired()

		// 확인은 exp 뒤 60초(시계 차이)까지 받으므로 그 안의 줄은 남긴다.
		assertEquals(setOf("live", "skew"), jdbc.queryForList("select jti from used_id_tokens", String::class.java).toSet())
	}

	@Test
	fun `토큰과 jti 는 로그에 남지 않는다`(output: CapturedOutput) {
		val jti = "secret-jti-${UUID.randomUUID()}"
		val token = googleToken(WEB, jti = jti)
		login(token)
		login(token)

		assertFalse(jti in output.all)
		assertFalse(token.substringAfterLast('.') in output.all)
	}
}
