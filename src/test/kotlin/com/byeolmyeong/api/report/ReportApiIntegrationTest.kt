package com.byeolmyeong.api.report

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.http
import org.junit.jupiter.api.AfterEach
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
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val UNKNOWN = """{"date":"1990-03-15","time":null,"placeId":$MAPO}"""
private const val NONE = """{"status":"NONE"}"""
private const val WRITING = """{"status":"WRITING"}"""
private const val FAILED = """{"status":"FAILED"}"""
private const val DONE = """{"status":"DONE"}"""
private val AREAS = listOf("PERSONALITY", "RELATIONSHIP", "WORK", "EMOTION")
private val TIME_ONLY_WORDS = listOf("하우스", "상승점", "중천", "행운점", "버텍스")

private val mapper = JsonMapper()

/** 요청의 구조(스키마)에서 근거로 고를 수 있게 한 말. */
fun vocabularyOf(request: GenerationRequest): List<String> = mapper.valueToTree<JsonNode>(request.schema)
	.get("properties").get("paragraphs").get("items").get("properties").get("basis").get("items").get("enum")
	.values().map { it.asString() }

/** 어느 영역의 요청인지(`report_work` → `WORK`). */
fun areaOf(request: GenerationRequest): String = request.purpose.removePrefix("report_").uppercase()

fun areaJson(title: String = "가짜영역제목", paragraphs: List<Map<String, Any>>): JsonNode =
	mapper.valueToTree(mapOf("title" to title, "paragraphs" to paragraphs))

fun paragraphs(request: GenerationRequest, call: Int, count: Int = 4): List<Map<String, Any>> =
	(1..count).map { mapOf("title" to "가짜제목$it", "body" to "가짜본문-$call", "basis" to vocabularyOf(request).take(2)) }

/** 검증을 통과하는 한 영역의 글. 본문에 몇 번째 호출의 답인지 적는다. */
fun validArea(request: GenerationRequest, call: Int): JsonNode? = areaJson(paragraphs = paragraphs(request, call))

/** 시기 요청의 "[시기 목록]" 한 줄: `- id | 시작 해~끝 해 | …`. */
data class TimingLine(val id: String, val fromYear: Int, val toYear: Int)

private val TIMING_LINE = Regex("""^- (\S+) \| (\d{4})~(\d{4}) \| """)

fun timingLines(request: GenerationRequest): List<TimingLine> = request.userText.lines().mapNotNull { line ->
	TIMING_LINE.find(line)?.destructured?.let { (id, from, to) -> TimingLine(id, from.toInt(), to.toInt()) }
}

/** 검증을 통과하는 시기 글: 받은 줄마다 해를 그대로 옮겨 적는다. [change] 로 한 줄씩 고쳐 틀린 답을 만든다. */
fun timingJson(request: GenerationRequest, change: (TimingLine, MutableMap<String, Any>) -> Unit = { _, _ -> }): JsonNode =
	mapper.valueToTree(
		mapOf(
			"items" to timingLines(request).map { line ->
				mutableMapOf<String, Any>(
					"id" to line.id, "fromYear" to line.fromYear, "toYear" to line.toYear,
					"title" to "가짜시기제목-${line.id}", "body" to "가짜시기본문-${line.id}",
				).also { change(line, it) }
			},
		),
	)

/**
 * 실제 생성기 대신 끼우는 가짜. 받은 요청을 기억하고, 문([gate])이 닫혀 있으면 열릴 때까지 답하지 않는다.
 * 네 영역의 요청은 [requests] 에, 시기 글의 요청(`report_timing`)은 [timingRequests] 에 따로 둔다.
 */
class FakeReportGenerator : TextGenerator {
	val requests = CopyOnWriteArrayList<GenerationRequest>()
	val timingRequests = CopyOnWriteArrayList<GenerationRequest>()

	@Volatile
	var respond: (GenerationRequest, Int) -> JsonNode? = ::validArea

	@Volatile
	var respondTiming: (GenerationRequest, Int) -> JsonNode? = { request, _ -> timingJson(request) }

	@Volatile
	var gate: CountDownLatch? = null

	override fun generate(request: GenerationRequest): JsonNode? {
		if (request.purpose == "report_timing") {
			timingRequests += request
			val call = timingRequests.size
			gate?.await(30, TimeUnit.SECONDS)
			return respondTiming(request, call)
		}
		requests += request
		val call = requests.size
		gate?.await(30, TimeUnit.SECONDS)
		return respond(request, call)
	}

	fun calls(area: String): Int = requests.count { areaOf(it) == area }

	fun reset() {
		requests.clear()
		timingRequests.clear()
		respond = ::validArea
		respondTiming = { request, _ -> timingJson(request) }
		gate = null
	}
}

class DoneEvents {
	val owners = CopyOnWriteArrayList<UUID>()

	@EventListener
	fun on(event: ReportDone) {
		owners += event.ownerId
	}
}

@Import(TestcontainersConfiguration::class, ReportApiIntegrationTest.FakeConfig::class)
// 살아 있다는 표시(`active_at`)를 적는 주기를 길게 둬, 테스트가 그 값을 옮겨 놓은 사이에 다시 적히지 않게 한다.
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.heartbeat-seconds=3600"],
)
class ReportApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun fakeReportGenerator() = FakeReportGenerator()

		@Bean
		fun doneEvents() = DoneEvents()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeReportGenerator

	@Autowired
	private lateinit var events: DoneEvents

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
		events.owners.clear()
	}

	private fun rows(): Int = jdbc.queryForObject("select count(*) from reports", Int::class.java)!!

	private fun storedBody(): String? = jdbc.queryForObject("select body::text from reports", String::class.java)

	private fun tokenWithProfile(profile: String): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	private fun post(token: String) = http(port, "POST", "/reports", token = token)

	private fun get(token: String) = http(port, "GET", "/reports/me", token = token)

	private fun status(token: String): String = mapper.readTree(get(token).body())["status"].asString()

	private fun closeGate(): CountDownLatch = CountDownLatch(1).also { generator.gate = it }

	/** 상태가 [expected] 가 될 때까지 `GET /reports/me` 를 다시 부른다. */
	private fun await(token: String, expected: String): JsonNode {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (true) {
			val response = get(token)
			assertEquals(200, response.statusCode(), response.body())
			val json = mapper.readTree(response.body())
			if (json["status"].asString() == expected) return json
			check(System.nanoTime() < deadline) { "상태가 $expected 가 되지 않는다: ${json["status"]}" }
			Thread.sleep(20)
		}
	}

	/** 생성기가 그만큼의 요청을 받을 때까지(문이 닫혀 있으면 그 일들이 답을 기다리며 서 있다) 기다린다. */
	private fun awaitCalls(areas: Int, timing: Int) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (generator.requests.size < areas || generator.timingRequests.size < timing) {
			check(System.nanoTime() < deadline) { "생성기가 요청을 받지 않는다" }
			Thread.sleep(10)
		}
	}

	@Test
	fun `POST 는 기다리지 않고 202 를 주고 GET 은 NONE 에서 WRITING 을 거쳐 DONE 이 된다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()
		assertEquals(200, get(token).statusCode())
		assertEquals(NONE, get(token).body())

		val posted = post(token)

		// 생성기는 아직 답하지 않았다: 요청은 글을 기다리지 않고 돌아왔다
		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals(WRITING, posted.body())
		assertEquals(WRITING, get(token).body())
		assertEquals("WRITING", jdbc.queryForObject("select status from reports", String::class.java))
		assertNull(storedBody(), "완성 전에는 본문이 없다")
		assertTrue(events.owners.isEmpty())

		gate.countDown()
		val done = await(token, "DONE")

		assertEquals(listOf("status", "report"), done.propertyNames().toList())
		val report = done["report"]
		assertEquals(listOf("sections", "timing"), report.propertyNames().toList())
		assertEquals(AREAS, report["sections"].values().map { it["area"].asString() }, "네 영역, 이 순서")
		for (section in report["sections"].values()) {
			assertEquals(listOf("area", "title", "paragraphs"), section.propertyNames().toList())
			assertEquals("가짜영역제목", section["title"].asString())
			assertEquals(4, section["paragraphs"].size())
			assertEquals(listOf("title", "body", "basis"), section["paragraphs"][0].propertyNames().toList())
			assertEquals(listOf("태양", "달"), section["paragraphs"][0]["basis"].values().map { it.asString() })
		}
		assertEquals(AREAS.sorted(), generator.requests.map(::areaOf).sorted(), "영역마다 한 번씩 부른다")
		assertEquals(1, generator.timingRequests.size, "시기 글은 한 번 부른다")
		assertEquals(done, mapper.readTree(get(token).body()), "다시 열면 같은 글")

		val owner = jdbc.queryForObject("select id from accounts", UUID::class.java)
		assertEquals(listOf(owner), events.owners.toList(), "ReportDone 은 한 번")
	}

	@Test
	fun `이미 완성된 차트는 다시 만들지 않고 출생 정보를 바꾸면 새로 만든다`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		val original = await(token, "DONE")

		val again = post(token)
		assertEquals(202, again.statusCode())
		assertEquals(DONE, again.body())
		settle()
		assertEquals(4, generator.requests.size, "다시 생성하지 않는다")
		assertEquals(1, events.owners.size)

		http(port, "PUT", "/profile", UNKNOWN, token)
		assertEquals(NONE, get(token).body(), "지금 차트의 리포트는 아직 없다")
		assertEquals(WRITING, post(token).body())
		val changed = await(token, "DONE")
		assertEquals(8, generator.requests.size)
		assertEquals(2, rows())
		assertTrue(original != changed)

		http(port, "PUT", "/profile", KNOWN, token)
		assertEquals(original, mapper.readTree(get(token).body()), "되돌리면 예전 글")
		assertEquals(DONE, post(token).body())
		settle()
		assertEquals(8, generator.requests.size)
	}

	@Test
	fun `동시에 온 POST 는 생성을 한 번만 시작한다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()

		val posts = (1..6).map { CompletableFuture.supplyAsync { post(token) } }.map { it.get(20, TimeUnit.SECONDS) }

		for (response in posts) {
			assertEquals(202, response.statusCode(), response.body())
			assertEquals(WRITING, response.body())
		}
		gate.countDown()
		await(token, "DONE")
		settle()
		assertEquals(4, generator.requests.size, "네 영역을 한 번씩만")
		assertEquals(1, rows())
		assertEquals(1, events.owners.size)
	}

	@Test
	fun `쓰는 중에 다시 POST 해도 두 번째 생성을 시작하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()
		assertEquals(WRITING, post(token).body())
		assertEquals(WRITING, post(token).body())
		gate.countDown()
		await(token, "DONE")
		settle()
		assertEquals(4, generator.requests.size)
	}

	@Test
	fun `틀린 영역만 다시 생성하고 나머지는 다시 부르지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { request, call ->
			if (areaOf(request) == "WORK" && generator.calls("WORK") == 1) {
				areaJson(paragraphs = paragraphs(request, call).map { it + ("basis" to listOf("태양", "없는별")) })
			} else {
				validArea(request, call)
			}
		}

		post(token)
		val done = await(token, "DONE")

		assertEquals(2, generator.calls("WORK"))
		assertEquals(5, generator.requests.size, "나머지 세 영역은 한 번씩")
		val work = generator.requests.filter { areaOf(it) == "WORK" }
		assertEquals(work[0], work[1], "같은 요청으로 다시 부른다")
		assertFalse(done.toString().contains("없는별"))
	}

	@Test
	fun `세 번 틀리면 FAILED 이고 아무것도 저장하지 않으며 다시 POST 하면 새로 시작한다`() {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Pair<String, (GenerationRequest, Int) -> JsonNode?>> = listOf(
			"차트에 없는 근거" to { r, c -> areaJson(paragraphs = paragraphs(r, c).map { it + ("basis" to listOf("없는별")) }) },
			"아무 천체도 없는 하우스" to { r, c ->
				val empty = (1..12).map { "${it}하우스" }.first { it !in vocabularyOf(r) }
				areaJson(paragraphs = paragraphs(r, c).map { it + ("basis" to listOf(empty)) })
			},
			"응답을 쓸 수 없음" to { _, _ -> null },
			"문단이 3개" to { r, c -> areaJson(paragraphs = paragraphs(r, c, count = 3)) },
			"문단이 7개" to { r, c -> areaJson(paragraphs = paragraphs(r, c, count = 7)) },
			"빈 영역 제목" to { r, c -> areaJson(title = " ", paragraphs = paragraphs(r, c)) },
			"빈 문단 제목" to { r, c -> areaJson(paragraphs = paragraphs(r, c).map { it + ("title" to "") }) },
			"빈 본문" to { r, c -> areaJson(paragraphs = paragraphs(r, c).map { it + ("body" to "\n") }) },
			"근거 없는 문단" to { r, c -> areaJson(paragraphs = paragraphs(r, c).map { it + ("basis" to emptyList<String>()) }) },
			"필드가 빠짐" to { _, _ -> mapper.readTree("""{"title":"제목"}""") },
			"타입이 틀림" to { _, _ -> mapper.readTree("""{"title":1,"paragraphs":[{"title":"가","body":"나","basis":[1]}]}""") },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respond = { request, call -> if (areaOf(request) == "EMOTION") breakIt(request, call) else validArea(request, call) }

			val posted = post(token)
			assertEquals(202, posted.statusCode(), name)
			assertEquals(WRITING, posted.body(), "$name: FAILED 뒤의 POST 는 다시 시작한다")
			await(token, "FAILED")
			settle()

			assertEquals(FAILED, get(token).body(), name)
			assertEquals(3, generator.calls("EMOTION"), "$name: 처음 1번 + 다시 2번")
			for (area in AREAS - "EMOTION") assertTrue(generator.calls(area) <= 1, "$name: 맞은 영역은 다시 부르지 않는다")
			assertEquals(1, rows(), name)
			assertNull(storedBody(), "$name: 본문을 저장하지 않는다")
			assertTrue(events.owners.isEmpty(), name)
		}

		generator.reset()
		assertEquals(WRITING, post(token).body())
		await(token, "DONE")
		settle() // DONE 을 커밋한 뒤 일꾼이 완성 이벤트를 낸다 — 세기 전에 끝나길 기다린다
		assertEquals(4, generator.requests.size)
		assertEquals(1, rows())
		assertEquals(1, events.owners.size)
	}

	@Test
	fun `생성 호출이 실패하면 그 영역을 다시 부르지 않고 FAILED 이다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { request, call ->
			if (areaOf(request) == "RELATIONSHIP") throw GenerationException("HTTP_429") else validArea(request, call)
		}

		post(token)
		await(token, "FAILED")
		settle()

		assertEquals(1, generator.calls("RELATIONSHIP"), "한도·키 문제는 다시 불러도 같다")
		assertNull(storedBody())
		assertTrue(events.owners.isEmpty())
	}

	@Test
	fun `쓰는 도중 서버가 꺼진 리포트는 살아 있다는 표시가 2분 끊기면 FAILED 이고 다시 시작할 수 있다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		post(token)
		await(token, "FAILED")
		settle()
		generator.reset()
		// 다른 서버(또는 재시작하기 전의 이 서버)가 30분 전에 자리를 잡고 쓰다가 꺼졌다: 이 서버에는 그 생성이 없다
		jdbc.update("update reports set status = 'WRITING', started_at = now() - interval '30 minutes', active_at = now() - interval '1 minute'")

		assertEquals(WRITING, get(token).body(), "표시가 끊긴 지 2분이 안 됐으면 쓰는 중으로 본다")
		assertEquals(WRITING, post(token).body())
		settle()
		assertEquals(0, generator.requests.size, "다시 시작하지 않았다")

		jdbc.update("update reports set active_at = now() - interval '3 minutes'")
		assertEquals(FAILED, get(token).body(), "멈춘 것으로 본다")
		assertEquals(WRITING, post(token).body(), "다시 시작한다")
		await(token, "DONE")
		settle()

		assertEquals(4, generator.requests.size)
		assertEquals(1, rows())
		assertEquals(1, events.owners.size)
	}

	@Test
	fun `다른 생성이 자리를 잡으면 옛 생성은 모델을 더 부르지 않고 그만두며 덮어쓰지도 못한다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()
		// 옛 생성이 받을 답(영역 1~4번째 호출, 시기 1번째 호출)은 모두 틀렸다: 자리를 잃지 않았다면 다시 불렀을 것이다
		generator.respond = { request, call ->
			if (call <= 4) areaJson(paragraphs = paragraphs(request, call, count = 3)) else validArea(request, call)
		}
		generator.respondTiming = { request, call -> if (call == 1) mapper.readTree("""{"items":[]}""") else timingJson(request) }
		post(token)
		awaitCalls(areas = 4, timing = 1)

		jdbc.update("update reports set active_at = now() - interval '3 minutes'")
		assertEquals(FAILED, get(token).body(), "멈춘 것으로 본다")
		assertEquals(WRITING, post(token).body(), "다시 시작한다")
		assertEquals(WRITING, get(token).body())

		gate.countDown()
		val done = await(token, "DONE")
		settle()

		assertEquals(8, generator.requests.size, "옛 생성이 이미 부른 4번 + 다시 시작한 4번. 옛 생성은 틀린 답을 받고도 다시 부르지 않는다")
		assertEquals(2, generator.timingRequests.size, "시기 글도 마찬가지다")
		assertEquals(1, rows())
		assertEquals(1, events.owners.size, "옛 생성은 아무것도 남기지 않는다")
		val calls = done["report"]["sections"].values().flatMap { it["paragraphs"].values() }
			.map { it["body"].asString().removePrefix("가짜본문-").toInt() }
		assertTrue(calls.all { it in 5..8 }, "저장된 글은 다시 시작한 생성의 것이다: $calls")
	}

	@Test
	fun `줄에서 기다리던 생성이 자리를 잃으면 차례가 와도 모델을 부르지 않는다`() {
		val busy = tokenWithProfile(KNOWN)
		val gate = closeGate()
		post(busy)
		awaitCalls(areas = 4, timing = 1)
		// 일꾼 다섯이 모두 앞 사람의 일을 붙잡고 있다. 뒤 사람의 생성은 줄에서 기다린다
		val waiting = "another-accounts-session-token"
		val other = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", other)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(waiting), other)
		http(port, "PUT", "/profile", KNOWN, waiting)
		assertEquals(WRITING, post(waiting).body())

		jdbc.update("update reports set active_at = now() - interval '3 minutes' where owner_id = ?", other)
		assertEquals(WRITING, post(waiting).body(), "다른 생성이 자리를 잡는다")

		gate.countDown()
		await(waiting, "DONE")
		settle()

		assertEquals(8, generator.requests.size, "앞 사람 4번 + 뒤 사람의 새 생성 4번. 자리를 잃은 생성은 한 번도 부르지 않는다")
		assertEquals(2, generator.timingRequests.size)
		assertEquals(2, events.owners.size)
	}

	@Test
	fun `시각을 알면 하우스와 상승점이 생성기에 가고 모르면 가지 않으며 근거로도 받지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		await(token, "DONE")
		for (request in generator.requests) {
			assertTrue(request.userText.contains("하우스") && request.userText.contains("상승점: "))
			assertTrue(vocabularyOf(request).containsAll(listOf("상승점", "중천", "행운점", "버텍스")))
			assertFalse(request.instructions.contains("계산되지 않았습니다"))
		}

		settle()
		generator.reset()
		http(port, "PUT", "/profile", UNKNOWN, token)
		post(token)
		await(token, "DONE")
		assertEquals(4, generator.requests.size)
		for (request in generator.requests) {
			for (word in TIME_ONLY_WORDS) {
				assertFalse(request.userText.contains(word), "차트 사실에 '$word' 가 있다:\n${request.userText}")
				assertFalse(vocabularyOf(request).any { it.contains(word) }, "근거 말에 '$word' 가 있다")
			}
			assertTrue(request.userText.contains("태양: 물고기"))
			assertTrue(request.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다"), "모델에게 쓰지 말라고 알린다")
		}

		for (basis in listOf("1하우스", "상승점", "중천")) {
			settle()
			generator.reset()
			http(port, "PUT", "/profile", """{"date":"1991-04-16","time":null,"placeId":$MAPO}""", token)
			generator.respond = { request, call -> areaJson(paragraphs = paragraphs(request, call).map { it + ("basis" to listOf("태양", basis)) }) }
			post(token)
			await(token, "FAILED")
			settle()
			assertEquals(3, AREAS.maxOf(generator::calls), "$basis: 세 번 시도하고 그만둔다")
			assertEquals(FAILED, get(token).body(), "$basis: 이 차트의 리포트는 만들지 못했다")
			assertEquals(2, jdbc.queryForObject("select count(*) from reports where body is not null", Int::class.java), "$basis: 글이 있는 것은 앞의 두 차트뿐이다")
		}
	}

	// 시각을 모르는 프로필에는 태어난 곳이 없다(결정 007). 곳을 보내 저장했던 때와 같은 차트다.
	@Test
	fun `태어난 곳이 없는 시각 모름 프로필로도 리포트를 만든다`() {
		val token = tokenWithProfile("""{"date":"1990-03-15","time":null}""")
		assertNull(jdbc.queryForObject("select place_id from profiles", Int::class.java))
		assertEquals(NONE, get(token).body())

		assertEquals(202, post(token).statusCode())
		val done = await(token, "DONE")
		assertEquals(AREAS, done["report"]["sections"].values().map { it["area"].asString() })
		assertEquals(4, generator.requests.size)
		for (request in generator.requests) {
			assertTrue(request.userText.contains("태양: 물고기"))
			for (word in TIME_ONLY_WORDS) assertFalse(request.userText.contains(word), "차트 사실에 '$word' 가 있다")
		}

		// placeId 를 보내도 같은 차트라 저장된 리포트를 그대로 준다(다시 생성하지 않는다)
		settle()
		assertEquals(200, http(port, "PUT", "/profile", UNKNOWN, token).statusCode())
		assertEquals(DONE, post(token).body())
		assertEquals(4, generator.requests.size)
		assertEquals(1, rows())
	}

	@Test
	fun `영역마다 그 영역의 지시문이 가고 공통 글쓰기 규칙이 함께 간다`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		await(token, "DONE")

		val byArea = generator.requests.associateBy(::areaOf)
		assertEquals(AREAS.toSet(), byArea.keys)
		assertEquals(1, byArea.values.map { it.userText }.distinct().size, "차트는 네 영역이 같다")
		assertEquals(4, byArea.values.map { it.instructions }.distinct().size, "지시문은 영역마다 다르다")
		for (request in byArea.values) {
			assertTrue(request.instructions.contains("점성술 용어를 하나도 쓰지 않습니다"), "공통 규칙")
			assertTrue(request.instructions.contains("단정하지 않습니다"), "공통 규칙")
			assertTrue(request.instructions.contains("4~6개"), "문단 수")
		}
		assertTrue(byArea.getValue("RELATIONSHIP").instructions.contains("나는 사람을 어떻게 대하는 사람인가"))
	}

	@Test
	fun `프롬프트에 출생 날짜와 시각과 장소가 없다`() {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":null,"placeId":$MAPO}""", token)
		post(token)
		await(token, "DONE")

		assertEquals(8, generator.requests.size)
		for (request in generator.requests) {
			val all = request.instructions + "\n" + request.userText + "\n" + mapper.writeValueAsString(request.schema)
			for (secret in listOf("1977", "07-07", "07:07", "7월", "마포", "서울", "37.5", "126.9", MAPO.toString())) {
				assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
			}
		}
	}

	@Test
	fun `프로필이 없으면 POST 는 404 이고 GET 은 NONE 이며 세션이 없으면 401 이다`() {
		for ((method, path) in listOf("POST" to "/reports", "GET" to "/reports/me")) {
			val noSession = http(port, method, path)
			assertEquals(401, noSession.statusCode(), path)
			assertEquals("""{"error":"UNAUTHORIZED"}""", noSession.body())
			assertEquals(401, http(port, method, path, token = "wrong-token").statusCode())
		}

		val token = devToken(port)
		val noProfile = post(token)
		assertEquals(404, noProfile.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", noProfile.body())
		assertEquals(200, get(token).statusCode())
		assertEquals(NONE, get(token).body())
		settle()
		assertEquals(0, generator.requests.size)
		assertEquals(0, rows())
	}

	// 소유자는 토큰에서만 얻는다. 차트가 같아도 남의 리포트를 보여 주지 않는다.
	@Test
	fun `남의 리포트는 보이지 않고 계정을 지우면 그 사람의 리포트만 지워진다`() {
		val mine = tokenWithProfile(KNOWN)
		post(mine)
		val myReport = await(mine, "DONE")

		val theirs = "another-accounts-session-token"
		val other = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", other)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(theirs), other)
		assertEquals(NONE, get(theirs).body(), "프로필이 없는 남에게 내 리포트가 나가지 않는다")

		http(port, "PUT", "/profile", KNOWN, theirs)
		assertEquals(NONE, get(theirs).body(), "차트가 같아도 남의 리포트는 없는 것이다")
		assertEquals(WRITING, post(theirs).body())
		val theirReport = await(theirs, "DONE")
		assertEquals(8, generator.requests.size, "남의 글을 돌려쓰지 않고 새로 만든다")
		assertTrue(myReport != theirReport)
		assertEquals(2, rows())
		assertEquals(2, events.owners.size)
		assertTrue(other in events.owners)

		assertEquals(204, http(port, "DELETE", "/account", token = theirs).statusCode())
		assertEquals(1, rows())
		assertEquals(0, jdbc.queryForObject("select count(*) from reports where owner_id = ?", Int::class.java, other))
		assertEquals(myReport, mapper.readTree(get(mine).body()))

		assertEquals(204, http(port, "DELETE", "/account", token = mine).statusCode())
		assertEquals(0, rows())
	}

	@Test
	fun `쓰는 중에 계정을 지우면 끝난 글은 저장되지 않고 알림도 없다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()
		post(token)
		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		gate.countDown()
		settle()

		assertEquals(0, rows())
		assertTrue(events.owners.isEmpty())
	}

	@Test
	fun `출생 정보 원문을 reports 에 두지 않는다`() {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")

		val row = jdbc.queryForMap("select chart_hash, status, body::text as body from reports")
		assertEquals("DONE", row["status"])
		assertTrue(Regex("[0-9a-f]{64}").matches(row["chart_hash"] as String))
		val stored = row.values.joinToString("\n")
		// 시기 영역은 마디의 해와 만 나이, 구간의 날짜를 담는다(F4). 그래서 태어난 해는 짐작할 수 있고 "1977" 같은 해나
		// 어느 해의 "07-07" 이 날짜로 나올 수 있다. 출생 날짜 그 자체와 시각은 없어야 한다(구간은 생일에서 시작하지도 끝나지도 않는다).
		for (secret in listOf("1977-07-07", "07:07")) assertFalse(stored.contains(secret), "'$secret' 이 저장됐다")
		val dates = Regex("\\d{4}-\\d{2}-\\d{2}").findAll(stored).map { it.value }.toList()
		assertTrue(dates.size > 100)
		assertTrue(dates.none { it == "2057-07-07" }, "만 80세 생일에서 자른 구간이 없다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보와 프롬프트와 글을 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")
		val facts = generator.requests.first().userText
		// 실패하는 길도 본다: 근거가 틀려 버려지는 글, 호출 실패
		settle()
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":null,"placeId":$MAPO}""", token)
		generator.respond = { request, call -> areaJson(paragraphs = paragraphs(request, call).map { it + ("basis" to listOf("없는별")) }) }
		post(token)
		await(token, "FAILED")
		settle()
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		post(token)
		await(token, "FAILED")
		settle()

		for (secret in listOf("1977", "07:07", "가짜영역제목", "가짜본문", "가짜제목", "없는별", "태양: ", "상승점", "하우스", facts.lines()[1], "4~6개")) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=report_started"))
		assertTrue(output.all.contains("event=report_done"))
		assertTrue(Regex("event=report_attempt_rejected area=[A-Z]+ reason=BASIS_NOT_IN_CHART attempt=1").containsMatchIn(output.all))
		assertTrue(Regex("event=report_area_failed area=[A-Z]+ reason=ATTEMPTS_EXHAUSTED attempt=3").containsMatchIn(output.all))
		assertTrue(Regex("event=report_area_failed area=[A-Z]+ reason=HTTP_500 attempt=1").containsMatchIn(output.all))
		assertTrue(output.all.contains("event=report_failed"))
	}

	@Test
	fun `완성된 리포트에는 시기가 있다 - 마디와 구간과 읽을 때 가린 지금`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		val done = await(token, "DONE")

		val timing = done["report"]["timing"]
		assertEquals(listOf("timeKnown", "milestones", "intervals", "current"), timing.propertyNames().toList())
		assertTrue(timing["timeKnown"].asBoolean())

		// 마디: 1990-03-15 14:30 서울(chart/ChartReference.kt 의 SEOUL_1990). 기준값은 JPL Horizons(chart/timeline-horizons-reference.tsv 의 A)
		val milestones = timing["milestones"].values().toList()
		assertEquals(
			listOf("kind", "date", "year", "age", "from", "to", "size", "title", "body"),
			milestones[0].propertyNames().toList(),
		)
		assertEquals(
			listOf("SATURN_SQUARE", "SATURN_RETURN", "URANUS_OPPOSITION", "SECOND_SATURN_RETURN"),
			milestones.map { it["kind"].asString() },
		)
		assertEquals(listOf("2011-11-09", "2020-01-17", "2034-08-20", "2049-02-21"), milestones.map { it["date"].asString() })
		assertEquals(listOf(2011, 2020, 2034, 2049), milestones.map { it["year"].asInt() })
		assertEquals(listOf(21, 29, 44, 58), milestones.map { it["age"].asInt() })
		assertEquals(listOf("SMALL", "BIG", "BIG", "BIG"), milestones.map { it["size"].asString() })
		assertEquals("2011-10-31", milestones[0]["from"].asString())
		assertEquals("2012-08-08", milestones[0]["to"].asString())
		assertTrue(milestones.all { it["title"].asString() == "가짜시기제목-${it["kind"].asString()}" }, "마디의 글은 모델이 쓴다")

		// 구간
		val intervals = timing["intervals"].values().toList()
		assertTrue(intervals.size > 150, "평생 구간: ${intervals.size}")
		val isoDate = Regex("\\d{4}-\\d{2}-\\d{2}")
		for (interval in intervals) {
			val keys = interval.propertyNames().toList()
			val change = interval["kind"].asString() == "CHANGE"
			val expectedKeys = listOf("id", "kind", "from", "to", "area") + (if (change) listOf("size") else emptyList()) + listOf("title", "body")
			assertEquals(expectedKeys, keys)
			assertTrue(interval["kind"].asString() in listOf("CHANGE", "OPPORTUNITY", "FATED"))
			assertTrue(interval["area"].asString() in listOf("PEOPLE", "WORK", "MONEY", "SELF"))
			if (change) assertTrue(interval["size"].asString() in listOf("BIG", "SMALL"))
			assertTrue(isoDate.matches(interval["from"].asString()) && isoDate.matches(interval["to"].asString()))
			assertTrue(interval["from"].asString() <= interval["to"].asString())
			assertTrue(interval["title"].asString().isNotBlank() && interval["body"].asString().isNotBlank())
		}
		assertEquals(intervals.map { it["from"].asString() }.sorted(), intervals.map { it["from"].asString() }, "시작 순서")
		assertTrue(intervals.any { it["kind"].asString() == "FATED" })
		// 기준값 파일의 A: 명왕성이 달과 직각 2027-02-07~2028-12-29 (작은 변화). 달은 출생지와 무관해서 이 프로필(마포)에서도 같다.
		assertTrue(
			intervals.any {
				it["kind"].asString() == "CHANGE" && it["size"].asString() == "SMALL" &&
					it["from"].asString().startsWith("2027-02") && it["to"].asString().startsWith("2028-12")
			},
		)

		// 모델의 글은 맡긴 줄에만, 나머지는 표의 글
		val asked = timingLines(generator.timingRequests.single()).map { it.id }.toSet()
		assertEquals(4, asked.count { !it.startsWith("i") }, "마디 네 개")
		val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
		for (interval in intervals) {
			val id = interval["id"].asString()
			val near = interval["to"].asString() >= today.toString() && interval["from"].asString() <= today.plusYears(10).toString()
			assertEquals(near, id in asked, "오늘 걸쳐 있거나 10년 안에 시작하는 구간만 맡긴다: $interval")
			assertEquals(id in asked, interval["title"].asString() == "가짜시기제목-$id")
		}

		// 지금: 저장하지 않고 읽을 때 만든다
		val current = timing["current"]
		assertEquals(today.toString(), current["date"].asString())
		val expectedNow = intervals.filter { it["from"].asString() <= today.toString() && today.toString() <= it["to"].asString() }
		assertEquals(expectedNow, current["intervals"].values().toList())
		assertTrue(current["nearestMilestone"].asString() in milestones.map { it["kind"].asString() })
		val nextMilestone = milestones.firstOrNull { it["date"].asString() > today.toString() }
		assertEquals(nextMilestone, current["nextMilestone"])
		assertEquals(intervals.firstOrNull { it["from"].asString() > today.toString() }, current["nextInterval"])
		assertFalse(current.has("mainId"), "대표 구간은 시기 풀이(/timing-report)에만 있다 — 옛 리포트의 지금에는 키가 없다")
		assertTrue(intervals.none { it.has("summary") || it.has("detail") || it.has("practices") || it.has("reason") }, "시기 풀이의 칸은 옛 리포트에 없다")
		val stored = mapper.readTree(storedBody())
		// jsonb 는 키 순서를 지키지 않으므로 모음으로 본다
		assertEquals(setOf("timeKnown", "milestones", "intervals"), stored["timing"].propertyNames().toSet(), "지금은 저장하지 않는다")
	}

	@Test
	fun `시각을 모르면 시기에 운명적 만남 구간이 없고 timeKnown 이 false 다`() {
		val token = tokenWithProfile(UNKNOWN)
		post(token)
		val timing = await(token, "DONE")["report"]["timing"]

		assertFalse(timing["timeKnown"].asBoolean())
		assertEquals(4, timing["milestones"].size())
		assertTrue(timing["intervals"].size() > 50)
		assertEquals(0, timing["intervals"].values().count { it["kind"].asString() == "FATED" })
		val request = generator.timingRequests.single()
		for (word in TIME_ONLY_WORDS) assertFalse(request.userText.contains(word), "시기 요청에 '$word' 가 있다")
		assertTrue(request.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다"))
	}

	@Test
	fun `시기 글의 해가 계산값과 다르면 버리고 다시 생성한다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respondTiming = { request, call ->
			if (call == 1) timingJson(request) { line, map -> if (line.id == "SATURN_RETURN") map["fromYear"] = line.fromYear + 1 } else timingJson(request)
		}

		post(token)
		val done = await(token, "DONE")

		assertEquals(2, generator.timingRequests.size)
		assertEquals(generator.timingRequests[0], generator.timingRequests[1], "같은 요청으로 다시 부른다")
		assertEquals(4, generator.requests.size, "네 영역은 다시 부르지 않는다")
		assertEquals(2020, done["report"]["timing"]["milestones"][1]["year"].asInt(), "해는 서버가 계산한 값이다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `시기 글이 세 번 틀리면 FAILED 이고 아무것도 저장하지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Pair<String, (GenerationRequest) -> JsonNode?>> = listOf(
			"끝 해가 다름" to { r -> timingJson(r) { line, map -> if (line.id == "URANUS_OPPOSITION") map["toYear"] = line.toYear + 1 } },
			"글 안에 기간 밖의 해" to { r -> timingJson(r) { line, map -> if (line.id == "SATURN_RETURN") map["body"] = "2031년에 큰 변화가 와요" } },
			"줄이 빠짐" to { r -> mapper.valueToTree(mapOf("items" to timingJson(r)["items"].values().drop(1))) },
			"빈 글" to { r -> timingJson(r) { _, map -> map["body"] = " " } },
			"응답을 쓸 수 없음" to { _ -> null },
			"필드가 빠짐" to { _ -> mapper.readTree("""{"items":[{"id":"SATURN_RETURN"}]}""") },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respondTiming = { request, _ -> breakIt(request) }

			assertEquals(WRITING, post(token).body(), name)
			await(token, "FAILED")
			settle()

			assertEquals(3, generator.timingRequests.size, "$name: 처음 1번 + 다시 2번")
			assertNull(storedBody(), "$name: 네 영역이 맞아도 저장하지 않는다")
			assertTrue(events.owners.isEmpty(), name)
		}
		assertTrue(Regex("event=report_attempt_rejected area=TIMING reason=YEAR_MISMATCH attempt=1").containsMatchIn(output.all))
		assertTrue(Regex("event=report_attempt_rejected area=TIMING reason=MALFORMED attempt=1").containsMatchIn(output.all))
		assertTrue(Regex("event=report_area_failed area=TIMING reason=ATTEMPTS_EXHAUSTED attempt=3").containsMatchIn(output.all))
		for (secret in listOf("가짜시기", "2031년", "1990")) assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")

		generator.reset()
		assertEquals(WRITING, post(token).body())
		await(token, "DONE")
	}

	@Test
	fun `시기 프롬프트에 출생 날짜와 시각과 장소와 태어난 해가 없다`() {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")

		val request = generator.timingRequests.single()
		val all = request.instructions + "\n" + request.userText + "\n" + mapper.writeValueAsString(request.schema)
		for (secret in listOf("1977", "07-07", "07:07", "7월", "마포", "서울", "37.5", "126.9", MAPO.toString())) {
			assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
		}
		assertTrue(request.userText.contains("[시기 목록]"))
		assertTrue(timingLines(request).size >= 4)
	}

	@Test
	fun `시기가 없는 예전 리포트는 만든 적 없는 것으로 보고 다시 만들면 그 행을 덮어쓴다`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		await(token, "DONE")
		settle()
		// 이 변경 전에 저장된 모양으로 되돌린다: 본문에 sections 만 있다
		jdbc.update("update reports set body = body - 'timing'")
		generator.reset()
		events.owners.clear()

		assertEquals(NONE, get(token).body(), "예전 리포트는 보여 주지 않는다")
		val posted = post(token)
		assertEquals(202, posted.statusCode())
		assertEquals(WRITING, posted.body(), "다시 만든다")
		val done = await(token, "DONE")
		settle()

		assertEquals(1, rows(), "같은 행을 덮어쓴다")
		assertEquals(4, generator.requests.size)
		assertEquals(1, generator.timingRequests.size)
		assertEquals(4, done["report"]["timing"]["milestones"].size())
		assertEquals(1, events.owners.size)
		assertEquals(DONE, post(token).body(), "이제는 다시 만들지 않는다")
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
