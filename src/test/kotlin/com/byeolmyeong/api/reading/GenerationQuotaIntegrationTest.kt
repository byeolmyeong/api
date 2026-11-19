package com.byeolmyeong.api.reading

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
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val MAPO = 1841277
private fun profile(date: String) = """{"date":"$date","time":"14:30","placeId":$MAPO}"""

/**
 * 결정 023: 생성 한도는 모델을 실제로 부르는 요청만 센다. 시간 창(2번)으로 본다 — 분 창은 경계를 걸치면 흔들린다.
 * 테스트마다 계정을 지우므로 개발용 계정의 id 가 바뀌어 한도가 테스트끼리 섞이지 않는다.
 */
@Import(TestcontainersConfiguration::class, ReadingApiIntegrationTest.FakeGeneratorConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON,
		"byeolmyeong.rate-limit.generation-per-minute=100",
		"byeolmyeong.rate-limit.generation-per-hour=2",
	],
)
class GenerationQuotaIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeReadingGenerator

	private val mapper = JsonMapper()

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		generator.reset()
	}

	private fun setProfile(token: String, date: String) = assertEquals(200, http(port, "PUT", "/profile", profile(date), token).statusCode())
	private fun read(token: String) = http(port, "POST", "/readings", token = token)

	@Test
	fun `저장본을 돌려주는 요청은 세지 않는다`() {
		val token = devToken(port)
		setProfile(token, "1990-03-15")
		repeat(5) { assertEquals(200, read(token).statusCode(), "${it + 1}번째") }
		assertEquals(1, generator.prompts.size, "모델은 처음 한 번만")
	}

	@Test
	fun `출생 정보가 없어 404 인 요청은 세지 않는다`() {
		val token = devToken(port)
		repeat(4) { assertEquals(404, read(token).statusCode()) }
		setProfile(token, "1990-03-15")
		assertEquals(200, read(token).statusCode())
	}

	@Test
	fun `새로 생성하는 요청이 한도를 넘으면 429 이고 모델을 부르지 않는다`() {
		val token = devToken(port)
		setProfile(token, "1990-03-15")
		assertEquals(200, read(token).statusCode())
		setProfile(token, "1991-04-16")
		assertEquals(200, read(token).statusCode())
		setProfile(token, "1992-05-17")

		val over = read(token)

		assertEquals(429, over.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", over.body())
		assertEquals(2, generator.prompts.size)
	}

	@Test
	fun `한도를 넘은 리포트 요청은 쓰는 중으로 남지 않는다`() {
		val token = devToken(port)
		setProfile(token, "1990-03-15")
		assertEquals(200, read(token).statusCode())
		setProfile(token, "1991-04-16")
		assertEquals(200, read(token).statusCode())

		val over = http(port, "POST", "/reports", token = token)

		assertEquals(429, over.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", over.body())
		val status = mapper.readTree(http(port, "GET", "/reports/me", token = token).body())["status"].asString()
		assertNotEquals("WRITING", status, "자리를 잡은 채 남으면 다음 요청이 시작하지 못한다")
	}
}
