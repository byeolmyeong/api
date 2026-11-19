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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GOOGLE_AUD = "test-google-client"
private const val KAKAO_AUD = "test-kakao-rest-key"
private const val KAKAO_REDIRECT = "http://localhost:8081/auth/kakao"
private const val PLACE = 1841277 // 서울(출생지 목록의 id)
private val TEST_KEY: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()
private val mapper = JsonMapper()

/** 공급자 대신 테스트 키로 서명한 ID 토큰. */
private fun idToken(iss: String, aud: String, sub: String, key: RSAKey = TEST_KEY): String =
	SignedJWT(
		JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
		JWTClaimsSet.Builder().issuer(iss).audience(aud).subject(sub)
			.issueTime(Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build(),
	).apply { sign(RSASSASigner(key)) }.serialize()

private fun google(sub: String) = idToken("https://accounts.google.com", GOOGLE_AUD, sub)

// 소셜 로그인과 가입(결정 021). 공급자 키는 테스트 키로, 카카오 서버는 가짜로 바꿔 끼운다.
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		"byeolmyeong.auth.google.client-ids=$GOOGLE_AUD",
		"byeolmyeong.auth.kakao.client-ids=$KAKAO_AUD",
		// 한 주소(localhost)에서 로그인을 많이 부른다. 한도는 AuthRateLimitIntegrationTest 가 본다.
		"byeolmyeong.rate-limit.auth-per-minute=10000",
	],
)
class SocialLoginIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@TestBean(name = "googleKeys", methodName = "testKeys")
	private lateinit var googleKeys: JWKSource<SecurityContext>

	@TestBean(name = "kakaoKeys", methodName = "testKeys")
	private lateinit var kakaoKeys: JWKSource<SecurityContext>

	@TestBean(methodName = "fakeKakao")
	private lateinit var kakao: KakaoOAuth

	companion object {
		val unlinked = java.util.concurrent.CopyOnWriteArrayList<String>()
		var unlinkResult = true

		@JvmStatic
		fun testKeys(): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(TEST_KEY.toPublicJWK()))

		/** 코드 "good-<sub>" 만 ID 토큰으로 바꿔 준다. 돌아올 주소는 하나만 받는다. */
		@JvmStatic
		fun fakeKakao(): KakaoOAuth = object : KakaoOAuth {
			override fun exchange(code: String, redirectUri: String): String {
				if (redirectUri != KAKAO_REDIRECT || !code.startsWith("good-")) throw InvalidCodeException()
				return idToken("https://kauth.kakao.com", KAKAO_AUD, code.removePrefix("good-"))
			}

			override fun unlink(userId: String): Boolean {
				unlinked += userId
				return unlinkResult
			}
		}
	}

	@BeforeEach
	fun clean() {
		jdbc.update("delete from signup_tickets")
		jdbc.update("delete from accounts")
		unlinked.clear()
		unlinkResult = true
	}

	private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

	private fun json(body: String): JsonNode = mapper.readTree(body)

	private fun login(body: String) = http(port, "POST", "/auth/login", body)

	private fun signupToken(sub: String): String {
		val response = login("""{"provider":"GOOGLE","idToken":"${google(sub)}"}""")
		assertEquals(200, response.statusCode(), response.body())
		val node = json(response.body())
		assertEquals("SIGNUP_REQUIRED", node["status"].asString())
		return node["signupToken"].asString()
	}

	private fun signup(ticket: String, date: LocalDate, extra: String = "") =
		http(port, "POST", "/auth/signup", """{"signupToken":"$ticket","date":"$date","time":"14:30","placeId":$PLACE$extra}""")

	private fun adult() = koreaToday().minusYears(30)

	@Test
	fun `처음 온 사람은 계정 대신 가입권을 받는다`() {
		val response = login("""{"provider":"GOOGLE","idToken":"${google("new-user")}"}""")
		val node = json(response.body())
		assertEquals("SIGNUP_REQUIRED", node["status"].asString())
		assertTrue(node["signupToken"].asString().length >= 32)
		assertTrue(Instant.parse(node["expiresAt"].asString()).isAfter(Instant.now().plusSeconds(25 * 60)))
		assertEquals(0, count("accounts"))
		assertEquals(1, count("signup_tickets"))
	}

	@Test
	fun `가입권과 출생 정보로 가입하면 계정과 출생 정보와 세션이 함께 생기고 가입권은 사라진다`() {
		val response = signup(signupToken("u1"), adult())

		assertEquals(201, response.statusCode(), response.body())
		val token = json(response.body())["token"].asString()
		assertEquals(1, count("accounts"))
		assertEquals(1, count("profiles"))
		assertEquals(0, count("signup_tickets"))
		val profile = http(port, "GET", "/profile", token = token)
		assertEquals(200, profile.statusCode())
		assertEquals(adult().toString(), json(profile.body())["date"].asString())
		assertEquals("GOOGLE", jdbc.queryForObject("select provider from accounts", String::class.java))
	}

	@Test
	fun `가입한 사람이 다시 로그인하면 세션을 받고 계정은 하나다`() {
		signup(signupToken("u2"), adult())

		val again = login("""{"provider":"GOOGLE","idToken":"${google("u2")}"}""")
		val node = json(again.body())
		assertEquals("SIGNED_IN", node["status"].asString())
		assertEquals(204, http(port, "GET", "/auth/session", token = node["token"].asString()).statusCode())
		assertEquals(1, count("accounts"))
	}

	@Test
	fun `만 14세 미만이면 403 이고 계정도 출생 정보도 가입권도 남지 않는다`() {
		val response = signup(signupToken("kid"), koreaToday().minusYears(10))

		assertEquals(403, response.statusCode())
		assertEquals("""{"error":"UNDER_AGE"}""", response.body())
		assertEquals(0, count("accounts"))
		assertEquals(0, count("profiles"))
		assertEquals(0, count("signup_tickets"))
	}

	@Test
	fun `14번째 생일 당일은 가입되고 하루 모자라면 403 이다`() {
		assertEquals(403, signup(signupToken("almost"), koreaToday().minusYears(14).plusDays(1)).statusCode())
		assertEquals(201, signup(signupToken("birthday"), koreaToday().minusYears(14)).statusCode())
	}

	@Test
	fun `가입권은 한 번만 쓴다`() {
		val ticket = signupToken("u3")
		assertEquals(201, signup(ticket, adult()).statusCode())

		val second = signup(ticket, adult())
		assertEquals(401, second.statusCode())
		assertEquals("""{"error":"SIGNUP_EXPIRED"}""", second.body())
	}

	@Test
	fun `만료된 가입권은 401 이다`() {
		val ticket = signupToken("u4")
		jdbc.update("update signup_tickets set expires_at = now() - interval '1 minute'")
		assertEquals(401, signup(ticket, adult()).statusCode())
		assertEquals(401, signup("no-such-ticket", adult()).statusCode())
	}

	@Test
	fun `출생 정보가 틀리면 400 이고 가입권은 다시 쓸 수 있다`() {
		val ticket = signupToken("u5")
		val bad = http(port, "POST", "/auth/signup", """{"signupToken":"$ticket","date":"1990-13-45","time":null,"placeId":null}""")
		assertEquals(400, bad.statusCode())
		assertEquals("""{"error":"INVALID_DATE"}""", bad.body())
		assertEquals(201, signup(ticket, adult()).statusCode())
	}

	@Test
	fun `같은 사람이 가입권 두 장으로 동시에 가입해도 계정과 출생 정보는 하나다`() {
		val tickets = listOf(signupToken("twin"), signupToken("twin"))
		val pool = Executors.newFixedThreadPool(2)
		val codes = pool.invokeAll(tickets.map { t -> Callable { signup(t, adult()).statusCode() } }).map { it.get() }
		pool.shutdown()

		assertEquals(listOf(201, 201), codes)
		assertEquals(1, count("accounts"))
		assertEquals(1, count("profiles"))
	}

	@Test
	fun `남은 가입권으로 다시 가입해도 이미 있는 계정의 출생 정보를 바꾸지 않는다`() {
		val first = signupToken("again")
		val leftover = signupToken("again")
		assertEquals(201, signup(first, adult()).statusCode())

		val second = signup(leftover, adult().minusYears(5))
		assertEquals(201, second.statusCode(), "세션은 준다")
		assertEquals(adult(), jdbc.queryForObject("select birth_date from profiles", java.sql.Date::class.java)!!.toLocalDate())
		assertEquals(1, jdbc.queryForObject("select count(*) from audit_logs where action = 'PROFILE_SAVED'", Int::class.java))
	}

	@Test
	fun `서명이 틀리거나 대상이 다른 ID 토큰은 401 이다`() {
		val other = RSAKeyGenerator(2048).keyID("test").generate()
		for (token in listOf(
			idToken("https://accounts.google.com", GOOGLE_AUD, "x", other),
			idToken("https://accounts.google.com", "someone-else", "x"),
		)) {
			val response = login("""{"provider":"GOOGLE","idToken":"$token"}""")
			assertEquals(401, response.statusCode())
			assertEquals("""{"error":"INVALID_ID_TOKEN"}""", response.body())
		}
		assertEquals(0, count("signup_tickets"))
	}

	@Test
	fun `요청 모양이 틀리면 400 INVALID_REQUEST 다`() {
		for (body in listOf(
			"""{"idToken":"x"}""",
			"""{"provider":"DEV","idToken":"x"}""",
			"""{"provider":"NAVER","idToken":"x"}""",
			"""{"provider":"GOOGLE"}""",
			"""{"provider":"GOOGLE","idToken":"x","code":"c","redirectUri":"$KAKAO_REDIRECT"}""",
			"""{"provider":"GOOGLE","code":"good-a","redirectUri":"$KAKAO_REDIRECT"}""",
			"""{"provider":"KAKAO","code":"good-a"}""",
			"[]",
		)) {
			val response = login(body)
			assertEquals(400, response.statusCode(), body)
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body(), body)
		}
	}

	@Test
	fun `카카오 웹은 인가 코드로 로그인하고 틀린 코드는 401 INVALID_CODE 다`() {
		val ok = login("""{"provider":"KAKAO","code":"good-k1","redirectUri":"$KAKAO_REDIRECT"}""")
		assertEquals("SIGNUP_REQUIRED", json(ok.body())["status"].asString())
		assertEquals(201, signup(json(ok.body())["signupToken"].asString(), adult()).statusCode())
		assertEquals("KAKAO", jdbc.queryForObject("select provider from accounts", String::class.java))

		val native = login("""{"provider":"KAKAO","idToken":"${idToken("https://kauth.kakao.com", KAKAO_AUD, "k1")}"}""")
		assertEquals("SIGNED_IN", json(native.body())["status"].asString(), "앱(ID 토큰)과 웹(코드)은 같은 카카오 계정이다")

		val bad = login("""{"provider":"KAKAO","code":"bad","redirectUri":"$KAKAO_REDIRECT"}""")
		assertEquals(401, bad.statusCode())
		assertEquals("""{"error":"INVALID_CODE"}""", bad.body())
	}

	@Test
	fun `카카오 계정을 지우면 카카오 연결을 끊고 실패해도 계정은 지운다`() {
		val ticket = json(login("""{"provider":"KAKAO","code":"good-k9","redirectUri":"$KAKAO_REDIRECT"}""").body())["signupToken"].asString()
		val token = json(signup(ticket, adult()).body())["token"].asString()
		unlinkResult = false

		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		assertEquals(listOf("k9"), unlinked.toList())
		assertEquals(0, count("accounts"))
	}

	@Test
	fun `구글 계정을 지울 때는 카카오를 부르지 않는다`() {
		val token = json(signup(signupToken("g9"), adult()).body())["token"].asString()
		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())
		assertEquals(emptyList(), unlinked.toList())
	}

	@Test
	fun `cookie 를 달라고 하면 가입과 로그인 모두 토큰을 쿠키로만 준다`() {
		val created = signup(signupToken("web"), adult(), extra = ""","cookie":true""")
		assertEquals(201, created.statusCode())
		assertEquals("{}", created.body())
		assertTrue(created.headers().allValues("Set-Cookie").any { it.startsWith("bm_session=") })

		val again = login("""{"provider":"GOOGLE","idToken":"${google("web")}","cookie":true}""")
		assertEquals("""{"status":"SIGNED_IN"}""", again.body())
		assertTrue(again.headers().allValues("Set-Cookie").any { it.startsWith("bm_session=") })
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `공급자 토큰과 가입권은 로그에 남기지 않는다`(output: CapturedOutput) {
		val idToken = google("quiet")
		val ticket = json(login("""{"provider":"GOOGLE","idToken":"$idToken"}""").body())["signupToken"].asString()
		signup(ticket, adult())
		assertFalse(output.all.contains(idToken))
		assertFalse(output.all.contains(ticket))
		assertFalse(jdbc.queryForList("select token_hash from signup_tickets", String::class.java).contains(ticket))
	}
}
