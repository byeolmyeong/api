package com.byeolmyeong.api.push

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.http
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
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val TOKEN = "ExponentPushToken[abcDEF0123456789_-xyzQ]"
private const val OTHER_TOKEN = "ExpoPushToken[zzzzYYYYxxxx1111]"

/** 기기 토큰 등록 · 지우기(결정 042 의 2). 소유자는 세션에서만. */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class PushDeviceApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var me: UUID
	private lateinit var myToken: String
	private lateinit var other: UUID
	private lateinit var otherToken: String

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		account("push-me").let { (id, t) -> me = id; myToken = t }
		account("push-other").let { (id, t) -> other = id; otherToken = t }
	}

	private fun sha256(text: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

	private fun account(name: String): Pair<UUID, String> {
		val id = UUID.randomUUID()
		val session = "push-session-of-$name"
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'TEST', ?)", id, name)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(session), id)
		return id to session
	}

	private fun register(body: String, session: String? = myToken, version: String? = "1.0.0"): HttpResponse<String> =
		if (version == null) http(port, "POST", "/push/devices", body, session) else http(port, "POST", "/push/devices", body, session, "X-App-Version", version)

	private fun add(token: String, platform: String, session: String = myToken): HttpResponse<String> =
		register("""{"expoToken":"$token","platform":"$platform"}""", session)

	private fun remove(token: String, session: String? = myToken): HttpResponse<String> =
		http(port, "DELETE", "/push/devices/" + URLEncoder.encode(token, Charsets.UTF_8), token = session)

	private fun rows(): List<Map<String, Any?>> =
		jdbc.queryForList("select expo_token, account_id, platform, app_version from push_devices order by expo_token")

	@Test
	fun `등록하면 204 이고 내 계정 · 플랫폼 · 앱 버전으로 한 줄이 생긴다`() {
		val response = add(TOKEN, "IOS")

		assertEquals(204, response.statusCode(), response.body())
		assertEquals(listOf(mapOf<String, Any?>("expo_token" to TOKEN, "account_id" to me, "platform" to "IOS", "app_version" to "1.0.0")), rows())
	}

	@Test
	fun `같은 토큰을 다시 보내면 줄은 하나이고 다른 계정이 보내면 그 계정으로 옮긴다`() {
		assertEquals(204, add(TOKEN, "IOS").statusCode())
		jdbc.update("update push_devices set last_seen_at = now() - interval '3 days'")
		assertEquals(204, add(TOKEN, "IOS").statusCode())
		assertEquals(1, rows().size)
		assertEquals(true, jdbc.queryForObject("select last_seen_at > now() - interval '1 minute' from push_devices", Boolean::class.java), "다시 등록한 때를 고친다")

		assertEquals(204, add(TOKEN, "ANDROID", otherToken).statusCode())

		assertEquals(listOf(mapOf<String, Any?>("expo_token" to TOKEN, "account_id" to other, "platform" to "ANDROID", "app_version" to "1.0.0")), rows())
	}

	@Test
	fun `한 계정이 기기 여럿을 둘 수 있고 앱 버전이 없거나 틀리면 비워 둔다`() {
		assertEquals(204, register("""{"expoToken":"$TOKEN","platform":"IOS"}""", version = null).statusCode())
		assertEquals(204, register("""{"expoToken":"$OTHER_TOKEN","platform":"ANDROID"}""", version = "v1-beta").statusCode())

		assertEquals(listOf(null, null), rows().map { it["app_version"] })
		assertEquals(listOf(me, me), rows().map { it["account_id"] })
	}

	@Test
	fun `토큰 · 플랫폼이 틀리면 400 이고 아무것도 넣지 않는다`() {
		for (bad in listOf("", "abc", "ExponentPushToken[]", "ExponentPushToken[has space]", "ExponentPushToken[abc", "PushToken[abc]", "ExponentPushToken[${"a".repeat(200)}]")) {
			val response = register("""{"expoToken":"$bad","platform":"IOS"}""")
			assertEquals(400, response.statusCode(), bad)
			assertEquals("""{"error":"INVALID_PUSH_TOKEN"}""", response.body(), bad)
		}
		assertEquals("""{"error":"INVALID_PUSH_TOKEN"}""", register("""{"platform":"IOS"}""").body())
		for (bad in listOf("ios", "WEB", "")) assertEquals("""{"error":"INVALID_PLATFORM"}""", register("""{"expoToken":"$TOKEN","platform":"$bad"}""").body(), bad)
		assertEquals("""{"error":"INVALID_PLATFORM"}""", register("""{"expoToken":"$TOKEN"}""").body())
		// 숫자는 Jackson 이 글자로 받아 모양 검사에 걸린다(400 은 같다).
		assertEquals("""{"error":"INVALID_PUSH_TOKEN"}""", register("""{"expoToken":1,"platform":"IOS"}""").body())
		for (raw in listOf("""{"expoToken":["$TOKEN"],"platform":"IOS"}""", """{"expoToken":"$TOKEN","platform":["IOS"]}""", "[]")) {
			assertEquals(400, register(raw).statusCode(), raw)
			assertEquals("""{"error":"INVALID_REQUEST"}""", register(raw).body(), raw)
		}
		assertEquals(emptyList(), rows())
	}

	@Test
	fun `내 기기만 지우고 남의 것 · 없는 것은 404 이다`() {
		assertEquals(204, add(TOKEN, "IOS").statusCode())
		assertEquals(204, add(OTHER_TOKEN, "ANDROID", otherToken).statusCode())

		assertEquals(404, remove(OTHER_TOKEN).statusCode(), "남의 기기")
		assertEquals("""{"error":"NOT_FOUND"}""", remove("ExponentPushToken[nope]").body())
		assertEquals(2, rows().size)

		assertEquals(204, remove(TOKEN).statusCode())
		assertEquals(listOf(OTHER_TOKEN), rows().map { it["expo_token"] })
		assertEquals(404, remove(TOKEN).statusCode(), "이미 지웠다")
	}

	@Test
	fun `계정을 지우면 그 계정의 기기도 함께 지워진다`() {
		assertEquals(204, add(TOKEN, "IOS").statusCode())
		assertEquals(204, add(OTHER_TOKEN, "ANDROID", otherToken).statusCode())

		assertEquals(204, http(port, "DELETE", "/account", token = myToken).statusCode())

		assertEquals(listOf(OTHER_TOKEN), rows().map { it["expo_token"] })
	}

	@Test
	fun `정지된 계정도 기기 토큰은 지울 수 있다 — 로그아웃의 일부`() {
		assertEquals(204, add(TOKEN, "IOS").statusCode())
		jdbc.update("update accounts set suspended_at = now() where id = ?", me)

		assertEquals(403, add(OTHER_TOKEN, "IOS").statusCode(), "등록은 막힌다")
		assertEquals(204, remove(TOKEN).statusCode())
		assertEquals(emptyList(), rows())
	}

	@Test
	fun `세션이 없으면 401 이다`() {
		assertEquals(401, register("""{"expoToken":"$TOKEN","platform":"IOS"}""", session = null).statusCode())
		assertEquals(401, remove(TOKEN, session = null).statusCode())
		assertEquals(emptyList(), rows())
	}

	@Test
	fun `토큰은 로그에 남지 않는다`(output: CapturedOutput) {
		add(TOKEN, "IOS")
		register("""{"expoToken":"ExponentPushToken[bad token]","platform":"IOS"}""")
		remove(TOKEN)

		assertFalse(output.all.contains("abcDEF0123456789"), "토큰이 로그에 있다")
	}
}
