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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.convention.TestBean
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

private const val BUNDLE = "com.byeolmyeong.app"
private val APPLE_TEST_KEY: RSAKey = RSAKeyGenerator(2048).keyID("apple").generate()

private fun appleIdToken(sub: String): String = SignedJWT(
	JWSHeader.Builder(JWSAlgorithm.RS256).keyID("apple").build(),
	JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience(BUNDLE).subject(sub)
		.claim("email", "$sub@privaterelay.appleid.com")
		.issueTime(Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build(),
).apply { sign(RSASSASigner(APPLE_TEST_KEY)) }.serialize()

// 애플 계정의 토큰 보관 · 탈퇴 때 철회 · 서버 알림(결정 021). 애플 서버는 가짜로 바꿔 끼운다.
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	// 한 주소(localhost)에서 로그인을 많이 부른다. 한도는 AuthRateLimitIntegrationTest 가 본다.
	properties = ["byeolmyeong.auth.apple.client-ids=$BUNDLE", "byeolmyeong.rate-limit.auth-per-minute=10000"],
)
class AppleAccountIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@TestBean(name = "appleKeys", methodName = "testKeys")
	private lateinit var appleKeys: JWKSource<SecurityContext>

	@TestBean(methodName = "fakeApple")
	private lateinit var apple: AppleSignIn

	companion object {
		val revoked = CopyOnWriteArrayList<Pair<String, String>>()
		var revokeResult = true
		var revokeThrows = false

		@JvmStatic
		fun testKeys(): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(APPLE_TEST_KEY.toPublicJWK()))

		/** 코드 "good…" 은 "refresh-<코드>" 로 바꿔 준다. 알림의 payload 는 "종류|사용자" 다(진짜 확인은 AppleSignInClientTest). */
		@JvmStatic
		fun fakeApple(): AppleSignIn = object : AppleSignIn {
			override fun exchange(code: String, clientId: String, redirectUri: String?) =
				if (code.startsWith("good") && clientId == BUNDLE) "refresh-$code" else null

			override fun revoke(refreshToken: String, clientId: String): Boolean {
				revoked += refreshToken to clientId
				if (revokeThrows) throw IllegalStateException("서명 실패 같은 예상 밖 오류")
				return revokeResult
			}

			/** "종류|사용자" 또는 "종류|사용자|일어난 때(초)" */
			override fun notification(payload: String): AppleEvent {
				val parts = payload.split("|")
				if (parts.size !in 2..3) throw InvalidNotificationException()
				return AppleEvent(parts[0], parts[1], parts.getOrNull(2)?.let { Instant.ofEpochSecond(it.toLong()) })
			}
		}
	}

	private val mapper = JsonMapper()

	@BeforeEach
	fun clean() {
		jdbc.update("delete from signup_tickets")
		jdbc.update("delete from accounts")
		revoked.clear()
		revokeResult = true
		revokeThrows = false
	}

	private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

	private fun refreshToken(): String? = jdbc.queryForList("select refresh_token from apple_tokens", String::class.java).firstOrNull()

	private fun login(sub: String, code: String?) = http(
		port, "POST", "/auth/login",
		"""{"provider":"APPLE","idToken":"${appleIdToken(sub)}"${code?.let { ""","authorizationCode":"$it"""" } ?: ""}}""",
	).also { assertEquals(200, it.statusCode(), it.body()) }

	private fun signUp(sub: String, code: String?, date: String = "1995-05-05"): Int {
		val ticket = mapper.readTree(login(sub, code).body())["signupToken"].asString()
		return http(port, "POST", "/auth/signup", """{"signupToken":"$ticket","date":"$date","time":null,"placeId":null}""").statusCode()
	}

	private fun sessionToken(sub: String, code: String? = null): String = mapper.readTree(login(sub, code).body())["token"].asString()

	@Test
	fun `가입하면 로그인 때 바꾼 refresh token 을 계정에 둔다`() {
		assertEquals(201, signUp("a1", "good1"))
		assertEquals("refresh-good1", refreshToken())
		assertEquals(BUNDLE, jdbc.queryForObject("select client_id from apple_tokens", String::class.java))
		assertEquals(0, count("signup_tickets"))
	}

	@Test
	fun `다시 로그인하면 새 refresh token 으로 바꿔 둔다`() {
		signUp("a2", "good1")
		sessionToken("a2", "good2")
		assertEquals("refresh-good2", refreshToken())
	}

	@Test
	fun `코드를 바꾸지 못해도 로그인과 가입은 된다`() {
		assertEquals(201, signUp("a3", "bad"))
		assertEquals(null, refreshToken())
		assertEquals(201, signUp("a4", null))
	}

	@Test
	fun `탈퇴하면 refresh token 을 철회하고 실패해도 계정은 지운다`() {
		signUp("a5", "good1")
		revokeResult = false

		assertEquals(204, http(port, "DELETE", "/account", token = sessionToken("a5")).statusCode())

		assertEquals(listOf("refresh-good1" to BUNDLE), revoked.toList())
		assertEquals(0, count("accounts"))
		assertEquals(0, count("apple_tokens"))
	}

	@Test
	fun `철회가 예상 밖 오류로 터져도 계정은 지운다`() {
		signUp("a9", "good1")
		revokeThrows = true
		assertEquals(204, http(port, "DELETE", "/account", token = sessionToken("a9")).statusCode())
		assertEquals(0, count("accounts"))
	}

	@Test
	fun `만 14세 미만으로 가입을 못 하면 받아 둔 애플 토큰도 철회한다`() {
		assertEquals(403, signUp("kid", "good1", date = Instant.now().toString().take(10)))
		assertEquals(listOf("refresh-good1" to BUNDLE), revoked.toList())
		assertEquals(0, count("signup_tickets"))
	}

	private fun notify(payload: String) = http(port, "POST", "/auth/apple/notifications", """{"payload":"$payload"}""")

	@Test
	fun `애플 사용 중단 알림이면 그 계정의 세션을 모두 지우고 계정은 남긴다`() {
		signUp("a6", null)
		val token = sessionToken("a6")

		assertEquals(200, notify("consent-revoked|a6").statusCode())

		assertEquals(401, http(port, "GET", "/auth/session", token = token).statusCode())
		assertEquals(1, count("accounts"))
	}

	@Test
	fun `사용 중단 알림이 늦게 다시 와도 그 뒤에 로그인한 세션은 남긴다`() {
		signUp("a10", null)
		val before = sessionToken("a10")
		jdbc.update("update sessions set created_at = now() - interval '2 hours'")
		val after = sessionToken("a10")
		val oneHourAgo = Instant.now().minusSeconds(3600).epochSecond

		assertEquals(200, notify("consent-revoked|a10|$oneHourAgo").statusCode())

		assertEquals(401, http(port, "GET", "/auth/session", token = before).statusCode(), "알림보다 먼저 생긴 세션은 지운다")
		assertEquals(204, http(port, "GET", "/auth/session", token = after).statusCode(), "알림 뒤에 생긴 세션은 남긴다")
	}

	@Test
	fun `애플 계정 삭제 알림이면 계정을 지운다 — 같은 알림이 또 와도 200 이다`() {
		signUp("a7", "good1")
		assertEquals(200, notify("account-deleted|a7").statusCode())
		assertEquals(0, count("accounts"))
		assertEquals(
			"SYSTEM",
			jdbc.queryForObject("select actor_type from audit_logs where action = 'ACCOUNT_DELETED' order by occurred_at desc limit 1", String::class.java),
			"사용자가 아니라 서버가 지운 것이다",
		)
		assertEquals(200, notify("account-deleted|a7").statusCode())
		assertEquals(200, notify("account-delete|nobody").statusCode())
	}

	@Test
	fun `모르는 알림 종류와 모르는 사용자는 200 으로 받고 아무것도 하지 않는다`() {
		signUp("a8", null)
		assertEquals(200, notify("email-disabled|a8").statusCode())
		assertEquals(200, notify("consent-revoked|nobody").statusCode())
		assertEquals(1, count("accounts"))
	}

	@Test
	fun `애플 알림이 아니면 400 INVALID_NOTIFICATION 이다`() {
		val response = notify("garbage")
		assertEquals(400, response.statusCode())
		assertEquals("""{"error":"INVALID_NOTIFICATION"}""", response.body())
		assertEquals(400, http(port, "POST", "/auth/apple/notifications", "{}").statusCode())
	}
}
