package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.http
import org.junit.jupiter.api.AfterEach
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MY_SIDE_WORDS = listOf("태양", "달", "수성", "금성", "화성", "상승점")
private val PARTNER_BODY = directBody(name = "지우", relation = "LOVE", date = "1988-07-21", time = "06:10", placeId = MAPO)

private fun started(status: String) = Regex("""\{"id":"[0-9a-f-]{36}","status":"$status"}""")

/** 궁합 한 번에 만들기(결정 053 의 3): 202 로 답하고 뒤에서 요약 다음 네 영역을 써서 모두 통과하면 DONE, 아니면 FAILED · 다시 하기. */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	// 살아 있다는 표시(`active_at`)를 적는 주기를 길게 둬, 테스트가 그 값을 옮겨 놓은 사이에 다시 적히지 않게 한다.
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000", "byeolmyeong.report.heartbeat-seconds=3600"],
)
class SynastryGenerationIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	/** 뒤에서 도는 생성이 모두 끝날 때까지 기다린다. */
	private fun settle() {
		generator.gate?.countDown()
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline) { "뒤에서 도는 생성이 끝나지 않는다" }
			Thread.sleep(10)
		}
	}

	@BeforeEach
	@AfterEach
	fun clean() {
		settle()
		jdbc.update("delete from accounts")
		generator.reset()
	}

	private fun reportRows(): Int = jdbc.queryForObject("select count(*) from synastry_reports", Int::class.java)!!

	private fun storedBody(): String? = jdbc.queryForList("select body::text from synastry_reports", String::class.java).firstOrNull()

	private fun tokenWithProfile(profile: String = ME): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	/** 궁합 하나를 만들기 시작하고(202) id 를 준다. 상대는 [PARTNER_BODY] 다. */
	private fun start(token: String, body: String = PARTNER_BODY): String {
		val response = http(port, "POST", "/synastry", body, token)
		assertEquals(202, response.statusCode(), response.body())
		return mapper.readTree(response.body())["id"].asString()
	}

	private fun retry(token: String, id: String) = http(port, "POST", "/synastry/$id/retry", token = token)

	private fun get(token: String, id: String): JsonNode = mapper.readTree(http(port, "GET", "/synastry/$id", token = token).body())

	private fun closeGate(): CountDownLatch = CountDownLatch(1).also { generator.gate = it }

	private fun await(token: String, id: String, expected: String): JsonNode = awaitSynastry(port, token, id, expected)

	private fun awaitAreaCalls(count: Int) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (generator.areas.size < count) {
			check(System.nanoTime() < deadline) { "생성기가 요청을 받지 않는다" }
			Thread.sleep(10)
		}
	}

	@Test
	fun `만들기는 기다리지 않고 202 를 주고 뒤에서 요약 다음 네 영역을 써서 DONE 이 된다`() {
		val token = tokenWithProfile()
		val gate = closeGate()

		val posted = http(port, "POST", "/synastry", PARTNER_BODY, token)

		assertEquals(202, posted.statusCode(), posted.body())
		assertTrue(started("WRITING").matches(posted.body()), posted.body())
		val id = mapper.readTree(posted.body())["id"].asString()
		awaitAreaCalls(4)
		val writing = get(token, id)
		assertEquals("WRITING", writing["status"].asString())
		assertTrue(writing.has("score"), "점수는 넣을 때 서버가 계산해 둔다")
		for (key in listOf("headline", "keywords", "reasons", "report")) assertFalse(writing.has(key), "쓰는 중에는 '$key' 이 없다")
		assertNull(storedBody(), "완성 전에는 본문이 없다")
		// 요약을 먼저 쓰고, 그다음 네 영역을 동시에 쓴다(넷 모두 문 앞에서 기다리고 있다)
		assertEquals(SUMMARY_PURPOSE, generator.requests.first().purpose)
		assertEquals(REPORT_AREAS.sorted(), generator.areas.map(::areaOf).sorted())

		gate.countDown()
		val done = await(token, id, "DONE")

		assertEquals("가짜한줄요약", done["headline"].asString())
		val sections = done["report"]["sections"].values().toList()
		assertEquals(REPORT_AREAS, sections.map { it["area"].asString() })
		for (section in sections) {
			assertEquals("가짜영역제목", section["title"].asString())
			assertEquals(3, section["paragraphs"].size())
			for (paragraph in section["paragraphs"].values()) {
				assertTrue(paragraph["basis"].values().map { it.asString() }.all { it in MY_SIDE_WORDS }, paragraph["basis"].toString())
				assertEquals(setOf("title", "body", "basis"), paragraph.propertyNames().toSet())
			}
		}
		// 이 사이의 작은 실천 셋은 오래가기 영역에만 있다
		assertEquals(PRACTICES, sections.last()["practices"].values().map { it.asString() })
		assertTrue(sections.dropLast(1).none { it.has("practices") })
		assertEquals(done["report"], mapper.readTree(storedBody()))
		// 완성되면 알림함에 궁합 줄이 그 궁합 id 로 하나 생긴다(결정 035 — SynastryReportDone)
		val until = System.currentTimeMillis() + 5_000
		fun notified() = jdbc.queryForList("select kind || ':' || synastry_id from notifications", String::class.java)
		while (notified().isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
		assertEquals(listOf("SYNASTRY_REPORT_DONE:$id"), notified())
		// 완성된 궁합은 다시 하기를 불러도 새로 쓰지 않는다
		val again = retry(token, id)
		assertEquals(202, again.statusCode())
		assertTrue(started("DONE").matches(again.body()), again.body())
		assertEquals(5, generator.requests.size)
	}

	@Test
	fun `네 영역의 재료는 요약과 같은 서버가 계산한 연결과 점수이고 근거는 내 쪽 요소뿐이며 실천은 오래가기에서만 받는다`() {
		val token = tokenWithProfile()
		val id = start(token, directBody(name = "지우", relation = "FRIEND", date = "1988-07-21", time = "06:10", placeId = MAPO))

		val done = await(token, id, "DONE")

		val score = done["score"].asInt()
		for (request in generator.areas) {
			assertEquals(MY_SIDE_WORDS, vocabularyOf(request))
			assertTrue(request.userText.contains("${score}점"))
			assertTrue(request.userText.contains("[근거로 쓸 수 있는 말]"))
			assertTrue(request.userText.contains("친구"), request.userText)
			assertTrue(request.instructions.contains("점성술 용어를 하나도 쓰지 않습니다"))
			assertEquals(generator.summaries.single().userText, request.userText.substringBefore("\n\n[근거로 쓸 수 있는 말]"), "요약과 같은 재료다")
			val properties = (request.schema["properties"] as Map<*, *>).keys
			if (areaOf(request) == "LASTING") {
				assertEquals(setOf("title", "paragraphs", "practices"), properties)
				assertTrue(request.instructions.contains("practices") && request.instructions.contains("\"기\"로 끝납니다"))
			} else {
				assertEquals(setOf("title", "paragraphs"), properties)
			}
		}
		// 영역마다 지시문이 다르다
		assertEquals(4, generator.areas.map { it.instructions }.toSet().size)
		assertEquals(REPORT_AREAS.map { "synastry_report_${it.lowercase()}" }.sorted(), generator.areas.map { it.purpose }.sorted())
	}

	@Test
	fun `내 시각을 모르면 근거에 상승점이 없다`() {
		val token = tokenWithProfile("""{"date":"$MY_DATE","time":null,"placeId":$MAPO}""")
		val id = start(token)

		await(token, id, "DONE")

		assertEquals(MY_SIDE_WORDS - "상승점", vocabularyOf(generator.areas.first()))
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `글을 쓴 뒤에도 로그에 상대의 출생 정보와 글이 없고 기다린 시간을 남긴다`(output: CapturedOutput) {
		val token = tokenWithProfile()
		val id = start(token)

		await(token, id, "DONE")
		settle()

		val noise = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{4}-\d{2}-\d{2}[T ][\d:.]+|[0-9a-f]{64}""")
		val logged = noise.replace(output.all, "")
		for (secret in listOf("1988", "07-21", "06:10", "지우", "가짜영역제목", "가짜본문", "가짜문단제목", "가짜실천", "상대의 ", "나의 ")) {
			assertFalse(logged.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(logged.contains("event=synastry_created"))
		assertTrue(Regex("""event=synastry_report_done attempts=5 elapsed_ms=\d+""").containsMatchIn(logged), "한 번에 만들기의 기다림을 잰다")
	}

	@Test
	fun `요약을 만들지 못하면 네 영역을 부르지 않고 FAILED 이며 다시 하기는 같은 행을 처음부터 쓴다`() {
		val token = tokenWithProfile()

		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		val failedId = start(token)
		val failed = await(token, failedId, "FAILED")
		settle()
		assertEquals(1, generator.requests.size, "호출 자체의 실패는 다시 부르지 않는다")
		assertFalse(failed.has("headline"))

		val unusable: List<(Int) -> JsonNode?> = listOf(
			{ null },
			{ summaryJson(keywords = listOf("하나", "둘")) },
			{ summaryJson(keywords = listOf("하나", "둘", "셋", "넷")) },
			{ summaryJson(keywords = listOf("하나", "하나", "둘")) },
			{ summaryJson(keywords = listOf("하나", " ", "둘")) },
			{ summaryJson(headline = " ") },
			{ summaryJson(headline = "두 사람은 95점짜리 사이예요") },
			{ summaryJson(keywords = listOf("하나", "둘", "100점")) },
			{ summaryJson(headline = "가".repeat(101)) },
			{ mapper.readTree("""{"keywords":"하나","headline":"한 줄"}""") },
			{ mapper.readTree("""{"keywords":["하나","둘","셋"]}""") },
		)
		for ((index, answer) in unusable.withIndex()) {
			generator.reset()
			generator.respond = { _, call -> answer(call) }
			val id = start(token)
			await(token, id, "FAILED")
			settle()
			assertEquals(3, generator.summaries.size, "경우 $index: 처음 한 번 + 다시 2번")
			assertTrue(generator.areas.isEmpty(), "경우 $index: 요약이 없으면 네 영역을 부르지 않는다")
		}
		assertEquals(0, reportRows())
		assertEquals(0, jdbc.queryForObject("select count(*) from synastries where headline is not null", Int::class.java))

		// 다시 하기: 같은 행에서 처음부터 쓴다. 한 번 틀리고 다시 써서 맞으면 완성된다.
		generator.reset()
		generator.respond = { request, call ->
			if (request.purpose == SUMMARY_PURPOSE && call == 1) summaryJson(headline = " ") else if (request.purpose == SUMMARY_PURPOSE) summaryJson(headline = "다시쓴한줄") else validAnswer(request, call)
		}
		val retried = retry(token, failedId)
		assertEquals(202, retried.statusCode())
		assertEquals("""{"id":"$failedId","status":"WRITING"}""", retried.body())
		assertEquals("다시쓴한줄", await(token, failedId, "DONE")["headline"].asString())
		assertEquals(2, generator.summaries.size)
		assertEquals(4, generator.areas.size)
		assertEquals(1 + unusable.size, jdbc.queryForObject("select count(*) from synastries", Int::class.java), "다시 하기는 새 행을 만들지 않는다")
	}

	@Test
	fun `한 영역이라도 만들지 못하면 FAILED 이고 아무것도 저장하지 않으며 다시 하기로 새로 쓴다`() {
		val token = tokenWithProfile()
		generator.respond = { request, call ->
			if (request.purpose == "synastry_report_conflict") throw GenerationException("HTTP_500") else validAnswer(request, call)
		}
		val id = start(token)

		val failed = await(token, id, "FAILED")
		settle()

		assertNull(storedBody(), "받아 둔 영역도 저장하지 않는다")
		assertFalse(failed.has("headline"), "받아 둔 요약도 저장하지 않는다")
		assertFalse(failed.has("report"))

		generator.reset()
		assertTrue(started("WRITING").matches(retry(token, id).body()))
		await(token, id, "DONE")
		assertEquals(4, generator.areas.size)
	}

	@Test
	fun `틀린 영역은 그 영역만 다시 쓰고 세 번 틀리면 FAILED 다`() {
		val token = tokenWithProfile()
		val bad: List<(GenerationRequest, Int) -> JsonNode?> = listOf(
			{ _, _ -> null },
			{ request, call -> areaJson(request, call, count = 2) },
			{ request, call -> areaJson(request, call, count = 5) },
			{ request, call -> areaJson(request, call, basis = emptyList()) },
			// 상대 쪽 요소나 내 쪽에서 보지 않는 요소는 근거가 될 수 없다
			{ request, call -> areaJson(request, call, basis = listOf("토성")) },
			{ request, call -> areaJson(request, call, basis = listOf("상대의 금성")) },
			{ request, _ -> mapper.valueToTree(mapOf("title" to "제목", "paragraphs" to (1..3).map { mapOf("title" to "제목", "body" to "궁합 87점", "basis" to vocabularyOf(request).take(1)) })) },
			{ request, _ -> mapper.valueToTree(mapOf("title" to " ", "paragraphs" to (1..3).map { mapOf("title" to "제목", "body" to "본문", "basis" to vocabularyOf(request).take(1)) })) },
		)
		// 오래가기의 작은 실천: 셋이 아니다 · "기" 로 끝나지 않는다 · 앞일을 단정한다 · 없다
		val badPractices: List<(GenerationRequest, Int) -> JsonNode?> = listOf(
			{ request, call -> areaJson(request, call, practices = PRACTICES.take(2)) },
			{ request, call -> areaJson(request, call, practices = PRACTICES.take(2) + "고마운 점을 말해요") },
			{ request, call -> areaJson(request, call, practices = PRACTICES.take(2) + "반드시 같이 산책하기") },
			{ request, call -> (areaJson(request, call) as tools.jackson.databind.node.ObjectNode).also { it.remove("practices") } },
		)
		for ((index, case) in (bad.map { "TALK" to it } + bad.take(1).map { "LASTING" to it } + badPractices.map { "LASTING" to it }).withIndex()) {
			val (area, answer) = case
			generator.reset()
			generator.respond = { request, call -> if (request.purpose != SUMMARY_PURPOSE && areaOf(request) == area) answer(request, call) else validAnswer(request, call) }
			val id = start(token)
			await(token, id, "FAILED")
			settle()
			assertEquals(3, generator.areas.count { areaOf(it) == area }, "경우 $index($area)")
			assertTrue(generator.areas.count { areaOf(it) != area } <= 3, "경우 $index: 다른 영역은 한 번씩만")
		}
		assertEquals(0, jdbc.queryForObject("select count(*) from synastry_reports where body is not null", Int::class.java))

		// 한 번 틀리고 다시 써서 맞으면 완성된다
		generator.reset()
		var talkCalls = 0
		generator.respond = { request, call ->
			when {
				request.purpose == SUMMARY_PURPOSE -> summaryJson()
				areaOf(request) == "TALK" && ++talkCalls == 1 -> areaJson(request, call, count = 2)
				else -> areaJson(request, call, count = 4)
			}
		}
		val id = start(token)
		val done = await(token, id, "DONE")
		assertEquals(5, generator.areas.size)
		assertEquals(listOf(4, 4, 4, 4), done["report"]["sections"].values().map { it["paragraphs"].size() })
	}

	@Test
	fun `쓰는 도중 서버가 꺼진 궁합은 살아 있다는 표시가 2분 끊기면 FAILED 로 보이고 다시 하기로 새로 쓴다`() {
		val token = tokenWithProfile()
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		val id = start(token)
		await(token, id, "FAILED")
		settle()
		generator.reset()
		// 다른 서버(또는 재시작하기 전의 이 서버)가 30분 전에 자리를 잡고 쓰다가 꺼졌다: 이 서버에는 그 생성이 없다
		jdbc.update("update synastries set status = 'WRITING', started_at = now() - interval '30 minutes', active_at = now() - interval '1 minute'")

		assertEquals("WRITING", get(token, id)["status"].asString(), "표시가 끊긴 지 2분이 안 됐으면 쓰는 중으로 본다")
		assertTrue(started("WRITING").matches(retry(token, id).body()))
		settle()
		assertTrue(generator.requests.isEmpty(), "다시 시작하지 않았다")

		jdbc.update("update synastries set active_at = now() - interval '3 minutes'")
		assertEquals("FAILED", get(token, id)["status"].asString(), "멈춘 것으로 본다")
		assertEquals("FAILED", mapper.readTree(http(port, "GET", "/synastry", token = token).body())[0]["status"].asString(), "목록에서도")
		assertTrue(started("WRITING").matches(retry(token, id).body()), "다시 시작한다")
		await(token, id, "DONE")
		settle()
		assertEquals(5, generator.requests.size)
		assertEquals(1, reportRows())
	}

	@Test
	fun `다른 생성이 자리를 잡으면 옛 생성은 모델을 더 부르지 않고 그만두며 덮어쓰지도 못한다`() {
		val token = tokenWithProfile()
		val gate = closeGate()
		// 옛 생성이 받을 영역 답(호출 2~5번, 1번은 요약)은 모두 틀렸다(문단 2개): 자리를 잃지 않았다면 다시 불렀을 것이다
		generator.respond = { request, call -> if (request.purpose == SUMMARY_PURPOSE) summaryJson() else areaJson(request, call, count = if (call <= 5) 2 else 4) }
		val id = start(token)
		awaitAreaCalls(4)

		jdbc.update("update synastries set active_at = now() - interval '3 minutes'")
		assertEquals("FAILED", get(token, id)["status"].asString())
		assertTrue(started("WRITING").matches(retry(token, id).body()), "다시 시작한다")

		gate.countDown()
		val done = await(token, id, "DONE")
		settle()
		assertEquals(8, generator.areas.size, "옛 생성이 이미 부른 4번 + 다시 시작한 4번. 옛 생성은 틀린 답을 받고도 다시 부르지 않는다")
		assertEquals(2, generator.summaries.size)
		assertEquals(listOf(4, 4, 4, 4), done["report"]["sections"].values().map { it["paragraphs"].size() }, "새 생성의 글이 남는다")
		assertEquals(done["report"], mapper.readTree(storedBody()))
	}

	@Test
	fun `줄에서 기다리던 생성이 자리를 잃으면 차례가 와도 모델을 부르지 않는다`() {
		val token = tokenWithProfile()
		val gate = closeGate()
		// 일꾼 다섯이 모두 앞의 두 궁합의 영역을 붙잡고 있다(넷 + 하나). 셋째 궁합의 요약은 줄에서 기다린다
		val first = start(token)
		awaitAreaCalls(4)
		val second = start(token)
		awaitAreaCalls(5)
		val waiting = start(token)
		assertEquals(2, generator.summaries.size)

		jdbc.update("update synastries set active_at = now() - interval '3 minutes' where id = ?", UUID.fromString(waiting))
		assertTrue(started("WRITING").matches(retry(token, waiting).body()), "다른 생성이 자리를 잡는다")

		gate.countDown()
		for (id in listOf(first, second, waiting)) await(token, id, "DONE")
		settle()
		assertEquals(3, generator.summaries.size, "자리를 잃은 생성은 요약도 부르지 않는다")
		assertEquals(12, generator.areas.size, "궁합 셋의 네 영역씩")
	}

	@Test
	fun `동시에 여러 번 다시 하기를 불러도 한 번만 쓴다`() {
		val token = tokenWithProfile()
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		val id = start(token)
		await(token, id, "FAILED")
		settle()
		generator.reset()
		val gate = closeGate()

		val responses = (1..6).map { CompletableFuture.supplyAsync { retry(token, id) } }.map { it.join() }

		assertTrue(responses.all { it.statusCode() == 202 && started("WRITING").matches(it.body()) }, responses.map { it.body() }.toString())
		gate.countDown()
		await(token, id, "DONE")
		settle()
		assertEquals(1, generator.summaries.size)
		assertEquals(4, generator.areas.size)
	}

	@Test
	fun `내 출생 정보를 바꾸면 직접 넣은 궁합의 점수를 바로 다시 계산하고 글은 그 궁합을 열 때 다시 쓴다`() {
		val token = tokenWithProfile()
		val id = start(token)
		val before = await(token, id, "DONE")
		assertEquals(expectedScore("1988-07-21", com.byeolmyeong.api.chart.Relation.LOVE, partnerTime = "06:10"), before["score"].asInt())
		// 1.0.0 에 상대 출생 정보 없이 만든 옛 궁합은 다시 계산할 수 없어 그대로 둔다
		val me = jdbc.queryForObject("select id from accounts", UUID::class.java)!!
		val legacy = UUID.randomUUID()
		jdbc.update(
			"insert into synastries (id, owner_id, name, relation, score, headline, keywords, owner_chart_hash) values (?, ?, '옛사람', 'LOVE', 70, '옛한줄', '[\"a\",\"b\",\"c\"]'::jsonb, 'old-hash')",
			legacy, me,
		)
		val newDate = "1993-11-02"
		val newTime = "08:45"
		val newScore = com.byeolmyeong.api.chart.synastryScore(chartOf(newDate, newTime), chartOf("1988-07-21", "06:10"), com.byeolmyeong.api.chart.Relation.LOVE)
		check(newScore != before["score"].asInt()) { "점수가 달라지는 새 생일을 골라야 한다" }
		settle()
		generator.reset()

		assertEquals(200, http(port, "PUT", "/profile", """{"date":"$newDate","time":"$newTime","placeId":$MAPO}""", token).statusCode())

		// 점수는 바로 바뀐다(목록은 다시 쓰기를 시작하지 않는다)
		val listed = mapper.readTree(http(port, "GET", "/synastry", token = token).body()).values().associateBy { it["id"].asString() }
		assertEquals(newScore, listed.getValue(id)["score"].asInt())
		assertEquals("DONE", listed.getValue(id)["status"].asString())
		assertEquals("가짜한줄요약", listed.getValue(id)["headline"].asString(), "글은 아직 옛 것이다")
		assertEquals(70, listed.getValue(legacy.toString())["score"].asInt())
		assertTrue(generator.requests.isEmpty())

		// 그 궁합을 열면 다시 쓰기 시작한다
		val opened = get(token, id)
		assertEquals("WRITING", opened["status"].asString())
		assertEquals(newScore, opened["score"].asInt())
		val rewritten = await(token, id, "DONE")
		settle()
		assertEquals(newScore, rewritten["score"].asInt())
		assertEquals(5, generator.requests.size)
		assertTrue(generator.summaries.single().userText.contains("${newScore}점"), "새 차트 · 새 점수로 쓴다")
		// 다시 연다고 또 쓰지는 않는다. 옛 궁합은 열어도 그대로다
		assertEquals("DONE", get(token, id)["status"].asString())
		assertEquals("DONE", get(token, legacy.toString())["status"].asString())
		settle()
		assertEquals(5, generator.requests.size)
	}

	@Test
	fun `궁합이나 계정을 지우면 글도 지워진다`() {
		val token = tokenWithProfile()
		val id = start(token)
		await(token, id, "DONE")
		assertEquals(1, reportRows())

		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		assertEquals(0, reportRows())
	}
}

/**
 * 뒤에서 일할 자리(심층 리포트와 같이 쓰는 일꾼)가 꽉 찼을 때. 자리는 5개(일하는 1 + 기다리는 4)이고 궁합 하나의 일은 요약 1 + 영역 4 다:
 * 앞의 궁합이 영역 넷(일하는 1 + 기다리는 3)을, 다음 궁합의 요약이 남은 한 자리를 쓰면 그다음 궁합은 요약부터 거절된다.
 */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.worker-threads=1", "byeolmyeong.report.queue-capacity=4"],
)
class SynastryExecutorFullIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private fun until(what: String, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (!condition()) {
			check(System.nanoTime() < deadline) { what }
			Thread.sleep(10)
		}
	}

	private fun idle() = until("뒤에서 도는 생성이 끝나지 않는다") { reportExecutor.activeCount == 0 && reportExecutor.queueSize == 0 }

	@Test
	fun `일할 자리가 없으면 FAILED 로 답하고 모델을 부르지 않으며 자리가 나면 다시 하기로 쓸 수 있다`() {
		jdbc.update("delete from accounts")
		generator.reset()
		val token = devToken(port)
		http(port, "PUT", "/profile", ME, token)
		fun post() = http(port, "POST", "/synastry", directBody(), token)
		fun status(id: String) = mapper.readTree(http(port, "GET", "/synastry/$id", token = token).body())["status"].asString()
		val gate = CountDownLatch(1).also { generator.gate = it }

		// 앞의 궁합: 요약을 쓴 뒤 영역 하나는 일꾼이 붙잡고(생성기가 답하지 않는다) 셋은 줄에 있다
		val first = mapper.readTree(post().body())["id"].asString()
		until("앞 궁합의 영역이 시작되지 않는다") { generator.areas.size == 1 && reportExecutor.queueSize == 3 }
		// 다음 궁합: 요약이 줄의 남은 한 자리에 선다
		val second = mapper.readTree(post().body())["id"].asString()
		assertEquals(4, reportExecutor.queueSize)

		// 그다음 궁합: 요약부터 거절된다
		val rejected = post()

		assertEquals(202, rejected.statusCode(), rejected.body())
		val third = mapper.readTree(rejected.body())
		assertEquals("FAILED", third["status"].asString())
		val thirdId = third["id"].asString()
		assertEquals("FAILED", status(thirdId))
		assertEquals("WRITING", status(first), "앞의 궁합은 그대로 쓰는 중이다")

		gate.countDown()
		until("앞의 두 궁합이 완성되지 않는다") { status(first) == "DONE" && status(second) == "DONE" }
		idle()

		assertEquals(2, generator.summaries.size, "거절된 궁합은 모델을 부르지 않는다")
		assertEquals(8, generator.areas.size)

		// 자리가 났다
		assertEquals("WRITING", mapper.readTree(http(port, "POST", "/synastry/$thirdId/retry", token = token).body())["status"].asString())
		until("거절됐던 궁합이 완성되지 않는다") { status(thirdId) == "DONE" }
		idle()
		assertEquals(12, generator.areas.size)
	}
}

/**
 * 앞의 궁합 때문에 줄에서 기다리는 궁합. 일꾼이 하나라 뒤 궁합의 일은 앞의 것이 끝날 때까지 시작도 못 한다.
 * 기다린 시간만으로는 멈춘 것이 아니다: 서버가 살아 있는 동안은 살아 있다는 표시(`active_at`)를 계속 적는다(여기서는 1초마다).
 */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000",
		"byeolmyeong.report.worker-threads=1", "byeolmyeong.report.heartbeat-seconds=1",
	],
)
class SynastryQueueWaitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private fun until(what: String, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (!condition()) {
			check(System.nanoTime() < deadline) { what }
			Thread.sleep(10)
		}
	}

	private fun settle() {
		generator.gate?.countDown()
		until("뒤에서 도는 생성이 끝나지 않는다") { reportExecutor.activeCount == 0 && reportExecutor.queueSize == 0 }
	}

	@BeforeEach
	@AfterEach
	fun clean() {
		settle()
		jdbc.update("delete from accounts")
		generator.reset()
	}

	private fun status(token: String, id: String) = mapper.readTree(http(port, "GET", "/synastry/$id", token = token).body())["status"].asString()

	/** 앞 궁합의 영역이 하나뿐인 일꾼을 붙잡고 있고(생성기가 답하지 않는다), 뒤 궁합은 줄에서 기다린다. 토큰과 뒤 궁합의 id 를 준다. */
	private fun waitingBehindAnother(): Pair<String, String> {
		val token = devToken(port)
		http(port, "PUT", "/profile", ME, token)
		generator.gate = CountDownLatch(1)
		fun post() = mapper.readTree(http(port, "POST", "/synastry", PARTNER_BODY, token).body())["id"].asString()
		post()
		until("앞 궁합의 영역이 시작되지 않는다") { generator.areas.size == 1 }
		val second = post()
		assertEquals("WRITING", status(token, second))
		return token to second
	}

	@Test
	fun `줄에서 10분 넘게 기다린 궁합도 WRITING 이고 차례가 오면 완성된다`() {
		val (token, second) = waitingBehindAnother()

		// 자리를 잡은 지 11분이 지났다
		jdbc.update("update synastries set started_at = started_at - interval '11 minutes' where id = ?", UUID.fromString(second))

		assertEquals("WRITING", status(token, second), "기다린 시간만으로 멈춘 것으로 보지 않는다")
		assertEquals("WRITING", mapper.readTree(http(port, "POST", "/synastry/$second/retry", token = token).body())["status"].asString(), "다시 시작하지도 않는다")

		// started_at 은 그 생성의 표식이기도 하다(끝낼 때 자기 것인지 가린다). 일이 시작되기 전에 되돌려 둔다
		jdbc.update("update synastries set started_at = started_at + interval '11 minutes' where id = ?", UUID.fromString(second))
		generator.gate!!.countDown()
		until("뒤 궁합이 완성되지 않는다") { status(token, second) == "DONE" }
		settle()

		assertEquals(2, generator.summaries.size)
		assertEquals(8, generator.areas.size, "두 궁합의 네 영역을 한 번씩만 불렀다")
	}

	@Test
	fun `서버가 살아 있으면 줄에서 기다리는 궁합에도 살아 있다는 표시를 계속 적는다`() {
		val (token, second) = waitingBehindAnother()

		// 표시가 오래 끊긴 것처럼 옮겨 놓아도 다음 주기에 다시 적힌다(쓰는 중인 것도, 기다리는 것도)
		jdbc.update("update synastries set active_at = now() - interval '11 minutes'")
		until("살아 있다는 표시가 다시 적히지 않는다") {
			jdbc.queryForObject("select count(*) from synastries where active_at > now() - interval '10 seconds'", Int::class.java) == 2
		}
		assertEquals("WRITING", status(token, second))

		generator.gate!!.countDown()
		until("뒤 궁합이 완성되지 않는다") { status(token, second) == "DONE" }
		settle()
		assertEquals(8, generator.areas.size)
	}
}
