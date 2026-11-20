package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

/** 오늘의 하늘 알림 켜기 · 받는 시간(결정 042 의 3). 켜기의 기본값은 푸시 수신 동의를 따른다(앱 결정 057 의 4). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class AccountTodaySkyIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val mapper = JsonMapper()
	private lateinit var token: String

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		token = devToken(port)
	}

	private fun get(): JsonNode = mapper.readTree(http(port, "GET", "/account", token = token).body())

	private fun patch(body: String) = http(port, "PATCH", "/account", body, token)

	private fun state(): Pair<Boolean, String> = get().let { it["todaySkyPush"].asBoolean() to it["todaySkyTime"].asString() }

	@Test
	fun `기본은 푸시 수신 동의를 따르고 받는 시간은 오전 8시다`() {
		assertEquals(false to "08:00", state(), "동의 안 함")

		assertEquals(200, patch("""{"pushConsent":true}""").statusCode())
		assertEquals(true to "08:00", state(), "동의하면 켜진다")

		assertEquals(200, patch("""{"pushConsent":false}""").statusCode())
		assertEquals(false to "08:00", state(), "철회하면 꺼진다")
	}

	@Test
	fun `직접 켜고 끈 값은 동의와 따로 간다`() {
		assertEquals(true, mapper.readTree(patch("""{"todaySkyPush":true}""").body())["todaySkyPush"].asBoolean())
		assertEquals(true to "08:00", state(), "동의 없이도 켤 수 있다")

		patch("""{"pushConsent":true}""")
		assertEquals(200, patch("""{"todaySkyPush":false}""").statusCode())
		assertEquals(false to "08:00", state(), "동의가 있어도 끈 값이 이긴다")
		assertEquals(true, get()["pushConsent"].asBoolean(), "끄기는 동의를 건드리지 않는다")
	}

	@Test
	fun `받는 시간은 5분 단위 HH mm 이고 틀리면 400 이고 같이 보낸 칸도 바꾸지 않는다`() {
		val ok = patch("""{"todaySkyTime":"21:35"}""")
		assertEquals(200, ok.statusCode())
		assertEquals("21:35", mapper.readTree(ok.body())["todaySkyTime"].asString())
		assertEquals("00:00", mapper.readTree(patch("""{"todaySkyTime":"00:00"}""").body())["todaySkyTime"].asString())
		assertEquals("23:55", mapper.readTree(patch("""{"todaySkyTime":"23:55"}""").body())["todaySkyTime"].asString())

		for (bad in listOf("21:33", "24:00", "8:00", "08:00:00", "", "0800", "12:60", "-1:00")) {
			val response = patch("""{"todaySkyTime":"$bad","todaySkyPush":true,"avatar":3}""")
			assertEquals(400, response.statusCode(), bad)
			assertEquals("""{"error":"INVALID_TODAY_SKY_TIME"}""", response.body(), bad)
		}
		assertEquals(false to "23:55", state())
		assertEquals(true, get()["avatar"].isNull)
		for (raw in listOf("""{"todaySkyTime":800}""", """{"todaySkyPush":"true"}""", """{"todaySkyPush":1}""")) {
			assertEquals(400, patch(raw).statusCode(), raw)
		}
	}
}
