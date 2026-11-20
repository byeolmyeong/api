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

private val PLATFORM_KEYS = setOf("minVersion", "latestVersion", "storeUrl", "message", "blockNow", "updatedAt", "updatedBy")
private val MAINTENANCE_KEYS = setOf("active", "message", "updatedAt", "updatedBy", "startedAt", "startedBy")
private const val STORE = "https://apps.apple.com/kr/app/id0000000000"
private const val MESSAGE = "안내비밀글-새 버전으로 업데이트해 주세요"

/** 앱 설정 편집 `GET /admin/app-config` · `PUT /admin/app-config/{platform}`(결정 016 · 040): 모양·검증·캐시 비우기·감사 기록. */
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

	/** 옛 관리 화면이 보내는 네 키(`latestVersion` 없음). */
	private fun body(minVersion: Any? = "1.2.0", storeUrl: Any? = STORE, message: Any? = MESSAGE, blockNow: Any? = false): Map<String, Any?> =
		mapOf("minVersion" to minVersion, "storeUrl" to storeUrl, "message" to message, "blockNow" to blockNow)

	/** 새 관리 화면이 보내는 다섯 키(결정 040). */
	private fun body5(latestVersion: Any?, minVersion: Any? = "1.2.0", blockNow: Any? = false): Map<String, Any?> =
		body(minVersion = minVersion, blockNow = blockNow) + ("latestVersion" to latestVersion)

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
		assertEquals(setOf("ios", "android", "maintenance"), json.propertyNames().toSet())
		val maintenance = json["maintenance"]
		assertEquals(MAINTENANCE_KEYS, maintenance.propertyNames().toSet())
		assertEquals(false, maintenance["active"].asBoolean())
		assertTrue(maintenance["message"].isNull && maintenance["updatedBy"].isNull)
		assertTrue(maintenance["startedAt"].isNull && maintenance["startedBy"].isNull)
		assertTrue(maintenance["updatedAt"].asString().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))
		for (platform in listOf("ios", "android")) {
			val config = json[platform]
			assertEquals(PLATFORM_KEYS, config.propertyNames().toSet())
			for (key in listOf("minVersion", "latestVersion", "storeUrl", "message", "updatedBy")) assertTrue(config[key].isNull, "$platform.$key 는 키가 있고 null 이다")
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
			"""{"ios":{"minVersion":"1.2.0","latestVersion":null,"storeUrl":"$STORE","message":"$MESSAGE","blockNow":true},"android":{"minVersion":null,"latestVersion":null,"storeUrl":null,"message":null,"blockNow":false},"maintenance":{"active":false,"message":null}}""",
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
	fun `최신 버전을 넣고 지우며 키가 없으면 그대로 둔다`() {
		val saved = put("ios", body5("1.4.0"))
		assertEquals(200, saved.statusCode(), saved.body())
		assertEquals("1.4.0", saved.json()["latestVersion"].asString())
		assertTrue("\"latestVersion\":\"1.4.0\"" in public(), "앱의 공개 조회에도 온다")
		assertTrue(get().json()["android"]["latestVersion"].isNull, "다른 플랫폼은 그대로")

		val kept = put("ios", body(message = "다른 값만 고침"))
		assertEquals(200, kept.statusCode())
		assertEquals("1.4.0", kept.json()["latestVersion"].asString(), "키가 없으면 그대로 둔다(옛 관리 화면)")

		assertEquals("1.2", put("ios", body5("1.2", minVersion = "1.2.0")).json()["latestVersion"].asString(), "최소 버전과 같으면 된다 · 보낸 글자 그대로")
		assertEquals("2.0.0", put("ios", body5("2.0.0", minVersion = null)).json()["latestVersion"].asString(), "최소 버전이 없으면 견주지 않는다")
		assertTrue(put("ios", body5(null)).json()["latestVersion"].isNull, "null 을 보내야 지워진다")
		assertTrue("\"latestVersion\":null" in public())
	}

	@Test
	fun `틀린 최신 버전과 최소 버전보다 낮은 최신 버전은 거절한다`() {
		assertEquals(200, put("ios", body5("1.4.0")).statusCode())
		val saved = get().body()

		for (bad in listOf(1, 1.4, true, listOf("1.4.0"), mapOf("v" to "1"))) assertError(400, "INVALID_REQUEST", put("ios", body5(bad)), "latestVersion=$bad")
		for (bad in listOf("", " ", "1.2.3.4", "v1.4", "1.4-beta", " 1.4", "1..4", "latest", "1234567890")) {
			assertError(400, "INVALID_LATEST_VERSION", put("ios", body5(bad)), "latestVersion='$bad'")
		}
		assertError(400, "LATEST_BELOW_MIN", put("ios", body5("1.1.9", minVersion = "1.2.0")))
		assertError(400, "LATEST_BELOW_MIN", put("ios", body5("1.9", minVersion = "1.10")), "숫자로 견준다")
		assertError(400, "LATEST_BELOW_MIN", put("ios", body(minVersion = "1.5.0")), "키가 없으면 저장된 최신 버전(1.4.0)과 견준다")
		assertError(400, "LATEST_BELOW_MIN", put("ios", body5("1.1.0", minVersion = "1.2.0", blockNow = true)))

		// 여러 개가 틀리면: INVALID_VERSION → INVALID_LATEST_VERSION → INVALID_STORE_URL
		assertError(400, "INVALID_VERSION", put("ios", body5("x", minVersion = "y")))
		assertError(400, "INVALID_LATEST_VERSION", put("ios", body5("x") + ("storeUrl" to "http://x")))

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

		jdbc.update("delete from audit_logs")
		assertEquals(200, put("android", body5("1.4.0")).statusCode())
		val withLatest = jdbc.queryForObject("select detail::text from audit_logs", String::class.java)
		assertEquals(mapper.readTree("""{"platform":"android","minVersion":"1.2.0","latestVersion":"1.4.0","blockNow":false}"""), mapper.readTree(withLatest))

		val dump = jdbc.queryForList("select row_to_json(a)::text from audit_logs a", String::class.java).joinToString("\n")
		for (secret in listOf(MESSAGE, "안내비밀글", STORE, "apps.apple.com", "https")) assertFalse(secret in dump, "감사 기록에 있으면 안 되는 값: $secret")

		val listed = http(port, "GET", "/admin/audit-logs?action=APP_CONFIG_UPDATED", token = token).json()
		assertEquals(1, listed["total"].asInt())
		assertEquals(listOf("APP_CONFIG", "android"), listOf(listed["items"][0]["targetType"].asString(), listed["items"][0]["targetId"].asString()))
	}

	private fun putMaintenance(body: Any?, token: String? = this.token): HttpResponse<String> = put("maintenance", body, token)

	@Test
	fun `점검을 켜고 끄면 누가 언제 바꿨는지 적히고 바로 적용된다`() {
		val rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)
		// 지금 값(점검 아님)을 서버 메모리에 읽혀 둔다.
		assertEquals(200, http(port, "GET", "/places").statusCode())

		val on = putMaintenance(mapOf("active" to true, "message" to "  $MESSAGE \n"))
		assertEquals(200, on.statusCode(), on.body())
		val json = on.json()
		assertEquals(MAINTENANCE_KEYS, json.propertyNames().toSet())
		assertEquals(true, json["active"].asBoolean())
		assertEquals(MESSAGE, json["message"].asString(), "앞뒤 공백을 뗀다")
		assertEquals(listOf(rootId, ROOT_ID), listOf(json["updatedBy"]["id"].asString(), json["updatedBy"]["name"].asString()))
		assertEquals(json, get().json()["maintenance"])

		assertEquals(503, http(port, "GET", "/places").statusCode(), "캐시 시간을 기다리지 않고 바로 막는다")
		assertEquals("""{"error":"MAINTENANCE"}""", http(port, "GET", "/profile").body())
		assertTrue(""""maintenance":{"active":true,"message":"$MESSAGE"}""" in public())
		assertTrue(get().json()["ios"]["minVersion"].isNull, "플랫폼 설정은 그대로다")

		// 점검 중에도 관리자 API 는 열려 있어 끌 수 있다.
		val off = putMaintenance(mapOf("active" to false, "message" to null))
		assertEquals(200, off.statusCode())
		assertEquals(false, off.json()["active"].asBoolean())
		assertTrue(off.json()["message"].isNull)
		assertEquals(200, http(port, "GET", "/places").statusCode(), "끄면 바로 풀린다")

		for (blank in listOf("", "   ")) assertTrue(putMaintenance(mapOf("active" to false, "message" to blank)).json()["message"].isNull)
		assertEquals(200, putMaintenance(mapOf("active" to false, "message" to "😀".repeat(200))).statusCode())
	}

	@Test
	fun `점검을 꺼진 상태에서 켤 때만 시작한 때와 관리자가 적히고 끄면 지워진다`() {
		val rootId = jdbc.queryForObject("select id::text from admin_accounts where login_id = ?", String::class.java, ROOT_ID)
		val secondId = createAdmin(port, token, "second-admin", "second-admin-password", jdbc).toString()
		val secondToken = adminToken(port, "second-admin", "second-admin-password")
		val before = Instant.now().minusSeconds(2)

		val on = putMaintenance(mapOf("active" to true, "message" to MESSAGE)).json()
		val startedAt = Instant.parse(on["startedAt"].asString())
		assertTrue(startedAt.isAfter(before) && startedAt.isBefore(Instant.now().plusSeconds(2)), "켠 때")
		assertTrue(on["startedAt"].asString().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")), "UTC 초까지")
		assertEquals(listOf(rootId, ROOT_ID), listOf(on["startedBy"]["id"].asString(), on["startedBy"]["name"].asString()))
		assertEquals(setOf("id", "name"), on["startedBy"].propertyNames().toSet())

		// 켜진 채로 글만 바꾸면(다른 관리자가 해도) 시작은 그대로이고 고친 사람만 바뀐다.
		jdbc.update("update app_maintenance set started_at = '2026-11-01T00:00:00Z'")
		val edited = putMaintenance(mapOf("active" to true, "message" to "글만 바꿔요"), secondToken).json()
		assertEquals("2026-11-01T00:00:00Z", edited["startedAt"].asString())
		assertEquals(rootId, edited["startedBy"]["id"].asString())
		assertEquals(secondId, edited["updatedBy"]["id"].asString())
		assertEquals(edited, get().json()["maintenance"])

		// 끄면 둘 다 지워지고, 꺼진 채로 다시 꺼도 그대로 null 이다.
		for (round in 1..2) {
			val off = putMaintenance(mapOf("active" to false, "message" to null)).json()
			assertTrue(off["startedAt"].isNull && off["startedBy"].isNull, "끈 뒤 $round")
		}

		// 다시 켜면 그때 켠 관리자다.
		val again = putMaintenance(mapOf("active" to true, "message" to null), secondToken).json()
		assertEquals(secondId, again["startedBy"]["id"].asString())
		assertEquals("second-admin", again["startedBy"]["name"].asString())
		assertTrue(Instant.parse(again["startedAt"].asString()).isAfter(before))

		// 앱이 읽는 공개 설정의 점검 모양은 그대로다(시작한 때 · 관리자는 관리자 쪽에만).
		assertTrue(""""maintenance":{"active":true,"message":null}""" in public(), public())
		assertFalse("startedAt" in public() || "second-admin" in public())
	}

	@Test
	fun `틀린 점검 요청은 거절하고 아무것도 바꾸지 않는다`() {
		assertEquals(200, putMaintenance(mapOf("active" to false, "message" to "남아 있을 글")).statusCode())
		jdbc.update("delete from audit_logs")

		for (raw in listOf("[]", "1", "null", "{", "")) assertError(400, "INVALID_REQUEST", putMaintenance(raw), raw)
		assertError(400, "INVALID_REQUEST", putMaintenance(mapOf("active" to true)), "message 없음")
		assertError(400, "INVALID_REQUEST", putMaintenance(mapOf("message" to "x")), "active 없음")
		for (bad in listOf(null, "true", 1, listOf(true))) assertError(400, "INVALID_REQUEST", putMaintenance(mapOf("active" to bad, "message" to null)), "active=$bad")
		for (bad in listOf(1, true, listOf("x"), mapOf("a" to "b"))) assertError(400, "INVALID_REQUEST", putMaintenance(mapOf("active" to true, "message" to bad)), "message=$bad")
		assertError(400, "INVALID_MESSAGE", putMaintenance(mapOf("active" to true, "message" to "가".repeat(201))))
		assertError(400, "INVALID_REQUEST", putMaintenance(mapOf("active" to "true", "message" to "가".repeat(201))), "타입이 먼저다")
		val (_, userToken) = userAccount(jdbc, "maintenance-user")
		for (bad in listOf(null, userToken)) assertError(401, "UNAUTHORIZED", putMaintenance(mapOf("active" to true, "message" to null), bad))

		val maintenance = get().json()["maintenance"]
		assertEquals(listOf("false", "남아 있을 글"), listOf(maintenance["active"].asString(), maintenance["message"].asString()))
		assertEquals(200, http(port, "GET", "/places").statusCode())
		assertEquals(0, jdbc.queryForObject("select count(*) from audit_logs", Int::class.java))
	}

	@Test
	fun `점검을 바꿀 때마다 감사 기록에 켰는지만 남는다`() {
		assertEquals(200, putMaintenance(mapOf("active" to true, "message" to MESSAGE)).statusCode())
		assertEquals(200, putMaintenance(mapOf("active" to false, "message" to MESSAGE)).statusCode())

		val rows = jdbc.queryForList("select actor_label, action, target_type, target_id, outcome, detail::text as detail from audit_logs order by occurred_at")
		assertEquals(2, rows.size)
		for (row in rows) assertEquals(listOf(ROOT_ID, "MAINTENANCE_UPDATED", "APP_CONFIG", "maintenance", "SUCCESS"), listOf("actor_label", "action", "target_type", "target_id", "outcome").map { row[it] })
		assertEquals(listOf("""{"active": true}""", """{"active": false}"""), rows.map { it["detail"] })
		val dump = jdbc.queryForList("select row_to_json(a)::text from audit_logs a", String::class.java).joinToString("\n")
		assertFalse(MESSAGE in dump || "안내비밀글" in dump)
		assertEquals(2, http(port, "GET", "/admin/audit-logs?action=MAINTENANCE_UPDATED", token = token).json()["total"].asInt())
	}
}
