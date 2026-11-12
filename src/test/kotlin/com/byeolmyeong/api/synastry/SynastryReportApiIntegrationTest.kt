package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
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

private const val NONE = """{"status":"NONE"}"""
private const val WRITING = """{"status":"WRITING"}"""
private const val FAILED = """{"status":"FAILED"}"""
private const val DONE = """{"status":"DONE"}"""
private const val PARTNER = """{"date":"1988-07-21","time":"06:10","placeId":$MAPO}"""
private val MY_SIDE_WORDS = listOf("태양", "달", "수성", "금성", "화성", "상승점")

/** 궁합 리포트(끌림·대화·갈등·오래가기): 상대의 출생 정보를 다시 받아 뒤에서 쓰고, 글만 저장한다(결정 010). */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	// 살아 있다는 표시(`active_at`)를 적는 주기를 길게 둬, 테스트가 그 값을 옮겨 놓은 사이에 다시 적히지 않게 한다.
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000", "byeolmyeong.report.heartbeat-seconds=3600"],
)
class SynastryReportApiIntegrationTest {

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

	private fun storedBody(): String? = jdbc.queryForObject("select body::text from synastry_reports", String::class.java)

	private fun tokenWithProfile(profile: String = ME): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	/** 궁합 하나를 만들고 id 를 준다. 상대는 [PARTNER] 다. */
	private fun synastry(token: String, relation: String = "LOVE"): String {
		val response = http(port, "POST", "/synastry/direct", directBody(relation = relation, date = "1988-07-21", time = "06:10", placeId = MAPO), token)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())["id"].asString()
	}

	private fun post(token: String, id: String, body: String? = PARTNER) = http(port, "POST", "/synastry/$id/report", body, token)

	private fun get(token: String, id: String) = http(port, "GET", "/synastry/$id/report", token = token)

	private fun closeGate(): CountDownLatch = CountDownLatch(1).also { generator.gate = it }

	private fun await(token: String, id: String, expected: String): JsonNode {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (true) {
			val response = get(token, id)
			assertEquals(200, response.statusCode(), response.body())
			val json = mapper.readTree(response.body())
			if (json["status"].asString() == expected) return json
			check(System.nanoTime() < deadline) { "상태가 $expected 가 되지 않는다: ${json["status"]}" }
			Thread.sleep(20)
		}
	}

	private fun reportStatusOf(token: String, id: String): String =
		mapper.readTree(http(port, "GET", "/synastry/$id", token = token).body())["reportStatus"].asString()

	@Test
	fun `POST 는 기다리지 않고 202 를 주고 뒤에서 네 영역을 써서 DONE 이 된다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		val gate = closeGate()
		assertEquals(NONE, get(token, id).body())

		val posted = post(token, id)

		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals(WRITING, posted.body())
		assertEquals(WRITING, get(token, id).body())
		assertEquals("WRITING", reportStatusOf(token, id))
		assertNull(storedBody(), "완성 전에는 본문이 없다")

		gate.countDown()
		val done = await(token, id, "DONE")

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
		assertEquals(REPORT_AREAS.sorted(), generator.areas.map(::areaOf).sorted())
		assertEquals("DONE", reportStatusOf(token, id))
		assertEquals(done["report"], mapper.readTree(storedBody()))
		// 완성된 리포트는 상대의 출생 정보 없이 읽고, 다시 요청해도 새로 쓰지 않는다
		val again = post(token, id, body = null)
		assertEquals(202, again.statusCode())
		assertEquals(DONE, again.body())
		assertEquals(4, generator.areas.size)
	}

	@Test
	fun `리포트의 재료는 서버가 계산한 연결과 점수이고 근거는 내 쪽 요소뿐이다`() {
		val token = tokenWithProfile()
		val id = synastry(token, relation = "WORK")

		post(token, id)
		await(token, id, "DONE")

		val score = mapper.readTree(http(port, "GET", "/synastry/$id", token = token).body())["score"].asInt()
		for (request in generator.areas) {
			assertEquals(MY_SIDE_WORDS, vocabularyOf(request))
			assertTrue(request.userText.contains("${score}점"))
			assertTrue(request.userText.contains("[근거로 쓸 수 있는 말]"))
			assertTrue(request.userText.contains("일"), request.userText)
			assertTrue(request.instructions.contains("점성술 용어를 하나도 쓰지 않습니다"))
			assertEquals(generator.summaries.single().userText, request.userText.substringBefore("\n\n[근거로 쓸 수 있는 말]"), "요약과 같은 재료다")
			for (secret in listOf("지우", "1988", "07-21", "06:10", "1990", "14:30", MAPO.toString())) {
				assertFalse(request.userText.contains(secret), "프롬프트에 '$secret' 이 있다")
			}
			assertFalse(Regex("""\d+\.\d""").containsMatchIn(request.userText))
		}
		// 영역마다 지시문이 다르다
		assertEquals(4, generator.areas.map { it.instructions }.toSet().size)
		assertEquals(REPORT_AREAS.map { "synastry_report_${it.lowercase()}" }.sorted(), generator.areas.map { it.purpose }.sorted())
	}

	@Test
	fun `내 시각을 모르면 근거에 상승점이 없다`() {
		val token = tokenWithProfile("""{"date":"$MY_DATE","time":null,"placeId":$MAPO}""")
		val id = synastry(token)

		post(token, id)
		await(token, id, "DONE")

		assertEquals(MY_SIDE_WORDS - "상승점", vocabularyOf(generator.areas.first()))
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `리포트를 쓴 뒤에도 DB 와 로그에 상대의 출생 정보가 없다`(output: CapturedOutput) {
		val token = tokenWithProfile()
		val id = synastry(token)

		post(token, id)
		await(token, id, "DONE")
		settle()

		val noise = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{4}-\d{2}-\d{2}[T ][\d:.]+|[0-9a-f]{64}""")
		val stored = noise.replace(
			jdbc.queryForList("select row_to_json(t)::text from synastry_reports t", String::class.java).joinToString("\n") +
				jdbc.queryForList("select row_to_json(t)::text from synastries t", String::class.java).joinToString("\n"),
			"",
		)
		val logged = noise.replace(output.all, "")
		for (secret in listOf("1988", "07-21", "06:10")) {
			assertFalse(stored.contains(secret), "DB 에 상대의 '$secret' 이 있다")
			assertFalse(logged.contains(secret), "로그에 상대의 '$secret' 이 있다")
		}
		for (secret in listOf("가짜영역제목", "가짜본문", "가짜문단제목", "상대의 ", "나의 ")) {
			assertFalse(logged.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(logged.contains("event=synastry_report_started"))
		assertTrue(logged.contains("event=synastry_report_done"))
	}

	@Test
	fun `다시 받은 출생 정보가 그 궁합의 것이 아니면 400 BIRTH_MISMATCH 이고 쓰지 않는다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		val score = expectedScore("1988-07-21", com.byeolmyeong.api.chart.Relation.LOVE, partnerTime = "06:10")
		val otherDate = partnerDate { it != score }

		val mismatch = post(token, id, """{"date":"$otherDate","time":null}""")

		assertEquals(400, mismatch.statusCode())
		assertEquals("""{"error":"BIRTH_MISMATCH"}""", mismatch.body())
		val cases = listOf(
			null to "INVALID_REQUEST",
			"not json" to "INVALID_REQUEST",
			"""{"time":null}""" to "INVALID_DATE",
			"""{"date":"1988-07-21","time":"99:99","placeId":$MAPO}""" to "INVALID_TIME",
			"""{"date":"1988-07-21","time":"06:10"}""" to "UNKNOWN_PLACE",
		)
		for ((body, code) in cases) {
			val response = post(token, id, body)
			assertEquals(400, response.statusCode(), "$body")
			assertEquals("""{"error":"$code"}""", response.body(), "$body")
		}
		assertEquals(0, reportRows())
		assertTrue(generator.areas.isEmpty())
		assertEquals(NONE, get(token, id).body())
	}

	@Test
	fun `내 출생 정보가 바뀌었으면 409 PROFILE_CHANGED 이다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		http(port, "PUT", "/profile", """{"date":"1991-01-01","time":"01:00","placeId":$MAPO}""", token)

		val response = post(token, id)

		assertEquals(409, response.statusCode())
		assertEquals("""{"error":"PROFILE_CHANGED"}""", response.body())
		assertEquals(0, reportRows())
		assertTrue(generator.areas.isEmpty())
	}

	@Test
	fun `동시에 여러 번 요청해도 한 번만 쓴다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		val gate = closeGate()

		val responses = (1..6).map { CompletableFuture.supplyAsync { post(token, id) } }.map { it.join() }

		assertTrue(responses.all { it.statusCode() == 202 && it.body() == WRITING }, responses.map { it.body() }.toString())
		gate.countDown()
		await(token, id, "DONE")
		settle()
		assertEquals(4, generator.areas.size)
		assertEquals(1, reportRows())
	}

	@Test
	fun `남의 궁합의 리포트는 요청도 읽기도 404 이다`() {
		val mine = tokenWithProfile()
		val id = synastry(mine)
		post(mine, id)
		await(mine, id, "DONE")
		val other = UUID.randomUUID()
		val theirs = "other-token-$other"
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", other, "someone-$other")
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(theirs), other)
		jdbc.update("insert into profiles (account_id, birth_date, birth_time, place_id) select ?, birth_date, birth_time, place_id from profiles", other)

		for (response in listOf(get(theirs, id), post(theirs, id), get(mine, UUID.randomUUID().toString()), post(mine, "not-an-id"))) {
			assertEquals(404, response.statusCode())
			assertEquals("""{"error":"NOT_FOUND"}""", response.body())
		}
		assertEquals(401, http(port, "GET", "/synastry/$id/report").statusCode())
		assertEquals(401, http(port, "POST", "/synastry/$id/report", PARTNER).statusCode())
		assertEquals(4, generator.areas.size)
	}

	@Test
	fun `한 영역이라도 만들지 못하면 FAILED 이고 아무것도 저장하지 않으며 다시 요청하면 새로 쓴다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		generator.respond = { request, call ->
			if (request.purpose == "synastry_report_conflict") throw GenerationException("HTTP_500") else validAnswer(request, call)
		}

		assertEquals(WRITING, post(token, id).body())
		await(token, id, "FAILED")
		settle()

		assertNull(storedBody(), "받아 둔 영역도 저장하지 않는다")
		assertEquals("FAILED", reportStatusOf(token, id))
		assertEquals(FAILED, get(token, id).body())

		generator.reset()
		assertEquals(WRITING, post(token, id).body())
		await(token, id, "DONE")
		assertEquals(4, generator.areas.size)
	}

	@Test
	fun `틀린 영역은 그 영역만 다시 쓰고 세 번 틀리면 FAILED 다`() {
		val token = tokenWithProfile()
		val bad: List<(com.byeolmyeong.api.generation.GenerationRequest, Int) -> JsonNode?> = listOf(
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
		for ((index, answer) in bad.withIndex()) {
			val id = synastry(token)
			generator.reset()
			generator.respond = { request, call -> if (areaOf(request) == "TALK") answer(request, call) else validAnswer(request, call) }
			post(token, id)
			await(token, id, "FAILED")
			settle()
			assertEquals(3, generator.areas.count { areaOf(it) == "TALK" }, "경우 $index")
			assertTrue(generator.areas.count { areaOf(it) != "TALK" } <= 3, "경우 $index: 다른 영역은 한 번씩만")
		}
		assertEquals(0, jdbc.queryForObject("select count(*) from synastry_reports where body is not null", Int::class.java))

		// 한 번 틀리고 다시 써서 맞으면 완성된다
		val id = synastry(token)
		generator.reset()
		var talkCalls = 0
		generator.respond = { request, call ->
			if (areaOf(request) == "TALK" && ++talkCalls == 1) areaJson(request, call, count = 2) else areaJson(request, call, count = 4)
		}
		post(token, id)
		val done = await(token, id, "DONE")
		assertEquals(5, generator.areas.size)
		assertEquals(listOf(4, 4, 4, 4), done["report"]["sections"].values().map { it["paragraphs"].size() })
	}

	@Test
	fun `쓰는 도중 서버가 꺼진 리포트는 살아 있다는 표시가 2분 끊기면 FAILED 로 보이고 다시 요청하면 새로 쓴다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		generator.respond = { request, call -> if (request.purpose == SUMMARY_PURPOSE) validAnswer(request, call) else throw GenerationException("HTTP_500") }
		post(token, id)
		await(token, id, "FAILED")
		settle()
		generator.reset()
		// 다른 서버(또는 재시작하기 전의 이 서버)가 30분 전에 자리를 잡고 쓰다가 꺼졌다: 이 서버에는 그 생성이 없다
		jdbc.update("update synastry_reports set status = 'WRITING', started_at = now() - interval '30 minutes', active_at = now() - interval '1 minute'")

		assertEquals(WRITING, get(token, id).body(), "표시가 끊긴 지 2분이 안 됐으면 쓰는 중으로 본다")
		assertEquals("WRITING", reportStatusOf(token, id))
		assertEquals(WRITING, post(token, id).body())
		settle()
		assertTrue(generator.areas.isEmpty(), "다시 시작하지 않았다")

		jdbc.update("update synastry_reports set active_at = now() - interval '3 minutes'")
		assertEquals(FAILED, get(token, id).body(), "멈춘 것으로 본다")
		assertEquals("FAILED", reportStatusOf(token, id))
		assertEquals(WRITING, post(token, id).body(), "다시 시작한다")
		await(token, id, "DONE")
		settle()
		assertEquals(4, generator.areas.size)
		assertEquals(1, reportRows())
	}

	@Test
	fun `멈춘 줄 알고 다시 시작한 뒤에 옛 생성이 뒤늦게 끝나도 덮어쓰지 못한다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		val gate = closeGate()
		post(token, id)
		assertEquals(WRITING, get(token, id).body())

		jdbc.update("update synastry_reports set active_at = now() - interval '3 minutes'")

		assertEquals(FAILED, get(token, id).body())
		assertEquals("FAILED", reportStatusOf(token, id))
		// 다시 요청하면 새로 시작하고, 멈춘 줄 알았던 옛 생성(호출 2~5번, 1번은 요약)이 뒤늦게 끝나도 덮어쓰지 않는다
		generator.respond = { request, call -> areaJson(request, call, count = if (call <= 5) 3 else 4) }
		assertEquals(WRITING, post(token, id).body())
		gate.countDown()
		val done = await(token, id, "DONE")
		settle()
		assertEquals(8, generator.areas.size)
		assertEquals(listOf(4, 4, 4, 4), done["report"]["sections"].values().map { it["paragraphs"].size() }, "새 생성의 글이 남는다")
		assertEquals(done["report"], mapper.readTree(storedBody()))
	}

	@Test
	fun `궁합이나 계정을 지우면 리포트도 지워진다`() {
		val token = tokenWithProfile()
		val id = synastry(token)
		post(token, id)
		await(token, id, "DONE")
		assertEquals(1, reportRows())

		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		assertEquals(0, reportRows())
	}
}

/** 뒤에서 일할 자리(심층 리포트와 같이 쓰는 일꾼)가 꽉 찼을 때. */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.worker-threads=1", "byeolmyeong.report.queue-capacity=1"],
)
class SynastryReportExecutorFullIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	@Test
	fun `일할 자리가 없으면 FAILED 로 답하고 아무것도 저장하지 않는다`() {
		jdbc.update("delete from accounts")
		generator.reset()
		val token = devToken(port)
		http(port, "PUT", "/profile", ME, token)
		val id = mapper.readTree(http(port, "POST", "/synastry/direct", directBody(), token).body())["id"].asString()
		val gate = CountDownLatch(1).also { generator.gate = it }

		// 자리는 2개(일하는 1 + 기다리는 1)인데 영역은 4개다
		val posted = http(port, "POST", "/synastry/$id/report", """{"date":"1988-07-21","time":null}""", token)

		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals("""{"status":"FAILED"}""", posted.body())
		assertEquals("""{"status":"FAILED"}""", http(port, "GET", "/synastry/$id/report", token = token).body())
		gate.countDown()
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline)
			Thread.sleep(10)
		}
		assertNull(jdbc.queryForObject("select body::text from synastry_reports", String::class.java))
		assertTrue(generator.areas.size <= 2, "자리를 얻은 영역만 불렀다")
	}
}

/**
 * 앞의 리포트 때문에 줄에서 기다리는 궁합 리포트. 일꾼이 하나라 뒤 리포트의 일은 앞의 것이 끝날 때까지 시작도 못 한다.
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
class SynastryReportQueueWaitIntegrationTest {

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

	private fun post(token: String, id: String) = http(port, "POST", "/synastry/$id/report", PARTNER, token)

	private fun get(token: String, id: String) = http(port, "GET", "/synastry/$id/report", token = token)

	/** 앞 궁합의 리포트가 하나뿐인 일꾼을 붙잡고 있고(생성기가 답하지 않는다), 뒤 궁합의 리포트는 줄에서 기다린다. 토큰과 뒤 궁합의 id 를 준다. */
	private fun waitingBehindAnother(): Pair<String, String> {
		val token = devToken(port)
		http(port, "PUT", "/profile", ME, token)
		val (first, second) = (1..2).map {
			val body = directBody(date = "1988-07-21", time = "06:10", placeId = MAPO)
			mapper.readTree(http(port, "POST", "/synastry/direct", body, token).body())["id"].asString()
		}
		generator.gate = CountDownLatch(1)
		assertEquals(WRITING, post(token, first).body())
		until("앞 리포트의 일이 시작되지 않는다") { generator.areas.size == 1 }
		assertEquals(WRITING, post(token, second).body())
		return token to second
	}

	@Test
	fun `줄에서 10분 넘게 기다린 리포트도 WRITING 이고 차례가 오면 완성된다`() {
		val (token, second) = waitingBehindAnother()

		// 자리를 잡은 지 11분이 지났다
		jdbc.update("update synastry_reports set started_at = started_at - interval '11 minutes' where synastry_id = ?", UUID.fromString(second))

		assertEquals(WRITING, get(token, second).body(), "기다린 시간만으로 멈춘 것으로 보지 않는다")
		assertEquals("WRITING", mapper.readTree(http(port, "GET", "/synastry/$second", token = token).body())["reportStatus"].asString())
		assertEquals(WRITING, post(token, second).body(), "다시 시작하지도 않는다")

		// started_at 은 그 생성의 표식이기도 하다(끝낼 때 자기 것인지 가린다). 일이 시작되기 전에 되돌려 둔다
		jdbc.update("update synastry_reports set started_at = started_at + interval '11 minutes' where synastry_id = ?", UUID.fromString(second))
		generator.gate!!.countDown()
		until("뒤 리포트가 완성되지 않는다") { get(token, second).body().contains("\"status\":\"DONE\"") }
		settle()

		assertEquals(8, generator.areas.size, "두 리포트의 네 영역을 한 번씩만 불렀다")
	}

	@Test
	fun `서버가 살아 있으면 줄에서 기다리는 리포트에도 살아 있다는 표시를 계속 적는다`() {
		val (token, second) = waitingBehindAnother()

		// 표시가 오래 끊긴 것처럼 옮겨 놓아도 다음 주기에 다시 적힌다(쓰는 중인 것도, 기다리는 것도)
		jdbc.update("update synastry_reports set active_at = now() - interval '11 minutes'")
		until("살아 있다는 표시가 다시 적히지 않는다") {
			jdbc.queryForObject("select count(*) from synastry_reports where active_at > now() - interval '10 seconds'", Int::class.java) == 2
		}
		assertEquals(WRITING, get(token, second).body())

		generator.gate!!.countDown()
		until("뒤 리포트가 완성되지 않는다") { get(token, second).body().contains("\"status\":\"DONE\"") }
		settle()
		assertEquals(8, generator.areas.size)
	}
}
