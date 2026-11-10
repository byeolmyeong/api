package com.byeolmyeong.api.web

import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MAPO = 1841277 // 서울특별시 마포구 (37.55438N, 126.90926E)

fun postChart(port: Int, body: String): HttpResponse<String> = HttpClient.newHttpClient().send(
	HttpRequest.newBuilder(URI("http://localhost:$port/charts"))
		.header("Content-Type", "application/json")
		.POST(HttpRequest.BodyPublishers.ofString(body))
		.build(),
	HttpResponse.BodyHandlers.ofString(),
)

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChartApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private val json = JsonMapper()

	private fun chart(body: String): JsonNode {
		val response = postChart(port, body)
		assertEquals(200, response.statusCode(), response.body())
		return json.readTree(response.body())
	}

	private fun JsonNode.names(): Set<String> = propertyNames().toSet()

	private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

	@Test
	fun `정상 요청은 200 과 차트 필드를 준다`() {
		val chart = chart("""{"date":"1990-03-15","time":"14:30","placeId":$MAPO}""")

		assertEquals(setOf("timeKnown", "positions", "points", "houses", "angles", "aspects"), chart.names())
		assertEquals(true, chart["timeKnown"].asBoolean())

		assertEquals(10, chart["positions"].size())
		val sun = chart["positions"][0]
		assertEquals(setOf("body", "sign", "degree", "minute", "house", "retrograde"), sun.names())
		// 태양 황경 354.37도(Swiss Ephemeris 기준값) → 물고기자리 24도 22분, 9하우스
		assertEquals("SUN", sun["body"].asString())
		assertEquals("PISCES", sun["sign"].asString())
		assertEquals(24, sun["degree"].asInt())
		assertEquals(22, sun["minute"].asInt())
		assertEquals(9, sun["house"].asInt())
		assertEquals(false, sun["retrograde"].asBoolean())

		assertEquals(listOf("NORTH_NODE", "LILITH", "FORTUNE"), chart["points"].items().map { it["point"].asString() })
		assertEquals(setOf("point", "sign", "degree", "minute", "house"), chart["points"][0].names())

		assertEquals(12, chart["houses"].size())
		assertEquals((1..12).toList(), chart["houses"].items().map { it["house"].asInt() })
		assertEquals(setOf("house", "sign", "degree", "minute"), chart["houses"][0].names())
		assertEquals(setOf("asc", "mc"), chart["angles"].names())
		// 서울 시청 기준 상승점은 사자자리 5.05도. 마포구는 경도가 0.07도 서쪽이라 같은 별자리·도에 있다
		assertEquals("LEO", chart["angles"]["asc"]["sign"].asString())
		assertEquals(chart["houses"][0]["degree"].asInt(), chart["angles"]["asc"]["degree"].asInt())

		assertTrue(chart["aspects"].size() > 0)
		val aspect = chart["aspects"][0]
		assertEquals(setOf("a", "b", "type", "orb", "applying"), aspect.names())
		assertTrue(aspect["orb"].isNumber && aspect["applying"].isBoolean)
	}

	@Test
	fun `시각이 null 이면 하우스 상승점 중천 행운점이 없다`() {
		val chart = chart("""{"date":"1990-03-15","time":null,"placeId":$MAPO}""")
		assertEquals(setOf("timeKnown", "positions", "points", "aspects"), chart.names())
		assertEquals(false, chart["timeKnown"].asBoolean())
		assertEquals(setOf("body", "sign", "degree", "minute", "retrograde"), chart["positions"][0].names())
		assertEquals(listOf("NORTH_NODE", "LILITH"), chart["points"].items().map { it["point"].asString() })
		// time 키가 아예 없어도 같다
		assertEquals(chart, chart("""{"date":"1990-03-15","placeId":$MAPO}"""))
	}

	@Test
	fun `없는 placeId 와 틀린 입력은 400 이다`() {
		fun assertBadRequest(body: String, error: String) {
			val response = postChart(port, body)
			assertEquals(400, response.statusCode(), body)
			assertEquals("""{"error":"$error"}""", response.body(), body)
		}
		assertBadRequest("""{"date":"1990-03-15","time":"14:30","placeId":999}""", "UNKNOWN_PLACE")
		assertBadRequest("""{"date":"1990-03-15","time":"14:30"}""", "UNKNOWN_PLACE")
		assertBadRequest("""{"date":"1990-03-15","time":"14:30","placeId":"마포"}""", "INVALID_REQUEST")
		assertBadRequest("""{"date":"1990-02-30","time":"14:30","placeId":$MAPO}""", "INVALID_DATE")
		assertBadRequest("""{"time":"14:30","placeId":$MAPO}""", "INVALID_DATE")
		assertBadRequest("""{"date":"1899-12-31","time":"14:30","placeId":$MAPO}""", "INVALID_DATE")
		assertBadRequest("""{"date":"2999-01-01","time":"14:30","placeId":$MAPO}""", "INVALID_DATE")
		assertBadRequest("""{"date":"1990-03-15","time":"25:00","placeId":$MAPO}""", "INVALID_TIME")
		assertBadRequest("""{"date":"1990-03-15","time":"오후 2시","placeId":$MAPO}""", "INVALID_TIME")
		assertBadRequest("""{"date":""", "INVALID_REQUEST")
		assertBadRequest("""[]""", "INVALID_REQUEST")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `정상 요청과 틀린 요청 모두 출생 정보를 로그에 남기지 않는다`(output: CapturedOutput) {
		chart("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		assertEquals(400, postChart(port, """{"date":"1977-07-07","time":"07:07","placeId":"1977-07-07"}""").statusCode())
		assertEquals(400, postChart(port, """{"date":"1977-07-07","time":"77:77","placeId":$MAPO}""").statusCode())
		assertEquals(400, postChart(port, """{"date":"1977-07-07","time":"07:07","placeId":""").statusCode())
		assertFalse(output.all.contains("1977"), output.all)
		assertFalse(output.all.contains("07:07"), output.all)
	}

	@Test
	fun `요청 뒤 DB 에 출생 정보 행이 없다`() {
		chart("""{"date":"1990-03-15","time":"14:30","placeId":$MAPO}""")
		val tables = jdbc.queryForList(
			"select table_name from information_schema.tables where table_schema = 'public' and table_name <> 'flyway_schema_history'",
			String::class.java,
		)
		val rows = tables.sumOf { jdbc.queryForObject("select count(*) from \"$it\"", Long::class.java)!! }
		assertEquals(0L, rows, "출생 정보를 담을 수 있는 테이블: $tables")
	}
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["byeolmyeong.rate-limit.charts-per-minute=3", "byeolmyeong.rate-limit.places-per-minute=2"],
)
class RateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `한도를 넘은 charts 요청은 429 다`() {
		val body = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
		assertEquals(listOf(200, 200, 200, 429, 429), (1..5).map { postChart(port, body).statusCode() })
		assertEquals("""{"error":"RATE_LIMITED"}""", postChart(port, body).body())
	}

	@Test
	fun `places 는 따로 세고 health 는 세지 않는다`() {
		fun status(path: String) = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		).statusCode()
		assertEquals(listOf(200, 200, 429), (1..3).map { status("/places?q=a") })
		assertFalse((1..10).map { status("/health") }.contains(429))
	}
}
