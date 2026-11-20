package com.byeolmyeong.api.sky

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.computeIntervals
import com.byeolmyeong.api.chart.notableDays
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.generation.dayCategory
import com.byeolmyeong.api.generation.monthPhrases
import com.byeolmyeong.api.http
import com.byeolmyeong.api.report.CountingQuota
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val UNKNOWN = """{"date":"1990-03-15","time":null}"""
private val mapper = JsonMapper()

/** 이번 달은 모델을 부르지 않는다 — 불리면 센다. */
class CountingGenerator : TextGenerator {
	val calls = AtomicInteger()

	override fun generate(request: GenerationRequest): JsonNode? {
		calls.incrementAndGet()
		return null
	}
}

/** `GET /timing/month`(결정 032): 요청 때 계산하고 저장하지 않으며 모델 · 생성 한도를 쓰지 않는다. */
@Import(TestcontainersConfiguration::class, MonthSkyApiIntegrationTest.FakeConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class MonthSkyApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun countingGenerator() = CountingGenerator()

		@Bean
		@Primary
		fun monthCountingQuota() = CountingQuota()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: CountingGenerator

	@Autowired
	private lateinit var quota: CountingQuota

	@Autowired
	private lateinit var profiles: BirthProfileService

	private val korea = ZoneId.of("Asia/Seoul")

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		generator.calls.set(0)
		quota.owners.clear()
	}

	private fun tokenWithProfile(profile: String): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	private fun month(token: String?, query: String = "") = http(port, "GET", "/timing/month$query", token = token)

	private fun ownerId(): UUID = jdbc.queryForObject("select id from accounts", UUID::class.java)!!

	/** 응답을 읽고 저장소 밖의 계산(chart · generation 의 순수 함수)과 견준다. */
	private fun assertMatchesCalculation(json: JsonNode, month: YearMonth) {
		val birth = profiles.chartInput(ownerId())!!
		val chart = birth.chart()
		val days = notableDays(chart, month) { dayCategory(it.mover, it.target) }
		val phrases = monthPhrases(days)
		val expectedDays = days.zip(phrases).map { (day, phrase) ->
			mapOf(
				"date" to day.date.toString(),
				"category" to phrase.category.name,
				"text" to phrase.text,
				"reason" to mapOf("when" to "DAY", "items" to listOf(mapOf("kind" to "TRANSIT", "mover" to day.mover.name, "target" to day.target, "type" to day.type.name))),
			)
		}
		assertEquals(mapper.valueToTree<JsonNode>(expectedDays), json["days"], "눈여겨볼 날 = 지나가는 별 계산 + 문구표")
		val expectedThemes = monthThemes(computeIntervals(chart, birth.utc), month)
		assertEquals(mapper.valueToTree<JsonNode>(expectedThemes), json["themes"], "이번 달 내내 = 그 사람의 시기 구간에서")
	}

	@Test
	fun `달을 주지 않으면 한국 날짜의 이번 달을 계산해 주고 같은 요청은 같은 본문이다`() {
		val token = tokenWithProfile(KNOWN)

		val response = month(token)

		assertEquals(200, response.statusCode(), response.body())
		val json = mapper.readTree(response.body())
		assertEquals(listOf("month", "timeKnown", "themes", "days"), json.propertyNames().toList())
		val thisMonth = YearMonth.now(korea)
		assertEquals(thisMonth.toString(), json["month"].asString())
		assertTrue(json["timeKnown"].asBoolean())
		assertTrue(json["themes"].size() <= 2)
		for (theme in json["themes"].values()) assertEquals(listOf("area", "title", "from", "to"), theme.propertyNames().toList())
		val days = json["days"].values().toList()
		assertTrue(days.size <= 3)
		val dates = days.map { LocalDate.parse(it["date"].asString()) }
		assertEquals(dates.sorted(), dates, "날짜 순")
		assertTrue(dates.all { YearMonth.from(it) == thisMonth })
		assertTrue(dates.zipWithNext().all { (a, b) -> ChronoUnit.DAYS.between(a, b) >= 3 }, "사흘 이상 떨어진다")
		for (day in days) {
			assertEquals(listOf("date", "category", "text", "reason"), day.propertyNames().toList())
			assertTrue(day["text"].asString().endsWith("날"))
			assertEquals("DAY", day["reason"]["when"].asString())
			assertTrue(day["reason"]["items"].single()["mover"].asString() in listOf("SUN", "MERCURY", "VENUS", "MARS", "JUPITER"))
		}
		assertMatchesCalculation(json, thisMonth)

		assertEquals(response.body(), month(token).body(), "같은 차트 · 같은 달은 같은 값(저장하지 않아도)")
		assertEquals(response.body(), month(token, "?month=$thisMonth").body())
		assertEquals(response.body(), month(token, "?month=").body(), "빈 값은 없는 것")
	}

	@Test
	fun `지난달과 다음 달도 받고 그 밖은 400 이며 형식이 틀리면 400 이다`() {
		val token = tokenWithProfile(KNOWN)
		val thisMonth = YearMonth.now(korea)
		for (allowed in listOf(thisMonth.minusMonths(1), thisMonth.plusMonths(1))) {
			val response = month(token, "?month=$allowed")
			assertEquals(200, response.statusCode(), "$allowed")
			val json = mapper.readTree(response.body())
			assertEquals(allowed.toString(), json["month"].asString())
			assertMatchesCalculation(json, allowed)
		}
		for (far in listOf(thisMonth.minusMonths(2), thisMonth.plusMonths(2), thisMonth.plusYears(1))) {
			val response = month(token, "?month=$far")
			assertEquals(400, response.statusCode(), "$far")
			assertEquals("""{"error":"MONTH_OUT_OF_RANGE"}""", response.body())
		}
		for (bad in listOf("2026-1", "2026-13", "abc", "2026-11-01")) {
			val response = month(token, "?month=$bad")
			assertEquals(400, response.statusCode(), bad)
			assertEquals("""{"error":"INVALID_MONTH"}""", response.body())
		}
	}

	@Test
	fun `시각을 모르면 상승점 · 중천이 대상인 날이 없고 timeKnown 이 false 다`() {
		val token = tokenWithProfile(UNKNOWN)

		val json = mapper.readTree(month(token).body())

		assertFalse(json["timeKnown"].asBoolean())
		val text = json.toString()
		for (word in listOf("\"ASC\"", "\"MC\"", "\"house\"", "VERTEX")) assertFalse(text.contains(word), word)
		assertMatchesCalculation(json, YearMonth.now(korea))
		// 다음 달 · 지난달도
		for (other in listOf(YearMonth.now(korea).plusMonths(1), YearMonth.now(korea).minusMonths(1))) {
			val next = mapper.readTree(month(token, "?month=$other").body())
			assertFalse(next.toString().contains("\"ASC\"") || next.toString().contains("\"MC\""))
		}
	}

	@Test
	fun `출생 정보가 없으면 404 이고 세션이 없으면 401 이다`() {
		assertEquals(401, month(null).statusCode())
		assertEquals("""{"error":"UNAUTHORIZED"}""", month(null).body())
		val token = devToken(port)
		val response = month(token)
		assertEquals(404, response.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", response.body())
	}

	@Test
	fun `모델을 부르지 않고 생성 한도를 쓰지 않으며 아무것도 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val tables = jdbc.queryForList(
			"select table_name from information_schema.tables where table_schema = 'public' and table_name not in ('flyway_schema_history', 'audit_logs', 'sessions', 'app_configs', 'app_maintenance', 'admin_accounts', 'admin_sessions', 'faqs')",
			String::class.java,
		)
		fun rows() = tables.associateWith { jdbc.queryForObject("select count(*) from \"$it\"", Long::class.java)!! }
		val before = rows()

		repeat(3) { assertEquals(200, month(token).statusCode()) }
		assertEquals(200, month(token, "?month=${YearMonth.now(korea).plusMonths(1)}").statusCode())

		assertEquals(0, generator.calls.get())
		assertTrue(quota.owners.isEmpty())
		assertEquals(before, rows(), "어느 테이블에도 행이 늘지 않는다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보와 계산 결과를 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		val json = mapper.readTree(month(token).body())
		for (secret in listOf("1977", "07:07") + json["days"].values().map { it["text"].asString() }) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		}
	}
}
