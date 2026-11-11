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

/** 실제 생성기 대신 끼우는 가짜. 받은 요청을 기억하고, 문([gate])이 닫혀 있으면 열릴 때까지 답하지 않는다. */
class FakeReportGenerator : TextGenerator {
	val requests = CopyOnWriteArrayList<GenerationRequest>()

	@Volatile
	var respond: (GenerationRequest, Int) -> JsonNode? = ::validArea

	@Volatile
	var gate: CountDownLatch? = null

	override fun generate(request: GenerationRequest): JsonNode? {
		requests += request
		val call = requests.size
		gate?.await(30, TimeUnit.SECONDS)
		return respond(request, call)
	}

	fun calls(area: String): Int = requests.count { areaOf(it) == area }

	fun reset() {
		requests.clear()
		respond = ::validArea
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
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
		assertEquals(listOf("sections"), report.propertyNames().toList(), "timing 은 태스크 8b 에서 온다")
		assertEquals(AREAS, report["sections"].values().map { it["area"].asString() }, "네 영역, 이 순서")
		for (section in report["sections"].values()) {
			assertEquals(listOf("area", "title", "paragraphs"), section.propertyNames().toList())
			assertEquals("가짜영역제목", section["title"].asString())
			assertEquals(4, section["paragraphs"].size())
			assertEquals(listOf("title", "body", "basis"), section["paragraphs"][0].propertyNames().toList())
			assertEquals(listOf("태양", "달"), section["paragraphs"][0]["basis"].values().map { it.asString() })
		}
		assertEquals(AREAS.sorted(), generator.requests.map(::areaOf).sorted(), "영역마다 한 번씩 부른다")
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
	fun `WRITING 인 채 10분이 지나면 GET 은 FAILED 이고 POST 는 다시 시작한다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()
		post(token)

		jdbc.update("update reports set started_at = now() - interval '9 minutes'")
		assertEquals(WRITING, get(token).body(), "10분이 안 됐으면 쓰는 중이다")
		assertEquals(WRITING, post(token).body())

		jdbc.update("update reports set started_at = now() - interval '11 minutes'")
		assertEquals(FAILED, get(token).body(), "멈춘 것으로 본다")
		assertEquals(WRITING, post(token).body(), "다시 시작한다")
		assertEquals(WRITING, get(token).body())

		gate.countDown()
		val done = await(token, "DONE")
		settle()

		assertEquals(8, generator.requests.size, "멈춘 줄 알았던 생성 4번 + 다시 시작한 4번")
		assertEquals(1, rows())
		assertEquals(1, events.owners.size, "뒤늦게 끝난 옛 생성은 버린다")
		val calls = done["report"]["sections"].values().flatMap { it["paragraphs"].values() }
			.map { it["body"].asString().removePrefix("가짜본문-").toInt() }
		assertTrue(calls.all { it in 5..8 }, "저장된 글은 다시 시작한 생성의 것이다: $calls")
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
			assertEquals(0, jdbc.queryForObject("select count(*) from reports where status <> 'DONE' and body is not null", Int::class.java))
		}
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
		for (secret in listOf("1977", "07-07", "07:07")) assertFalse(stored.contains(secret), "'$secret' 이 저장됐다")
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

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
