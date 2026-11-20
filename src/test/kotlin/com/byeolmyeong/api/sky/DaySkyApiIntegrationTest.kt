package com.byeolmyeong.api.sky

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.daySkyTransit
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.generation.dayCategory
import com.byeolmyeong.api.http
import com.byeolmyeong.api.web.GenerationQuota
import com.byeolmyeong.api.web.GenerationRateLimitedException
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
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val UNKNOWN = """{"date":"1990-03-15","time":null}"""
private const val FAILED = """{"error":"GENERATION_FAILED"}"""
private val PRACTICES = listOf("안부 한 줄 전하기", "할 일 하나 끝내 보기", "산책하며 숨 고르기")

/** 테스트가 보는 요청의 모습. [asksTitle] 은 돌려받을 구조에 제목 칸이 있는지(눈여겨볼 날이 아니면 모델이 제목을 쓴다). */
data class DaySkyPrompt(val purpose: String, val instructions: String, val facts: String, val asksTitle: Boolean)

/** 실제 생성기 대신 끼우는 가짜. 받은 요청을 기억하고, 테스트가 정한 대로 답한다. */
class FakeDaySkyGenerator : TextGenerator {
	private val mapper = JsonMapper()
	val prompts = CopyOnWriteArrayList<DaySkyPrompt>()

	@Volatile
	var respond: (DaySkyPrompt, Int) -> Map<String, Any?>? = ::validDaySky

	override fun generate(request: GenerationRequest): JsonNode? {
		val schema: JsonNode = mapper.valueToTree(request.schema)
		val prompt = DaySkyPrompt(request.purpose, request.instructions, request.userText, schema["properties"].has("title"))
		prompts += prompt
		return respond(prompt, prompts.size)?.let { mapper.valueToTree(it) }
	}

	fun reset() {
		prompts.clear()
		respond = ::validDaySky
	}
}

/** 검증을 통과하는 글. 부를 때마다 사람 쪽 흐름이 다르다([call] 번째 — 숫자 없이). 제목 칸을 물으면 제목도. */
fun validDaySky(prompt: DaySkyPrompt, call: Int): Map<String, Any?> = buildMap {
	if (prompt.asksTitle) put("title", "가짜 제목이 반가운 날")
	put("flow", mapOf("people" to "가짜사람${"하".repeat(call)}", "work" to "가짜일", "mind" to "가짜마음"))
	put("practices", PRACTICES)
}

/** 생성 한도를 센 소유자를 기억하고, [full] 이면 한도를 넘은 것으로 답한다. */
class SwitchQuota : GenerationQuota(100_000, 100_000) {
	val owners = CopyOnWriteArrayList<UUID>()

	@Volatile
	var full = false

	override fun acquire(ownerId: UUID) {
		owners += ownerId
		if (full) throw GenerationRateLimitedException()
	}
}

/** `POST /day-sky`(결정 034): 누른 날짜 · 오늘의 그날의 하늘을 한 번 만들어 저장하고 다시 열면 그대로 준다. */
@Import(TestcontainersConfiguration::class, DaySkyApiIntegrationTest.FakeConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class DaySkyApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun fakeDaySkyGenerator() = FakeDaySkyGenerator()

		@Bean
		@Primary
		fun daySkySwitchQuota() = SwitchQuota()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeDaySkyGenerator

	@Autowired
	private lateinit var quota: SwitchQuota

	@Autowired
	private lateinit var profiles: BirthProfileService

	@Autowired
	private lateinit var skies: DaySkyService

	private val mapper = JsonMapper()
	private val korea = ZoneId.of("Asia/Seoul")

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		generator.reset()
		quota.owners.clear()
		quota.full = false
	}

	private fun rows(): Int = jdbc.queryForObject("select count(*) from day_skies", Int::class.java)!!

	private fun ownerId(): UUID = jdbc.queryForObject("select id from accounts", UUID::class.java)!!

	private fun tokenWithProfile(profile: String): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	private fun sky(token: String?, date: LocalDate) = http(port, "POST", "/day-sky", """{"date":"$date"}""", token)

	private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

	private fun today(): LocalDate = LocalDate.now(korea)

	/** 지난달 · 이번 달 · 다음 달의 눈여겨볼 날(`GET /timing/month` 의 `days[]`). */
	private fun notableDays(token: String): List<JsonNode> {
		val thisMonth = YearMonth.now(korea)
		return listOf(thisMonth.minusMonths(1), thisMonth, thisMonth.plusMonths(1)).flatMap { month ->
			val response = http(port, "GET", "/timing/month?month=$month", token = token)
			assertEquals(200, response.statusCode(), response.body())
			json(response)["days"].values().toList()
		}
	}

	/** 앞뒤 한 달 안에서 눈여겨볼 날이 아닌 날들(오늘부터, 다음 달 말일까지). */
	private fun ordinaryDays(token: String): List<LocalDate> {
		val notable = notableDays(token).map { LocalDate.parse(it["date"].asString()) }.toSet()
		val last = YearMonth.now(korea).plusMonths(1).atEndOfMonth()
		return generateSequence(today()) { it.plusDays(1) }.takeWhile { it <= last }.filter { it !in notable }.toList()
	}

	private fun ordinaryDay(token: String): LocalDate = ordinaryDays(token).first()

	@Test
	fun `처음 요청은 만들어 저장하고 다시 요청하면 모델 · 한도 없이 저장본을 그대로 준다`() {
		val token = tokenWithProfile(KNOWN)
		val date = ordinaryDay(token)

		val first = sky(token, date)

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(1, generator.prompts.size)
		assertEquals(listOf(ownerId()), quota.owners, "새로 만들 때 한 번 센다")
		assertEquals(1, rows())
		val body = json(first)
		assertEquals(listOf("date", "title", "category", "flow", "reason", "practices", "createdAt"), body.propertyNames().toList())
		assertEquals(date.toString(), body["date"].asString())
		assertEquals("가짜 제목이 반가운 날", body["title"].asString(), "눈여겨볼 날이 아니면 모델이 제목을 쓴다")
		assertEquals(listOf("people", "work", "mind"), body["flow"].propertyNames().toList())
		assertEquals("가짜사람하", body["flow"]["people"].asString())
		assertEquals(PRACTICES.map { mapOf("title" to it) }, mapper.convertValue(body["practices"], List::class.java))
		val createdAt = body["createdAt"].asString()
		assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(createdAt), createdAt)
		assertEquals(
			jdbc.queryForObject("select created_at from day_skies", OffsetDateTime::class.java)!!.toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
			createdAt,
		)
		// 근거 · 칩은 서버가 정한다: 그날의 하늘 근거(chart.daySkyTransit)
		val chart = profiles.chartInput(ownerId())!!.chart()
		val transit = daySkyTransit(chart, date)
		assertEquals("DAY", body["reason"]["when"].asString())
		val item = body["reason"]["items"].single()
		assertEquals(listOf("kind", "mover", "target", "type"), item.propertyNames().toList())
		assertEquals(listOf("TRANSIT", transit.mover.name, transit.target, transit.type.name), listOf("kind", "mover", "target", "type").map { item[it].asString() })
		val category = if (transit.mover == Body.MOON) "MIND" else dayCategory(transit.mover, transit.target).name
		assertEquals(category, body["category"].asString())

		repeat(3) { assertEquals(first.body(), sky(token, date).body(), "같은 날은 같은 글") }
		assertEquals(1, generator.prompts.size, "저장본은 모델을 부르지 않는다")
		assertEquals(1, quota.owners.size, "저장본은 한도를 세지 않는다")
		assertEquals(1, rows())
	}

	@Test
	fun `눈여겨볼 날의 제목 · 칩 · 근거는 같은 달 이번 달의 그날 줄과 같고 모델은 제목을 쓰지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val days = notableDays(token)
		assertTrue(days.isNotEmpty(), "앞뒤 한 달에 눈여겨볼 날이 있다")

		for (day in days) {
			generator.reset()
			val response = sky(token, LocalDate.parse(day["date"].asString()))
			assertEquals(200, response.statusCode(), response.body())
			val body = json(response)
			assertEquals(day["text"].asString(), body["title"].asString(), "${day["date"]} 의 제목 = 이번 달 days[].text")
			assertEquals(day["category"], body["category"])
			assertEquals(day["reason"], body["reason"])
			val prompt = generator.prompts.single()
			assertFalse(prompt.asksTitle, "정해진 제목이면 돌려받을 구조에 제목 칸이 없다")
			assertTrue(prompt.facts.contains("[제목]\n${day["text"].asString()}"), prompt.facts)
		}
	}

	@Test
	fun `날짜가 없으면 한국 날짜의 오늘이다`() {
		val token = tokenWithProfile(KNOWN)

		val noBody = http(port, "POST", "/day-sky", token = token)

		assertEquals(200, noBody.statusCode(), noBody.body())
		assertEquals(today().toString(), json(noBody)["date"].asString())
		assertEquals(noBody.body(), http(port, "POST", "/day-sky", "{}", token).body(), "빈 객체도 오늘")
		assertEquals(noBody.body(), http(port, "POST", "/day-sky", """{"date":null}""", token).body())
		assertEquals(noBody.body(), sky(token, today()).body(), "오늘 날짜로 부른 것과 같은 글(오늘의 하늘 = 오늘의 그날의 하늘)")
		assertEquals(1, generator.prompts.size)
		assertEquals(1, rows())
	}

	@Test
	fun `지난달 1일부터 다음 달 말일까지만 받고 그 밖은 400 이며 형식 · 본문이 틀리면 400 이다`() {
		val token = tokenWithProfile(KNOWN)
		val thisMonth = YearMonth.now(korea)
		val first = thisMonth.minusMonths(1).atDay(1)
		val last = thisMonth.plusMonths(1).atEndOfMonth()
		for (allowed in listOf(first, last)) assertEquals(200, sky(token, allowed).statusCode(), "$allowed")
		for (far in listOf(first.minusDays(1), last.plusDays(1), today().plusYears(1))) {
			val response = sky(token, far)
			assertEquals(400, response.statusCode(), "$far")
			assertEquals("""{"error":"DATE_OUT_OF_RANGE"}""", response.body())
		}
		for (bad in listOf("", "2026-1-01", "20261121", "2026-02-30", "abc", "${today()}T00:00")) {
			val response = http(port, "POST", "/day-sky", """{"date":"$bad"}""", token)
			assertEquals(400, response.statusCode(), bad)
			assertEquals("""{"error":"INVALID_DATE"}""", response.body(), bad)
		}
		for (broken in listOf("{", "[]", "\"2026-11-21\"", """{"date":{}}""")) {
			val response = http(port, "POST", "/day-sky", broken, token)
			assertEquals(400, response.statusCode(), broken)
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body(), broken)
		}
		assertEquals(2, generator.prompts.size, "400 은 모델을 부르지 않는다")
		assertEquals(2, quota.owners.size, "400 은 한도를 세지 않는다")
		assertEquals(2, rows())
	}

	@Test
	fun `출생 정보가 없으면 404 이고 세션이 없으면 401 이다`() {
		assertEquals(401, sky(null, today()).statusCode())
		val token = devToken(port)
		val response = sky(token, today())
		assertEquals(404, response.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", response.body())
		assertEquals(0, generator.prompts.size)
		assertTrue(quota.owners.isEmpty(), "404 는 한도를 세지 않는다")
	}

	/** 같은 계정이 같은 날을 동시에 두 번 부른다. 두 요청이 생성기 안에서 겹치도록 생성기를 느리게 한다. */
	private fun skyTwiceAtOnce(token: String, date: LocalDate): List<HttpResponse<String>> {
		val start = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(2)
		try {
			val futures = (1..2).map { pool.submit<HttpResponse<String>> { start.await(); sky(token, date) } }
			start.countDown()
			return futures.map { it.get(60, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `같은 사람이 같은 날을 동시에 두 번 부르면 모델은 한 번만 불리고 둘 다 같은 글을 받는다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { prompt, call -> Thread.sleep(1500); validDaySky(prompt, call) }

		val (first, second) = skyTwiceAtOnce(token, today())

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(200, second.statusCode(), second.body())
		assertEquals(first.body(), second.body())
		assertEquals(1, generator.prompts.size, "뒤의 요청은 진행 중인 생성을 기다린다")
		assertEquals(1, quota.owners.size, "기다린 요청은 한도를 세지 않는다")
		assertEquals(1, rows())
	}

	@Test
	fun `틀린 글이 오면 다시 생성하고 두 번 더 틀리면 502 이며 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val (date, other) = ordinaryDays(token)
		generator.respond = { prompt, call ->
			val answer = validDaySky(prompt, call)
			if (call == 1) answer + ("practices" to listOf("안부 한 줄 전해요") + PRACTICES.drop(1)) else answer
		}
		val retried = sky(token, date)
		assertEquals(200, retried.statusCode(), retried.body())
		assertEquals(2, generator.prompts.size)
		assertEquals(generator.prompts[0], generator.prompts[1], "같은 요청으로 다시 부른다")
		assertEquals("가짜사람하하", json(retried)["flow"]["people"].asString())
		assertEquals(1, quota.owners.size, "다시 생성해도 한도는 한 번")

		fun flow(people: String = "가짜사람", work: String = "가짜일", mind: String = "가짜마음") = "flow" to mapOf("people" to people, "work" to work, "mind" to mind)
		val broken: List<Pair<String, (Map<String, Any?>) -> Map<String, Any?>?>> = listOf(
			"응답을 쓸 수 없음" to { _ -> null },
			"실천 끝맺음" to { a -> a + ("practices" to listOf("안부 한 줄 전해요") + PRACTICES.drop(1)) },
			"실천 2개" to { a -> a + ("practices" to PRACTICES.take(2)) },
			"실천 4개" to { a -> a + ("practices" to PRACTICES + "물 한 잔 마시기") },
			"빈 흐름" to { a -> a + flow(work = " ") },
			"흐름 칸 빠짐" to { a -> a + ("flow" to mapOf("people" to "가짜사람", "work" to "가짜일")) },
			"글 안의 숫자" to { a -> a + flow(people = "3시쯤 연락이 반가워요") },
			"단정" to { a -> a + flow(mind = "반드시 좋은 일이 생겨요") },
			"운명" to { a -> a + flow(people = "운명 같은 만남이 있어요") },
			"앞일 끝맺음" to { a -> a + flow(work = "좋은 소식이 올 거예요") },
			"건강" to { a -> a + flow(mind = "건강을 챙기면 좋아요") },
			"투자" to { a -> a + flow(work = "투자를 생각해 봐요") },
			"제목 빠짐" to { a -> a - "title" },
			"빈 제목" to { a -> a + ("title" to "") },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respond = { prompt, call -> breakIt(validDaySky(prompt, call)) }
			val response = sky(token, other)
			assertEquals(502, response.statusCode(), name)
			assertEquals(FAILED, response.body(), name)
			assertEquals(3, generator.prompts.size, "$name — 처음 1번 + 다시 2번")
			assertEquals(1, rows(), "$name — 저장하지 않는다")
		}

		// 실패는 남지 않는다: 다음 요청은 처음부터 다시 생성한다
		generator.reset()
		assertEquals(200, sky(token, other).statusCode())
		assertEquals(1, generator.prompts.size)
		assertEquals(2, rows())
	}

	@Test
	fun `생성 호출이 실패하면 다시 부르지 않고 502 이다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { _, _ -> throw GenerationException("HTTP_429") }

		val response = sky(token, today())

		assertEquals(502, response.statusCode())
		assertEquals(FAILED, response.body())
		assertEquals(1, generator.prompts.size, "한도 · 키 문제는 다시 불러도 같다")
		assertEquals(0, rows())
	}

	@Test
	fun `새로 만들 때 생성 한도를 넘으면 429 이고 모델을 부르지 않으며 저장본은 그대로 준다`() {
		val token = tokenWithProfile(KNOWN)
		val saved = sky(token, today())
		assertEquals(200, saved.statusCode())
		quota.full = true

		assertEquals(saved.body(), sky(token, today()).body(), "저장본은 한도를 보지 않는다")
		val over = sky(token, today().minusDays(1))

		assertEquals(429, over.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", over.body())
		assertEquals(1, generator.prompts.size)
		assertEquals(1, rows())
	}

	@Test
	fun `서버 안에서 부르는 만들기는 한도를 세지 않고 같은 저장본을 경로와 함께 쓴다`() {
		val token = tokenWithProfile(KNOWN)

		val made = skies.daySky(ownerId(), today(), countQuota = false)

		assertEquals(1, generator.prompts.size)
		assertTrue(quota.owners.isEmpty(), "한도를 세지 않는다")
		assertEquals(1, rows())
		val response = http(port, "POST", "/day-sky", token = token)
		assertEquals(200, response.statusCode())
		assertEquals(mapper.valueToTree<JsonNode>(made), json(response), "앱이 열면 그 저장본을 그대로 받는다")
		assertEquals(1, generator.prompts.size)
		assertTrue(quota.owners.isEmpty())
		// 이미 있으면 다시 만들지 않는다
		assertEquals(made, skies.daySky(ownerId(), today(), countQuota = false))
		assertEquals(1, generator.prompts.size)
	}

	@Test
	fun `출생 정보를 바꾸면 옛 차트의 글을 지우고 새 차트의 글을 만들며 되돌려도 다시 만든다`() {
		val token = tokenWithProfile(KNOWN)
		val original = sky(token, today()).body()

		assertEquals(200, http(port, "PUT", "/profile", UNKNOWN, token).statusCode())
		assertEquals(0, rows(), "바꾸면 옛 차트의 그날의 하늘을 지운다(결정 041)")
		val changed = sky(token, today())
		assertEquals(200, changed.statusCode())
		assertNotEquals(original, changed.body())
		assertEquals(2, generator.prompts.size)
		assertEquals(1, rows())

		assertEquals(200, http(port, "PUT", "/profile", KNOWN, token).statusCode())
		assertEquals(200, sky(token, today()).statusCode())
		assertEquals(3, generator.prompts.size, "옛 글은 지워졌으니 다시 만든다")
		assertEquals(1, rows())
	}

	@Test
	fun `모델에게는 차트 글과 그날의 통과 · 이유 · 칩 · 제목을 주고 날짜 · 도수는 주지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val date = ordinaryDay(token)
		assertEquals(200, sky(token, date).statusCode())

		val prompt = generator.prompts.single()
		assertEquals("day_sky", prompt.purpose)
		assertTrue(prompt.asksTitle)
		assertTrue(prompt.facts.startsWith("[천체]"), "차트 글이 먼저")
		val day = prompt.facts.substringAfter("[그날의 통과]")
		for (section in listOf("[이유]", "[칩]", "[제목]")) assertTrue(day.contains(section), section)
		assertFalse(day.any { it.isDigit() }, "그날의 재료에 날짜 · 도수가 없다:\n$day")
		assertFalse(prompt.facts.contains(date.toString()) || prompt.facts.contains("°"))
		assertTrue(prompt.instructions.contains("그날의 하늘"), "그날의 하늘 지시문")
		assertTrue(prompt.instructions.contains("실천"), "공통 글쓰기 규칙")
		assertFalse(prompt.instructions.contains("태어난 시각을 모르는 사람입니다"))
	}

	@Test
	fun `시각을 모르면 응답에 상승점 · 중천 · 하우스가 없고 시각 모름 지시문을 붙인다`() {
		val token = tokenWithProfile(UNKNOWN)
		val thisMonth = YearMonth.now(korea)
		val dates = generateSequence(thisMonth.minusMonths(1).atDay(1)) { it.plusDays(7) }.takeWhile { it <= thisMonth.plusMonths(1).atEndOfMonth() }.toList()

		for (date in dates) {
			val response = sky(token, date)
			assertEquals(200, response.statusCode(), response.body())
			for (word in listOf("\"ASC\"", "\"MC\"", "\"house\"", "VERTEX")) assertFalse(response.body().contains(word), "$date: $word")
		}
		val prompt = generator.prompts.first()
		assertTrue(prompt.instructions.contains("태어난 시각을 모르는 사람입니다"))
		for (word in listOf("상승점", "중천", "하우스")) assertFalse(prompt.facts.contains(word), word)
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보와 차트 글 · 만든 글을 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		generator.respond = { prompt, call -> if (call == 1) null else validDaySky(prompt, call) }

		val response = sky(token, today())

		assertEquals(200, response.statusCode())
		val prompt = generator.prompts.first()
		val chartLines = prompt.facts.lines().filter { ": " in it || "–" in it }
		assertTrue(chartLines.isNotEmpty())
		val written = json(response)
		val secrets = listOf("1977", "07:07") + chartLines + written["title"].asString() + written["flow"].values().map { it.asString() }
		for (secret in secrets) assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		assertTrue(output.all.contains("event=day_sky_attempt_rejected"), "다시 생성한 사유 코드는 남긴다")
	}
}
