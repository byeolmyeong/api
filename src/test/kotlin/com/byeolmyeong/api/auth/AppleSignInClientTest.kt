package com.byeolmyeong.api.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEAM = "TEAM123456"
private const val KEY_ID = "KEY1234567"
private const val BUNDLE = "com.byeolmyeong.app"

// 애플 토큰 교환 · 철회 · 서버 알림(결정 021). 애플 대신 이 테스트의 HTTP 서버가 답한다.
class AppleSignInClientTest {

	private val signingKey: ECKey = ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate()
	private val pem = "-----BEGIN PRIVATE KEY-----\n" +
		Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(signingKey.toECPrivateKey().encoded) +
		"\n-----END PRIVATE KEY-----\n"
	private val appleKey: RSAKey = RSAKeyGenerator(2048).keyID("apple").generate()

	private lateinit var server: HttpServer
	private var status = 200
	private var responseBody = """{"access_token":"a","token_type":"Bearer","expires_in":3600,"refresh_token":"the-refresh","id_token":"x"}"""
	private var lastPath = ""
	private var lastForm: Map<String, String> = emptyMap()
	private val calls = AtomicInteger()

	@BeforeEach
	fun start() {
		server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
		server.createContext("/") { exchange ->
			calls.incrementAndGet()
			lastPath = exchange.requestURI.path
			lastForm = exchange.requestBody.readAllBytes().decodeToString().split("&").filter { it.isNotEmpty() }.associate {
				val (k, v) = it.split("=", limit = 2)
				URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
			}
			val bytes = responseBody.toByteArray()
			exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stop() = server.stop(0)

	private fun client(privateKey: String = pem, keys: JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(appleKey.toPublicJWK()))) =
		AppleSignInClient(TEAM, KEY_ID, privateKey, listOf(BUNDLE, "com.byeolmyeong.web"), "http://localhost:${server.address.port}", keys)

	private fun assertClientSecret(secret: String, clientId: String) {
		val jwt = SignedJWT.parse(secret)
		assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
		assertEquals(KEY_ID, jwt.header.keyID)
		assertTrue(jwt.verify(ECDSAVerifier(signingKey.toPublicJWK())), "우리 키로 서명하지 않았다")
		val claims = jwt.jwtClaimsSet
		assertEquals(TEAM, claims.issuer)
		assertEquals(clientId, claims.subject)
		assertEquals(listOf("https://appleid.apple.com"), claims.audience)
		assertTrue(claims.expirationTime.toInstant().isAfter(Instant.now()))
	}

	@Test
	fun `authorization code 를 refresh token 으로 바꾼다`() {
		assertEquals("the-refresh", client().exchange("the-code", BUNDLE, null))
		assertEquals("/auth/token", lastPath)
		assertEquals(setOf("client_id", "client_secret", "code", "grant_type"), lastForm.keys)
		assertEquals(BUNDLE, lastForm["client_id"])
		assertEquals("the-code", lastForm["code"])
		assertEquals("authorization_code", lastForm["grant_type"])
		assertClientSecret(lastForm.getValue("client_secret"), BUNDLE)
	}

	@Test
	fun `웹은 로그인에 쓴 돌아올 주소를 함께 보낸다`() {
		client().exchange("the-code", "com.byeolmyeong.web", "https://byeolmyeong.com/auth/apple")
		assertEquals("https://byeolmyeong.com/auth/apple", lastForm["redirect_uri"])
		assertClientSecret(lastForm.getValue("client_secret"), "com.byeolmyeong.web")
	}

	@Test
	fun `교환에 실패하면 null 이다`() {
		status = 400
		responseBody = """{"error":"invalid_grant"}"""
		assertNull(client().exchange("used-code", BUNDLE, null))
		status = 500
		assertNull(client().exchange("the-code", BUNDLE, null))
		status = 200
		responseBody = """{"access_token":"a"}"""
		assertNull(client().exchange("the-code", BUNDLE, null))
	}

	@Test
	fun `키를 설정하지 않았으면 애플을 부르지 않는다`() {
		assertNull(client(privateKey = "").exchange("the-code", BUNDLE, null))
		assertFalse(client(privateKey = "").revoke("r", BUNDLE))
		assertEquals(0, calls.get())
	}

	@Test
	fun `refresh token 을 철회한다`() {
		responseBody = ""
		assertTrue(client().revoke("the-refresh", BUNDLE))
		assertEquals("/auth/revoke", lastPath)
		assertEquals("the-refresh", lastForm["token"])
		assertEquals("refresh_token", lastForm["token_type_hint"])
		assertEquals(BUNDLE, lastForm["client_id"])
		assertClientSecret(lastForm.getValue("client_secret"), BUNDLE)
		status = 500
		assertFalse(client().revoke("the-refresh", BUNDLE))
	}

	private fun notification(events: Any, aud: String = BUNDLE, key: RSAKey = appleKey): String =
		SignedJWT(
			JWSHeader.Builder(JWSAlgorithm.RS256).keyID("apple").build(),
			JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience(aud)
				.issueTime(Date()).jwtID("j1").claim("events", events).build(),
		).apply { sign(RSASSASigner(key)) }.serialize()

	@Test
	fun `서버 알림을 확인하고 종류 · 사용자 · 일어난 때를 꺼낸다`() {
		val asObject = mapOf("type" to "consent-revoked", "sub" to "apple-user", "event_time" to 1508184845)
		assertEquals(
			AppleEvent("consent-revoked", "apple-user", Instant.ofEpochSecond(1508184845)),
			client().notification(notification(asObject)),
		)
		// 실제 알림에는 events 가 JSON 글자로 오는 경우가 있다. event_time 이 없으면 null 이다.
		val asString = """{"type":"account-deleted","sub":"apple-user"}"""
		assertEquals(AppleEvent("account-deleted", "apple-user", null), client().notification(notification(asString)))
	}

	@Test
	fun `키 다시 받기 한도에 걸린 알림은 502 가 아니라 400 이다`() {
		val limited = JWKSource<SecurityContext> { _, _ -> throw RateLimitReachedException() }
		assertFailsWith<InvalidNotificationException> {
			client(keys = limited).notification(notification(mapOf("type" to "consent-revoked", "sub" to "apple-user")))
		}
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `키 값이 있는데 읽지 못하면 로그로 알리고, 철회를 못 하면 이유를 남긴다`(output: CapturedOutput) {
		// .env 에 여러 줄로 넣으면 첫 줄만 값이 된다.
		val firstLineOnly = client(privateKey = "-----BEGIN PRIVATE KEY-----")
		assertTrue(output.all.contains("event=apple_private_key_invalid"), "읽지 못한 키를 알리지 않았다")
		assertFalse(firstLineOnly.revoke("r", BUNDLE))
		assertTrue(output.all.contains("event=apple_revoke_failed reason=NotConfigured"))
	}

	@Test
	fun `서명 · 대상 · 모양이 틀린 알림은 받지 않는다`() {
		val events = mapOf("type" to "consent-revoked", "sub" to "apple-user")
		assertFailsWith<InvalidNotificationException> { client().notification(notification(events, aud = "someone-else")) }
		assertFailsWith<InvalidNotificationException> { client().notification(notification(events, key = RSAKeyGenerator(2048).keyID("apple").generate())) }
		assertFailsWith<InvalidNotificationException> { client().notification(notification(mapOf("type" to "consent-revoked"))) }
		assertFailsWith<InvalidNotificationException> { client().notification("not-a-jwt") }
	}
}
