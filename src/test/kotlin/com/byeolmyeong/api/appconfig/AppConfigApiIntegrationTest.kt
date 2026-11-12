package com.byeolmyeong.api.appconfig

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val APP = "http://localhost:8081"
private const val ADMIN = "http://localhost:5173"
private const val ADMIN_ID = "config-admin"
private const val ADMIN_PASSWORD = "config-admin-password"
private const val EMPTY = """{"minVersion":null,"storeUrl":null,"message":null,"blockNow":false}"""
private const val UPDATE_REQUIRED = """{"error":"UPDATE_REQUIRED"}"""
private const val NO_MAINTENANCE = """"maintenance":{"active":false,"message":null}"""
private const val MAINTENANCE = """{"error":"MAINTENANCE"}"""

fun resetAppConfigs(jdbc: JdbcTemplate) {
	jdbc.update("update app_configs set min_version = null, store_url = null, message = null, block_now = false, updated_by = null")
	jdbc.update("update app_maintenance set active = false, message = null, updated_by = null")
}

/**
 * 앱 설정의 공개 조회 `GET /app-config` 와 강제 업데이트(426, 결정 016). 값은 표에 직접 넣는다.
 * 캐시는 끈다(`cache-seconds=0`) — 캐시는 아래 [AppConfigCacheIntegrationTest] 가 본다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON,
		"byeolmyeong.app-config.cache-seconds=0",
		"byeolmyeong.rate-limit.app-config-per-minute=100000",
		"byeolmyeong.rate-limit.places-per-minute=100000",
		"byeolmyeong.rate-limit.admin-login-per-ip=100000",
		"byeolmyeong.rate-limit.admin-login-per-id=100000",
		"byeolmyeong.admin.bootstrap-id=$ADMIN_ID",
		"byeolmyeong.admin.bootstrap-password=$ADMIN_PASSWORD",
		"byeolmyeong.admin.bcrypt-strength=4",
		"byeolmyeong.cors.allowed-origins=$APP",
		"byeolmyeong.cors.admin-allowed-origins=$ADMIN",
	],
)
class AppConfigApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@BeforeEach
	fun clean() {
		resetAppConfigs(jdbc)
	}

	private fun set(platform: String, minVersion: String?, blockNow: Boolean, storeUrl: String? = null, message: String? = null) {
		jdbc.update("update app_configs set min_version = ?, block_now = ?, store_url = ?, message = ? where platform = ?", minVersion, blockNow, storeUrl, message, platform)
	}

	/** 앱이 보내는 두 헤더를 붙여 부른다. null 인 헤더는 보내지 않는다. */
	private fun call(
		method: String,
		path: String,
		platform: String? = "ios",
		version: String? = "1.1.9",
		token: String? = null,
		body: String? = null,
		vararg more: String,
	): HttpResponse<String> {
		val headers = buildList {
			if (platform != null) addAll(listOf("X-App-Platform", platform))
			if (version != null) addAll(listOf("X-App-Version", version))
			addAll(more)
		}
		return http(port, method, path, body, token, *headers.toTypedArray())
	}

	private fun assertBlocked(response: HttpResponse<String>, what: String = "") {
		assertEquals(426, response.statusCode(), what)
		assertEquals(UPDATE_REQUIRED, response.body(), what)
		assertEquals("application/json", response.headers().firstValue("Content-Type").orElse("").substringBefore(';'), what)
	}

	@Test
	fun `처음에는 두 플랫폼 모두 비어 있고 세션 없이 읽는다`() {
		val response = http(port, "GET", "/app-config")
		assertEquals(200, response.statusCode())
		assertEquals("""{"ios":$EMPTY,"android":$EMPTY,$NO_MAINTENANCE}""", response.body())
		assertEquals("public, max-age=60", response.headers().firstValue("Cache-Control").orElse(null))
	}

	@Test
	fun `넣은 값이 플랫폼마다 그대로 온다`() {
		set("ios", "1.2.0", true, "https://apps.apple.com/app/id000", "새 버전으로 업데이트해 주세요")
		set("android", "2.0", false)

		assertEquals(
			"""{"ios":{"minVersion":"1.2.0","storeUrl":"https://apps.apple.com/app/id000","message":"새 버전으로 업데이트해 주세요","blockNow":true},""" +
				""""android":{"minVersion":"2.0","storeUrl":null,"message":null,"blockNow":false},$NO_MAINTENANCE}""",
			http(port, "GET", "/app-config").body(),
		)
	}

	@Test
	fun `세션 없이 열린 것은 GET 뿐이다`() {
		// 같은 경로의 다른 메서드는 없다(405). 열려 있지 않다는 것만 본다.
		for (method in listOf("POST", "PUT", "DELETE")) assertEquals(405, http(port, method, "/app-config", "{}").statusCode(), method)
		assertEquals(401, http(port, "GET", "/app-config/ios").statusCode())
	}

	@Test
	fun `막기가 켜져 있고 버전이 낮으면 모든 앱 경로가 426 이다`() {
		set("ios", "1.2.0", true)
		val token = devToken(port)

		assertBlocked(call("GET", "/places"), "세션이 필요 없는 경로")
		assertBlocked(call("GET", "/profile", token = token), "세션이 있는 요청")
		assertBlocked(call("GET", "/profile"), "세션이 없으면 401 이 아니라 426 이 먼저다")
		assertBlocked(call("POST", "/readings", token = token))
		assertBlocked(call("PUT", "/profile", token = token, body = """{"date":"1990-03-15","time":null}"""))
		assertBlocked(call("DELETE", "/account", token = token))
		assertBlocked(call("POST", "/auth/dev"))
		assertBlocked(call("GET", "/faqs"))
		assertBlocked(call("GET", "/no-such-path", token = token), "없는 경로")
		assertEquals(1, jdbc.queryForObject("select count(*) from accounts", Int::class.java), "막힌 요청은 아무것도 하지 않는다")
		assertEquals(0, jdbc.queryForObject("select count(*) from profiles", Int::class.java))

		for (version in listOf("1.1.9", "1.1", "1", "0.9.9", "1.1.99", "0", "1.01.9")) assertBlocked(call("GET", "/places", version = version), version)
		for (platform in listOf("ios", "IOS", "iOS")) assertBlocked(call("GET", "/places", platform = platform), platform)
	}

	@Test
	fun `버전이 같거나 높으면 막지 않는다`() {
		set("ios", "1.2.0", true)
		for (version in listOf("1.2.0", "1.2", "1.2.1", "1.10.0", "1.10", "2", "10.0.0", "01.02.00")) {
			assertEquals(200, call("GET", "/places", version = version).statusCode(), version)
		}
	}

	@Test
	fun `조건이 하나라도 빠지면 막지 않는다`() {
		// 막기가 꺼져 있다(최소 버전만 있다).
		set("ios", "1.2.0", false)
		assertEquals(200, call("GET", "/places").statusCode())

		set("ios", "1.2.0", true)
		// 다른 플랫폼의 설정은 상관없다.
		assertEquals(200, call("GET", "/places", platform = "android").statusCode())
		// 헤더가 없다(웹), 하나만 있다.
		assertEquals(200, call("GET", "/places", platform = null, version = null).statusCode())
		assertEquals(200, call("GET", "/places", version = null).statusCode())
		assertEquals(200, call("GET", "/places", platform = null).statusCode())
		// 모르는 플랫폼.
		for (platform in listOf("web", "windows", "ios2", "i os")) assertEquals(200, call("GET", "/places", platform = platform).statusCode(), platform)
		// 읽을 수 없는 버전.
		for (version in listOf("1.1.9-beta", "v1.1.9", "1.1.9.0", "abc", "1..1", "1,1", "-1")) assertEquals(200, call("GET", "/places", version = version).statusCode(), version)
		// 세션 검사는 그대로다.
		assertEquals(401, call("GET", "/profile", platform = null, version = null).statusCode())
		assertEquals(401, call("GET", "/profile", version = "9.9.9").statusCode())
	}

	@Test
	fun `막기는 최소 버전이 있어야 켤 수 있다`() {
		val failure = runCatching { set("android", null, true) }.exceptionOrNull()
		assertNotEquals(null, failure, "테이블이 거절한다")
		assertEquals(200, call("GET", "/places", platform = "android", version = "0.0.1").statusCode())
	}

	@Test
	fun `설정 조회·상태 확인·관리자 경로는 막지 않는다`() {
		set("ios", "9.0.0", true, "https://apps.apple.com/app/id000", "업데이트해 주세요")
		set("android", "9.0.0", true)

		for (platform in listOf("ios", "android")) {
			val config = call("GET", "/app-config", platform = platform, version = "1.0.0")
			assertEquals(200, config.statusCode())
			assertEquals(true, "\"blockNow\":true" in config.body())
			assertEquals(200, call("GET", "/health", platform = platform, version = "1.0.0").statusCode())

			val login = call("POST", "/admin/auth/login", platform = platform, version = "1.0.0", body = """{"loginId":"$ADMIN_ID","password":"$ADMIN_PASSWORD"}""")
			assertEquals(200, login.statusCode(), "관리 화면은 잠기지 않는다")
			val adminToken = Regex(""""token":"([^"]+)"""").find(login.body())!!.groupValues[1]
			assertEquals(200, call("GET", "/admin/me", platform = platform, version = "1.0.0", token = adminToken).statusCode())
			assertEquals(200, call("GET", "/admin/faqs", platform = platform, version = "1.0.0", token = adminToken).statusCode())
			assertEquals(401, call("GET", "/admin/me", platform = platform, version = "1.0.0").statusCode(), "관리자 경로는 426 이 아니라 관리자 세션 검사의 답이다")
		}
	}

	@Test
	fun `426 에도 CORS 허용 헤더가 붙고 사전 요청은 건드리지 않는다`() {
		set("ios", "1.2.0", true)

		val blocked = call("GET", "/profile", "ios", "1.0.0", null, null, "Origin", APP)
		assertBlocked(blocked)
		assertEquals(APP, blocked.headers().firstValue("Access-Control-Allow-Origin").orElse(null))

		val preflight = call("OPTIONS", "/profile", "ios", "1.0.0", null, null, "Origin", APP, "Access-Control-Request-Method", "GET")
		assertEquals(200, preflight.statusCode())
		assertEquals(APP, preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null))

		// 허용하지 않은 출처는 여전히 CORS 가 거절한다.
		val foreign = call("GET", "/profile", "ios", "1.0.0", null, null, "Origin", "http://evil.example.com")
		assertEquals(403, foreign.statusCode())
		assertNull(foreign.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
	}

	private fun maintenance(active: Boolean, message: String? = null) {
		jdbc.update("update app_maintenance set active = ?, message = ?", active, message)
	}

	private fun assertMaintenance(response: HttpResponse<String>, what: String = "") {
		assertEquals(503, response.statusCode(), what)
		assertEquals(MAINTENANCE, response.body(), what)
		assertEquals("application/json", response.headers().firstValue("Content-Type").orElse("").substringBefore(';'), what)
	}

	@Test
	fun `점검 안내가 설정 조회에 실린다`() {
		maintenance(true, "새벽 2시부터 4시까지 점검해요")
		assertEquals(
			"""{"ios":$EMPTY,"android":$EMPTY,"maintenance":{"active":true,"message":"새벽 2시부터 4시까지 점검해요"}}""",
			http(port, "GET", "/app-config").body(),
		)
		maintenance(true)
		assertEquals(true, """"maintenance":{"active":true,"message":null}""" in http(port, "GET", "/app-config").body())
	}

	@Test
	fun `점검 중에는 웹과 앱의 모든 경로가 503 이다`() {
		val token = devToken(port)
		maintenance(true, "점검 중")

		// 버전 헤더가 없는 요청(웹)도 막힌다.
		assertMaintenance(call("GET", "/places", platform = null, version = null), "세션이 필요 없는 경로")
		assertMaintenance(call("GET", "/profile", platform = null, version = null, token = token), "세션이 있는 요청")
		assertMaintenance(call("GET", "/profile", platform = null, version = null), "세션이 없으면 401 이 아니라 503 이 먼저다")
		assertMaintenance(call("POST", "/readings", platform = null, version = null, token = token))
		assertMaintenance(call("PUT", "/profile", platform = null, version = null, token = token, body = """{"date":"1990-03-15","time":null}"""))
		assertMaintenance(call("POST", "/auth/dev", platform = null, version = null))
		assertMaintenance(call("GET", "/faqs", platform = null, version = null))
		assertMaintenance(call("GET", "/shared/no-such-share", platform = null, version = null))
		assertMaintenance(call("GET", "/no-such-path", platform = null, version = null, token = token), "없는 경로")
		assertMaintenance(call("GET", "/places", platform = "android", version = "9.9.9"), "최신 앱")
		assertEquals(0, jdbc.queryForObject("select count(*) from profiles", Int::class.java), "막힌 요청은 아무것도 하지 않는다")

		// 강제 업데이트보다 먼저다.
		set("ios", "1.2.0", true)
		assertMaintenance(call("GET", "/places", version = "1.0.0"), "옛 앱도 426 이 아니라 503")

		maintenance(false, "점검 중")
		assertBlocked(call("GET", "/places", version = "1.0.0"), "끄면 다음 검사(426)로 넘어간다")
		assertEquals(200, call("GET", "/places", platform = null, version = null).statusCode())
		assertEquals(401, call("GET", "/profile", platform = null, version = null).statusCode())
	}

	@Test
	fun `점검 중에도 설정 조회·상태 확인·관리자 경로는 열려 있다`() {
		maintenance(true, "점검 중")

		val config = http(port, "GET", "/app-config")
		assertEquals(200, config.statusCode())
		assertEquals(true, """"active":true""" in config.body())
		assertEquals(200, http(port, "GET", "/health").statusCode())

		val login = http(port, "POST", "/admin/auth/login", """{"loginId":"$ADMIN_ID","password":"$ADMIN_PASSWORD"}""")
		assertEquals(200, login.statusCode(), "관리자는 로그인해 점검을 끌 수 있어야 한다")
		val adminToken = Regex(""""token":"([^"]+)"""").find(login.body())!!.groupValues[1]
		assertEquals(200, http(port, "GET", "/admin/me", token = adminToken).statusCode())
		assertEquals(401, http(port, "GET", "/admin/me").statusCode(), "관리자 경로는 503 이 아니라 관리자 세션 검사의 답이다")
	}

	@Test
	fun `503 에도 CORS 허용 헤더가 붙고 사전 요청은 건드리지 않는다`() {
		maintenance(true)

		val blocked = http(port, "GET", "/profile", null, null, "Origin", APP)
		assertMaintenance(blocked)
		assertEquals(APP, blocked.headers().firstValue("Access-Control-Allow-Origin").orElse(null))

		val preflight = http(port, "OPTIONS", "/profile", null, null, "Origin", APP, "Access-Control-Request-Method", "GET")
		assertEquals(200, preflight.statusCode())
		assertEquals(APP, preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
	}
}

/** 설정은 서버 메모리에 잠깐 둔다: 그동안은 DB 를 다시 읽지 않고, 시간이 지나면 새 값을 읽는다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["byeolmyeong.app-config.cache-seconds=2", "byeolmyeong.rate-limit.app-config-per-minute=100000", "byeolmyeong.rate-limit.places-per-minute=100000"],
)
class AppConfigCacheIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Test
	fun `캐시가 살아 있는 동안은 옛 값이고 지나면 새 값이다`() {
		resetAppConfigs(jdbc)
		// 앞선 테스트가 남긴 캐시가 끝나기를 기다린 뒤 지금 값을 읽혀 둔다.
		Thread.sleep(2_100)
		assertEquals(200, http(port, "GET", "/places", null, null, "X-App-Platform", "ios", "X-App-Version", "1.0.0").statusCode())

		jdbc.update("update app_configs set min_version = '1.2.0', block_now = true where platform = 'ios'")
		assertEquals(200, http(port, "GET", "/places", null, null, "X-App-Platform", "ios", "X-App-Version", "1.0.0").statusCode(), "아직 옛 값")
		assertEquals(true, "\"minVersion\":null" in http(port, "GET", "/app-config").body())

		Thread.sleep(2_100)
		assertEquals(426, http(port, "GET", "/places", null, null, "X-App-Platform", "ios", "X-App-Version", "1.0.0").statusCode())
		assertEquals(true, "\"minVersion\":\"1.2.0\"" in http(port, "GET", "/app-config").body())
		resetAppConfigs(jdbc)
	}
}

/** `GET /app-config` 는 주소별 분당 제한이 있다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["byeolmyeong.rate-limit.app-config-per-minute=2"])
class AppConfigRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `한도를 넘으면 429 다`() {
		// 분이 바뀌는 순간에 걸치면 창이 비워진다. 그때는 한 번 더 채운다.
		val statuses = (1..6).map { http(port, "GET", "/app-config").statusCode() }
		assertEquals(true, 429 in statuses, statuses.toString())
		assertEquals(200, statuses.first())
		val limited = generateSequence { http(port, "GET", "/app-config") }.take(4).first { it.statusCode() == 429 }
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
	}
}
