package com.byeolmyeong.api.auth

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.http
import com.byeolmyeong.api.freshNickname
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
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GOOGLE_AUD = "test-google-client"
private const val KAKAO_AUD = "test-kakao-rest-key"
private const val KAKAO_REDIRECT = "http://localhost:8081/auth/kakao"
private const val PLACE = 1841277 // 서울(출생지 목록의 id)
/** 지금 약관 · 처리방침 버전(결정 026, application.properties 의 byeolmyeong.legal.*). */
private const val TERMS = "2026-11-19"
private val TEST_KEY: RSAKey = RSAKeyGenerator(2048).keyID("test").generate()
private val mapper = JsonMapper()

/** 공급자 대신 테스트 키로 서명한 ID 토큰. 이메일은 기본으로 `<sub>@example.com`(확인됨)이다(결정 024). */
private fun idToken(iss: String, aud: String, sub: String, key: RSAKey = TEST_KEY, email: String? = "$sub@example.com"): String =
	SignedJWT(
		JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
		JWTClaimsSet.Builder().issuer(iss).audience(aud).subject(sub)
			.apply { if (email != null) claim("email", email).claim("email_verified", true) }
			.issueTime(Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build(),
	).apply { sign(RSASSASigner(key)) }.serialize()

private fun google(sub: String, email: String? = "$sub@example.com") = idToken("https://accounts.google.com", GOOGLE_AUD, sub, email = email)

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

	/**
	 * 닉네임(결정 025) · 약관 · 처리방침 버전 · 프로필(결정 026)은 필수다. 값이 null 이면 그 칸을 보내지 않는다.
	 * [date] 는 `LocalDate` 나 틀린 날짜 글자다.
	 */
	private fun signup(
		ticket: String,
		date: Any,
		extra: String = "",
		nickname: String? = freshNickname(),
		terms: String? = TERMS,
		privacy: String? = TERMS,
		avatar: Int? = 3,
	): HttpResponse<String> {
		val fields = mapOf("nickname" to nickname, "termsVersion" to terms, "privacyVersion" to privacy, "avatar" to avatar)
			.filterValues { it != null }.entries.joinToString("") { (key, value) -> ",\"$key\":${mapper.writeValueAsString(value)}" }
		return http(port, "POST", "/auth/signup", """{"signupToken":"$ticket","date":"$date","time":"14:30","placeId":$PLACE$fields$extra}""")
	}

	private fun error(response: HttpResponse<String>) = response.statusCode() to response.body()

	private fun auditCount(action: String): Int =
		jdbc.queryForObject("select count(*) from audit_logs where action = ?", Int::class.java, action)!!

	private fun accountColumn(sub: String, column: String): Any? =
		jdbc.queryForList("select $column from accounts where provider = 'GOOGLE' and provider_user_id = ?", sub).single()[column]

	private fun adult() = koreaToday().minusYears(30)

	@Test
	fun `가입 때 닉네임이 없거나 틀리면 400 INVALID_NICKNAME 이고 가입권은 남는다(결정 025)`() {
		val ticket = signupToken("nick-bad")
		for (bad in listOf(null, "", "   ", "별", "가".repeat(11), "별이1", "별 이", "star 1", "별이!", "ㅋㅋ", "\uD83D\uDE00\uD83D\uDE00", "별\n이", "별\u0007이")) {
			val response = signup(ticket, adult(), nickname = bad)
			assertEquals(400, response.statusCode(), "닉네임 ${bad?.length}자")
			assertEquals("""{"error":"INVALID_NICKNAME"}""", response.body())
		}
		assertEquals(0, count("accounts where provider = 'GOOGLE'"), "계정을 만들지 않았다")
		assertEquals(201, signup(ticket, adult(), nickname = "별이").statusCode(), "같은 가입권으로 다시 보낼 수 있다")
	}

	@Test
	fun `닉네임은 한글 · 영문 2~10자이고 섞어 쓸 수 있으며 앞뒤 공백을 떼어 저장한다`() {
		assertEquals(201, signup(signupToken("nick-mix"), adult(), nickname = "Star별이").statusCode())
		assertEquals("Star별이", accountColumn("nick-mix", "nickname"))
		assertEquals(201, signup(signupToken("nick-ten"), adult(), nickname = "가".repeat(10)).statusCode())
		assertEquals(201, signup(signupToken("nick-trim"), adult(), nickname = "  지우  ").statusCode())
		assertEquals("지우", accountColumn("nick-trim", "nickname"))
	}

	@Test
	fun `이미 있는 닉네임이면 409 NICKNAME_TAKEN 이고 가입권은 남는다 — 영문은 대소문자를 가리지 않는다`() {
		assertEquals(201, signup(signupToken("dup-a"), adult(), nickname = "Star").statusCode())
		val ticket = signupToken("dup-b")
		val taken = signup(ticket, adult(), nickname = "star")
		assertEquals(409, taken.statusCode())
		assertEquals("""{"error":"NICKNAME_TAKEN"}""", taken.body())
		assertEquals(1, count("accounts where provider = 'GOOGLE'"), "두 번째 계정을 만들지 않았다")
		assertEquals(201, signup(ticket, adult(), nickname = "Moon").statusCode(), "같은 가입권으로 다른 닉네임을 보낼 수 있다")
	}

	@Test
	fun `가입 전에 닉네임을 쓸 수 있는지 묻는다 — 가입권이 있어야 하고 규칙에 어긋나면 400 이다`() {
		val ticket = signupToken("check")
		fun check(body: String) = http(port, "POST", "/auth/nickname-check", body)
		val free = check("""{"signupToken":"$ticket","nickname":"Star"}""")
		assertEquals(200, free.statusCode())
		assertEquals("""{"available":true}""", free.body())
		assertEquals(201, signup(signupToken("check-other"), adult(), nickname = "Star").statusCode())
		assertEquals("""{"available":false}""", check("""{"signupToken":"$ticket","nickname":"STAR"}""").body())
		assertEquals(400, check("""{"signupToken":"$ticket","nickname":"별1"}""").statusCode())
		assertEquals("""{"error":"SIGNUP_EXPIRED"}""", check("""{"signupToken":"no-such","nickname":"Star"}""").body())
		assertEquals(401, check("""{"nickname":"Star"}""").statusCode())
		jdbc.update("update signup_tickets set expires_at = now() - interval '1 minute'")
		assertEquals(401, check("""{"signupToken":"$ticket","nickname":"Moon"}""").statusCode(), "만료된 가입권")
	}

	@Test
	fun `가입 때 마케팅 동의를 켜면 일시가 남고, 끄거나 보내지 않으면 null 이다(결정 025)`() {
		assertEquals(201, signup(signupToken("mk-on"), adult(), extra = ",\"marketingConsent\":true").statusCode())
		assertNotNull(accountColumn("mk-on", "marketing_consented_at"))
		assertEquals(201, signup(signupToken("mk-off"), adult(), extra = ",\"marketingConsent\":false").statusCode())
		assertNull(accountColumn("mk-off", "marketing_consented_at"))
		assertEquals(201, signup(signupToken("mk-none"), adult()).statusCode())
		assertNull(accountColumn("mk-none", "marketing_consented_at"))
	}

	@Test
	fun `약관 버전이 없거나 지금 버전과 다르면 400 TERMS_REQUIRED 이고 가입권은 남는다 — 같은 가입권으로 다시 보내면 201(결정 026)`() {
		val ticket = signupToken("terms-bad")
		for ((terms, privacy) in listOf(null to null, null to TERMS, TERMS to null, "2026-01-01" to TERMS, TERMS to "2026-01-01", "" to "", " $TERMS" to TERMS)) {
			val response = signup(ticket, adult(), terms = terms, privacy = privacy)
			assertEquals(400 to """{"error":"TERMS_REQUIRED"}""", error(response), "terms=$terms privacy=$privacy")
		}
		assertEquals(0, count("accounts"), "계정을 만들지 않았다")
		assertEquals(1, count("signup_tickets"), "가입권을 쓰지 않았다")
		assertEquals(201, signup(ticket, adult()).statusCode(), "같은 가입권으로 다시 보낼 수 있다")
	}

	@Test
	fun `지금 약관 버전이면 두 버전과 동의 시각(서버 시각)이 계정에 남는다 — 앱이 보낸 시각은 받지 않는다`() {
		val before = Instant.now().minusMillis(1)
		val response = signup(signupToken("terms-ok"), adult(), extra = ""","termsAgreedAt":"2000-01-01T00:00:00Z","privacyAgreedAt":"2000-01-01T00:00:00Z"""")
		val after = Instant.now().plusMillis(1)

		assertEquals(201, response.statusCode(), response.body())
		assertEquals(TERMS, accountColumn("terms-ok", "terms_version"))
		assertEquals(TERMS, accountColumn("terms-ok", "privacy_version"))
		val termsAt = (accountColumn("terms-ok", "terms_agreed_at") as java.sql.Timestamp).toInstant()
		assertTrue(termsAt.isAfter(before) && termsAt.isBefore(after), "서버 시각: $termsAt")
		assertEquals(termsAt, (accountColumn("terms-ok", "privacy_agreed_at") as java.sql.Timestamp).toInstant(), "두 동의는 같은 때다")
	}

	@Test
	fun `가입 때 푸시 동의를 켜면 일시가 남고, 끄거나 보내지 않으면 null 이다(결정 026)`() {
		assertEquals(201, signup(signupToken("push-on"), adult(), extra = ",\"pushConsent\":true").statusCode())
		assertNotNull(accountColumn("push-on", "push_consented_at"))
		assertEquals(201, signup(signupToken("push-off"), adult(), extra = ",\"pushConsent\":false").statusCode())
		assertNull(accountColumn("push-off", "push_consented_at"))
		assertEquals(201, signup(signupToken("push-none"), adult()).statusCode())
		assertNull(accountColumn("push-none", "push_consented_at"))
	}

	@Test
	fun `프로필이 없거나 1~8 밖이면 400 INVALID_AVATAR 이고 가입권은 남는다 — 맞으면 번호를 둔다(결정 026)`() {
		val ticket = signupToken("avatar-bad")
		for (bad in listOf(null, 0, 9, -1)) {
			assertEquals(400 to """{"error":"INVALID_AVATAR"}""", error(signup(ticket, adult(), avatar = bad)), "avatar=$bad")
		}
		assertEquals(0, count("accounts"), "계정을 만들지 않았다")
		assertEquals(201, signup(ticket, adult(), avatar = 8).statusCode(), "같은 가입권으로 다시 보낼 수 있다")
		assertEquals(8, (accountColumn("avatar-bad", "avatar") as Number).toInt())
		assertEquals(201, signup(signupToken("avatar-one"), adult(), avatar = 1).statusCode())
		assertEquals(1, (accountColumn("avatar-one", "avatar") as Number).toInt())
	}

	@Test
	fun `판정 순서는 가입권 → 약관 → 닉네임 → 프로필 → 출생 정보 → 만 14세다(결정 026)`() {
		val ticket = signupToken("order")
		val kid = koreaToday().minusYears(10)
		assertEquals(401 to """{"error":"SIGNUP_EXPIRED"}""", error(signup("no-such", kid, nickname = null, terms = null, avatar = null)))
		assertEquals(400 to """{"error":"TERMS_REQUIRED"}""", error(signup(ticket, kid, nickname = null, terms = null, avatar = null)))
		assertEquals(400 to """{"error":"INVALID_NICKNAME"}""", error(signup(ticket, "1990-13-45", nickname = null, avatar = null)))
		assertEquals(400 to """{"error":"INVALID_AVATAR"}""", error(signup(ticket, "1990-13-45", avatar = null)))
		assertEquals(400 to """{"error":"INVALID_DATE"}""", error(signup(ticket, "1990-13-45")))
		assertEquals(1, count("signup_tickets"), "여기까지는 가입권을 쓰지 않았다")
		assertEquals(403 to """{"error":"UNDER_AGE"}""", error(signup(ticket, kid)))
		assertEquals(0, count("signup_tickets"))
	}

	@Test
	fun `남은 가입권으로 다시 와도 이미 있는 계정의 약관 시각 · 푸시 동의 · 프로필은 그대로이고 TERMS_AGREED 는 한 번이다`() {
		val tickets = List(2) { signupToken("keep-terms") }
		assertEquals(201, signup(tickets[0], adult(), avatar = 2).statusCode())
		val columns = "terms_version, terms_agreed_at, privacy_version, privacy_agreed_at, push_consented_at, avatar"
		val saved = jdbc.queryForMap("select $columns from accounts")
		assertEquals(TERMS, saved["terms_version"], "첫 가입이 약관을 남겼다")
		// 감사 기록은 테스트 사이에 지우지 않으므로(결정 015) 앞뒤 개수를 비교한다.
		val agreedBefore = auditCount("TERMS_AGREED")

		assertEquals(201, signup(tickets[1], adult(), avatar = 7, extra = ",\"pushConsent\":true").statusCode(), "세션만 준다")
		assertEquals(saved, jdbc.queryForMap("select $columns from accounts"))
		assertEquals(agreedBefore, auditCount("TERMS_AGREED"))
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `가입하면 감사 기록 TERMS_AGREED 에 두 버전만 남고, 감사 기록 · 로그에 이메일 · 닉네임이 없다`(output: CapturedOutput) {
		val nickname = freshNickname()
		assertEquals(201, signup(signupToken("terms-audit"), adult(), nickname = nickname).statusCode())

		val owner = accountColumn("terms-audit", "id").toString()
		val details = jdbc.queryForList("select detail::text from audit_logs where action = 'TERMS_AGREED' and actor_id = ?", String::class.java, owner)
		assertEquals(1, details.size)
		assertEquals(json("""{"termsVersion":"$TERMS","privacyVersion":"$TERMS"}"""), json(details.single()!!))
		val all = jdbc.queryForList("select coalesce(detail::text, '') from audit_logs where actor_id = ?", String::class.java, owner).joinToString()
		assertFalse(all.contains(nickname))
		assertFalse(all.contains("terms-audit@example.com"))
		assertFalse(output.all.contains(nickname))
		assertFalse(output.all.contains("terms-audit@example.com"))
	}

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
		val bad = http(port, "POST", "/auth/signup", """{"signupToken":"$ticket","date":"1990-13-45","time":null,"placeId":null,"nickname":"${freshNickname()}","termsVersion":"$TERMS","privacyVersion":"$TERMS","avatar":1}""")
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
	fun `남은 가입권으로 다시 와도 이미 있는 계정의 닉네임은 그대로이고, 자기 닉네임을 보내도 409 가 아니다`() {
		val tickets = List(3) { signupToken("keep-nick") }
		assertEquals(201, signup(tickets[0], adult(), nickname = "Star").statusCode())
		assertEquals(201, signup(tickets[1], adult(), nickname = "Moon").statusCode(), "세션만 준다")
		assertEquals("Star", accountColumn("keep-nick", "nickname"))
		assertEquals(201, signup(tickets[2], adult(), nickname = "Star").statusCode())
	}

	@Test
	fun `남은 가입권으로 다시 가입해도 이미 있는 계정의 출생 정보를 바꾸지 않는다`() {
		val first = signupToken("again")
		val leftover = signupToken("again")
		assertEquals(201, signup(first, adult()).statusCode())
		// 감사 기록은 테스트 사이에 지우지 않으므로(결정 015) 앞뒤 개수를 비교한다.
		val saved = { jdbc.queryForObject("select count(*) from audit_logs where action = 'PROFILE_SAVED'", Int::class.java) }
		val savedBefore = saved()

		val second = signup(leftover, adult().minusYears(5))
		assertEquals(201, second.statusCode(), "세션은 준다")
		assertEquals(adult(), jdbc.queryForObject("select birth_date from profiles", java.sql.Date::class.java)!!.toLocalDate())
		assertEquals(savedBefore, saved())
	}

	@Test
	fun `가입하면 토큰의 이메일을 받은 그대로 계정에 둔다`() {
		val ticket = json(login("""{"provider":"GOOGLE","idToken":"${google("mail1", email = "Mail1@Example.com")}"}""").body())["signupToken"].asString()
		assertEquals(201, signup(ticket, adult()).statusCode())
		assertEquals("Mail1@Example.com", jdbc.queryForObject("select email from accounts", String::class.java))
	}

	@Test
	fun `처음 온 사람의 토큰에 이메일이 없으면 400 EMAIL_REQUIRED 이고 가입권을 만들지 않는다`() {
		val response = login("""{"provider":"GOOGLE","idToken":"${google("nomail", email = null)}"}""")
		assertEquals(400, response.statusCode())
		assertEquals("""{"error":"EMAIL_REQUIRED"}""", response.body())
		assertEquals(0, count("signup_tickets"))
		assertEquals(0, count("accounts"))
	}

	@Test
	fun `이미 있는 계정은 토큰에 이메일이 없어도 로그인되고, 있으면 새 이메일로 고쳐 둔다`() {
		signup(signupToken("keep"), adult())
		val noMail = login("""{"provider":"GOOGLE","idToken":"${google("keep", email = null)}"}""")
		assertEquals("SIGNED_IN", json(noMail.body())["status"].asString())
		assertEquals("keep@example.com", jdbc.queryForObject("select email from accounts", String::class.java))

		login("""{"provider":"GOOGLE","idToken":"${google("keep", email = "new@example.com")}"}""")
		assertEquals("new@example.com", jdbc.queryForObject("select email from accounts", String::class.java))
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `이메일은 응답과 로그에 남기지 않는다`(output: CapturedOutput) {
		val response = login("""{"provider":"GOOGLE","idToken":"${google("secret", email = "secret-mail@example.com")}"}""")
		val created = signup(json(response.body())["signupToken"].asString(), adult())
		assertFalse(response.body().contains("secret-mail"))
		assertFalse(created.body().contains("secret-mail"))
		assertFalse(output.all.contains("secret-mail@example.com"))
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
	fun `탈퇴하면 약관 · 푸시 동의 · 프로필 칸이 계정과 함께 지워지고 감사 기록 TERMS_AGREED 는 남는다(결정 026 · 015)`() {
		val created = signup(signupToken("bye-terms"), adult(), extra = ",\"pushConsent\":true")
		assertEquals(201, created.statusCode())
		val owner = accountColumn("bye-terms", "id").toString()
		val agreed = { jdbc.queryForObject("select count(*) from audit_logs where action = 'TERMS_AGREED' and actor_id = ?", Int::class.java, owner) }
		assertEquals(1, agreed())

		assertEquals(204, http(port, "DELETE", "/account", token = json(created.body())["token"].asString()).statusCode())

		val left = jdbc.queryForObject(
			"""
			select count(*) from accounts where id = ?::uuid or terms_version is not null or terms_agreed_at is not null
			or privacy_version is not null or privacy_agreed_at is not null or push_consented_at is not null or avatar is not null
			""".trimIndent(),
			Int::class.java, owner,
		)
		assertEquals(0, left, "그 계정의 칸이 남지 않는다")
		assertEquals(1, agreed(), "감사 기록은 계정과 함께 지워지지 않는다")
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
