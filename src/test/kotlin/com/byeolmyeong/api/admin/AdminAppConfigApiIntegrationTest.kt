package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val PLATFORM_KEYS = setOf("minVersion", "storeUrl", "message", "blockNow", "updatedAt", "updatedBy")
private const val STORE = "https://apps.apple.com/kr/app/id0000000000"
private const val MESSAGE = "안내비밀글-새 버전으로 업데이트해 주세요"

/** 앱 설정 편집 `GET /admin/app-config` · `PUT /admin/app-config/{platform}`(결정 016): 모양·검증·캐시 비우기·감사 기록. */
@AdminIntegrationTest
class AdminAppConfigApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var events: ApplicationEventPublisher

	private lateinit var token: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		// 표를 직접 고쳤으므로 서버 메모리의 캐시도 비운다.
		events.publishEvent(AppConfigChanged())
		token = adminToken(port)
		jdbc.update("delete from audit_logs")
	}

	private fun body(minVersion: Any? = "1.2.0", storeUrl: Any? = STORE, message: Any? = MESSAGE, blockNow: Any? = false): Map<String, Any?> =
		mapOf("minVersion" to minVersion, "storeUrl" to storeUrl, "message" to message, "blockNow" to blockNow)

	private fun put(platform: String, body: Any?, token: String? = this.token): HttpResponse<String> =
		http(port, "PUT", "/admin/app-config/$platform", if (body is String) body else json(body), token)

	private fun get(): HttpResponse<String> = http(port, "GET", "/admin/app-config", token = token)

	private fun public(): String = http(port, "GET", "/app-config").body()

	/** 옛 버전의 iOS 앱이 부르는 요청. */
	private fun oldIos(path: String = "/places"): Int = http(port, "GET", path, null, null, "X-App-Platform", "ios", "X-App-Version", "1.0.0").statusCode()

	private fun assertError(status: Int, code: String, response: HttpResponse<String>, what: String = "") {
		assertEquals(status, response.statusCode(), what)
		assertEquals("""{"error":"$code"}""", response.body(), what)
	}

	@Test
	fun `처음에는 두 플랫폼 모두 비어 있다`() {
		val response = get()
		assertEquals(200, response.statusCode())
		val json = response.json()
		assertEquals(setOf("ios", "android"), json.propertyNames().toSet())
		for (platform in listOf("ios", "android")) {
			val config = json[platform]
			assertEquals(PLATFORM_KEYS, config.propertyNames().toSet())
			for (key in listOf("minVersion", "storeUrl", "message", "updatedBy")) assertTrue(config[key].isNull, "$platform.$key 는 키가 있고 null 이다")
			assertEquals(false, config["blockNow"].asBoolean())
			assertTrue(config["updatedAt"].asString().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))
		}
	}

	@Test
	fun `한 플랫폼의 네 값을 바꾸면 그 플랫폼만 바뀌고 누가 언제 바꿨는지 적힌다`() {
		val rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)
		val before = Instant.now().minusSeconds(2)

		val response = put("ios", body(blockNow = true))
		assertEquals(200, response.statusCode(), response.body())
		val ios = response.json()
		assertEquals(PLATFORM_KEYS, ios.propertyNames().toSet())
		assertEquals(listOf("1.2.0", STORE, MESSAGE), listOf("minVersion", "storeUrl", "message").map { ios[it].asString() })
		assertEquals(true, ios["blockNow"].asBoolean())
		assertEquals(listOf(rootId, ROOT_ID), listOf(ios["updatedBy"]["id"].asString(), ios["updatedBy"]["name"].asString()))
		assertEquals(setOf("id", "name"), ios["updatedBy"].propertyNames().toSet())
		assertTrue(Instant.parse(ios["updatedAt"].asString()).isAfter(before))

		val all = get().json()
		assertEquals(ios, all["ios"])
		assertTrue(all["android"]["minVersion"].isNull && all["android"]["updatedBy"].isNull, "다른 플랫폼은 그대로다")
		assertEquals(
			"""{"ios":{"minVersion":"1.2.0","storeUrl":"$STORE","message":"$MESSAGE","blockNow":true},"android":{"minVersion":null,"storeUrl":null,"message":null,"blockNow":false}}""",
			public(),
		)

		// 모두 비우는 것도 네 키를 다 보낸다.
		val cleared = put("ios", body(null, null, null, false))
		assertEquals(200, cleared.statusCode())
		for (key in listOf("minVersion", "storeUrl", "message")) assertTrue(cleared.json()[key].isNull, key)
		assertEquals(false, cleared.json()["blockNow"].asBoolean())
	}

	@Test
	fun `안내 글은 앞뒤 공백을 떼고 빈 글은 null 이 된다`() {
		assertEquals("가운데  공백은\n그대로", put("android", body(message = "  가운데  공백은\n그대로 \n")).json()["message"].asString())
		for (blank in listOf("", "   ", "\n\t ")) assertTrue(put("android", body(message = blank)).json()["message"].isNull)
		assertEquals(200, put("android", body(message = "가".repeat(200))).statusCode())
		assertEquals(200, put("android", body(message = "😀".repeat(200))).statusCode(), "글자 수는 코드 포인트로 센다")
		assertEquals(200, put("android", body(message = " " + "가".repeat(200) + " ")).statusCode(), "공백을 뗀 뒤에 센다")
		for (version in listOf("1", "1.2", "0.0.1", "10.20.30")) assertEquals(version, put("android", body(minVersion = version)).json()["minVersion"].asString())
		assertEquals(200, put("android", body(storeUrl = "https://play.google.com/store/apps/details?id=com.example.app&hl=ko")).statusCode())
		assertEquals(200, put("android", body(storeUrl = "https://a.co/" + "x".repeat(487))).statusCode(), "500자까지")
	}

	@Test
	fun `틀린 요청은 정해진 코드로 거절하고 아무것도 바꾸지 않는다`() {
		assertEquals(200, put("ios", body()).statusCode())
		val saved = get().body()

		for (platform in listOf("windows", "IOS", "web", "ios2", "all")) assertError(404, "NOT_FOUND", put(platform, body()), platform)

		// 본문이 객체가 아니거나, 네 키 가운데 하나라도 없거나, 타입이 틀리다.
		for (raw in listOf("[]", "\"x\"", "1", "null", "{", "")) assertError(400, "INVALID_REQUEST", put("ios", raw), raw)
		for (key in listOf("minVersion", "storeUrl", "message", "blockNow")) assertError(400, "INVALID_REQUEST", put("ios", body() - key), "$key 없음")
		assertError(400, "INVALID_REQUEST", put("ios", mapOf<String, Any?>()))
		for (bad in listOf(1, 1.2, true, listOf("1.2.0"), mapOf("v" to "1"))) {
			assertError(400, "INVALID_REQUEST", put("ios", body(minVersion = bad)), "minVersion=$bad")
			assertError(400, "INVALID_REQUEST", put("ios", body(storeUrl = bad)), "storeUrl=$bad")
			assertError(400, "INVALID_REQUEST", put("ios", body(message = bad)), "message=$bad")
		}
		for (bad in listOf(null, "true", "false", 0, 1, listOf(true), mapOf("on" to true))) assertError(400, "INVALID_REQUEST", put("ios", body(blockNow = bad)), "blockNow=$bad")

		for (bad in listOf("", " ", "1.2.3.4", "v1.2", "1.2-beta", " 1.2", "1.2 ", "1..2", "1.", "latest", "1,2", "1234567890")) {
			assertError(400, "INVALID_VERSION", put("ios", body(minVersion = bad)), "minVersion='$bad'")
		}
		val badUrls = listOf(
			"", " ", "http://apps.apple.com/app", "apps.apple.com/app", "//apps.apple.com", "https://", "https:///path", "itms-apps://apps.apple.com/app",
			"market://details?id=x", "javascript:alert(1)", "https://a b.com", "https://apps.apple.com/a b", " $STORE", "HTTPS://apps.apple.com/app\n",
			"https://a.co/" + "x".repeat(488),
		)
		for (bad in badUrls) assertError(400, "INVALID_STORE_URL", put("ios", body(storeUrl = bad)), "storeUrl='${bad.take(40)}'")
		assertError(400, "INVALID_MESSAGE", put("ios", body(message = "가".repeat(201))))
		assertError(400, "INVALID_MESSAGE", put("ios", body(message = "😀".repeat(201))))
		assertError(400, "BLOCK_REQUIRES_MIN_VERSION", put("ios", body(minVersion = null, blockNow = true)))

		// 여러 개가 틀리면 이 순서로 처음 걸린 것 하나다.
		assertError(400, "INVALID_REQUEST", put("ios", body("x", "x", "가".repeat(201), "true")))
		assertError(400, "INVALID_VERSION", put("ios", body("x", "x", "가".repeat(201), true)))
		assertError(400, "INVALID_STORE_URL", put("ios", body(null, "x", "가".repeat(201), true)))
		assertError(400, "INVALID_MESSAGE", put("ios", body(null, null, "가".repeat(201), true)))

		assertEquals(saved, get().body(), "거절된 요청은 아무것도 바꾸지 않는다")
		assertEquals(1, jdbc.queryForObject("select count(*) from audit_logs", Int::class.java), "거절된 요청은 감사 기록에도 없다")
	}

	@Test
	fun `관리자 세션이 있어야 한다`() {
		val (_, userToken) = userAccount(jdbc, "config-user")
		for (bad in listOf(null, userToken, "no-such-token")) {
			assertError(401, "UNAUTHORIZED", http(port, "GET", "/admin/app-config", token = bad))
			assertError(401, "UNAUTHORIZED", put("ios", body(), bad))
		}
		assertTrue(get().json()["ios"]["minVersion"].isNull)
	}

	@Test
	fun `바꾸면 캐시가 비워져 막기가 바로 켜지고 꺼진다`() {
		// 지금 값(막지 않음)을 서버 메모리에 읽혀 둔다.
		assertEquals(200, oldIos())
		assertTrue("\"blockNow\":false" in public())

		assertEquals(200, put("ios", body(minVersion = "2.0.0", blockNow = true)).statusCode())
		assertEquals(426, oldIos(), "캐시 시간(5초)을 기다리지 않고 바로 막는다")
		assertEquals(426, oldIos("/profile"))
		assertTrue("\"minVersion\":\"2.0.0\"" in public())
		assertEquals(200, http(port, "GET", "/places", null, null, "X-App-Platform", "android", "X-App-Version", "1.0.0").statusCode())
		assertEquals(200, http(port, "GET", "/places", null, null, "X-App-Platform", "ios", "X-App-Version", "2.0").statusCode())

		// 막힌 버전의 헤더를 달고도 관리자 API 는 열려 있어 되돌릴 수 있다.
		val undo = http(port, "PUT", "/admin/app-config/ios", json(body(minVersion = "2.0.0", blockNow = false)), token, "X-App-Platform", "ios", "X-App-Version", "1.0.0")
		assertEquals(200, undo.statusCode())
		assertEquals(200, oldIos(), "끄면 바로 풀린다")
	}

	@Test
	fun `바꿀 때마다 감사 기록에 플랫폼·최소 버전·막기만 남는다`() {
		val rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)
		assertEquals(200, put("ios", body(blockNow = true)).statusCode())

		val row = jdbc.queryForList("select actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail::text as detail from audit_logs").single()
		assertEquals(listOf("ADMIN", rootId, ROOT_ID, "APP_CONFIG_UPDATED", "APP_CONFIG", "ios", "SUCCESS"), listOf("actor_type", "actor_id", "actor_label", "action", "target_type", "target_id", "outcome").map { row[it] })
		assertEquals(mapper.readTree("""{"platform":"ios","minVersion":"1.2.0","blockNow":true}"""), mapper.readTree(row["detail"] as String))

		jdbc.update("delete from audit_logs")
		assertEquals(200, put("android", body(minVersion = null)).statusCode())
		val detail = jdbc.queryForObject("select detail::text from audit_logs", String::class.java)
		assertEquals(mapper.readTree("""{"platform":"android","blockNow":false}"""), mapper.readTree(detail))

		val dump = jdbc.queryForList("select row_to_json(a)::text from audit_logs a", String::class.java).joinToString("\n")
		for (secret in listOf(MESSAGE, "안내비밀글", STORE, "apps.apple.com", "https")) assertFalse(secret in dump, "감사 기록에 있으면 안 되는 값: $secret")

		val listed = http(port, "GET", "/admin/audit-logs?action=APP_CONFIG_UPDATED", token = token).json()
		assertEquals(1, listed["total"].asInt())
		assertEquals(listOf("APP_CONFIG", "android"), listOf(listed["items"][0]["targetType"].asString(), listed["items"][0]["targetId"].asString()))
	}
}
