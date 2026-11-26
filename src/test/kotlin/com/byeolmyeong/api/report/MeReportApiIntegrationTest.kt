package com.byeolmyeong.api.report

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.computeWorkStyle
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.http
import com.byeolmyeong.api.web.GenerationQuota
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
private val ME_AREAS = listOf("PERSONALITY", "LOVE", "RELATIONSHIP", "WORK", "EMOTION")
private val TIME_ONLY_WORDS = listOf("하우스", "상승점", "중천", "행운점", "버텍스")

private val mapper = JsonMapper()

/** 요청의 구조(스키마)에서 근거로 고를 수 있게 한 후보 id. 연애는 영역의 `basis`, 나머지는 문단의 `basis`. */
fun meCandidateIds(request: GenerationRequest): List<String> {
	val properties = mapper.valueToTree<JsonNode>(request.schema)["properties"]
	val basis = properties["paragraphs"]?.get("items")?.get("properties")?.get("basis") ?: properties["basis"]
	return basis["items"]["enum"].values().map { it.asString() }
}

/** 어느 영역의 요청인지(`me_report_work` → `WORK`). */
fun meAreaOf(request: GenerationRequest): String = request.purpose.removePrefix("me_report_").uppercase()

/** 몇 번째 호출인지를 숫자 없이 적는다(글에 숫자가 있으면 서버가 버린다): 12 → "일이". */
fun callWord(call: Int): String = call.toString().map { "영일이삼사오육칠팔구"[it - '0'] }.joinToString("")

/**
 * 검증을 통과하는 한 영역의 글(고칠 수 있는 맵). 본문에 몇 번째 호출의 답인지 적는다.
 * 근거는 [pick] 이 문단 번호(연애는 0)마다 후보에서 고른다 — 기본은 앞의 둘(해 · 달이 놓인 자리).
 */
fun validMeArea(request: GenerationRequest, call: Int, pick: (List<String>, Int) -> List<String> = { ids, _ -> ids.take(2) }): MutableMap<String, Any> {
	val ids = meCandidateIds(request)
	val word = callWord(call)
	val json = mutableMapOf<String, Any>("title" to "가짜영역제목", "keywords" to listOf("가짜열쇠말가", "가짜열쇠말나", "가짜열쇠말다"))
	val area = meAreaOf(request)
	if (area != "LOVE") json["paragraphs"] = (0..3).map { i -> mapOf("title" to "가짜제목${"가나다라"[i]}", "body" to "가짜본문-$word", "basis" to pick(ids, i)) }
	when (area) {
		"PERSONALITY" -> json["glance"] = mapOf("shining" to listOf("가짜빛남가", "가짜빛남나"), "tiring" to listOf("가짜지침가", "가짜지침나"))
		"LOVE" -> {
			json["practices"] = listOf("가", "나", "다").map { mapOf("title" to "가짜실천$it 해 보기", "body" to "가짜실천본문-$word") }
			json["basis"] = pick(ids, 0)
		}
		"RELATIONSHIP" -> json["closer"] = mapOf("first" to "가짜처음", "close" to "가짜친해지면", "longtime" to "가짜오래된사이")
		"EMOTION" -> json["shaken"] = mapOf("when" to "가짜흔들릴때", "comfort" to "가짜편해지는곳")
	}
	return json
}

/** 맵을 응답 JSON 으로. */
fun meJson(map: Map<String, Any>): JsonNode = mapper.valueToTree(map)

/** 실제 생성기 대신 끼우는 가짜. 받은 요청을 기억하고, 문([gate])이 닫혀 있으면 열릴 때까지 답하지 않는다. */
class FakeMeReportGenerator : TextGenerator {
	val requests = CopyOnWriteArrayList<GenerationRequest>()

	@Volatile
	var respond: (GenerationRequest, Int) -> JsonNode? = { request, call -> meJson(validMeArea(request, call)) }

	@Volatile
	var gate: CountDownLatch? = null

	override fun generate(request: GenerationRequest): JsonNode? {
		requests += request
		val call = requests.size
		gate?.await(30, TimeUnit.SECONDS)
		return respond(request, call)
	}

	fun calls(area: String): Int = requests.count { meAreaOf(it) == area }

	fun reset() {
		requests.clear()
		respond = { request, call -> meJson(validMeArea(request, call)) }
		gate = null
	}
}

class MeDoneEvents {
	val owners = CopyOnWriteArrayList<UUID>()

	@EventListener
	fun on(event: MeReportDone) {
		owners += event.ownerId
	}
}

/** "받을 수 있나" 의 테스트 대역(결정 029 의 4). [allow] 로 거절을 흉내 낸다. */
class SwitchableEntitlement : EntitlementPolicy() {
	@Volatile
	var allow = true
	val asked = CopyOnWriteArrayList<DetailReportKind>()

	override fun allows(ownerId: UUID, kind: DetailReportKind): Boolean {
		asked += kind
		return allow
	}
}

/** 생성 한도를 몇 번 셌는지 세는 대역(한도는 넘지 않게 크게). */
class CountingQuota : GenerationQuota(100_000, 100_000) {
	val owners = CopyOnWriteArrayList<UUID>()

	override fun acquire(ownerId: UUID) {
		owners += ownerId
		super.acquire(ownerId)
	}
}

@Import(TestcontainersConfiguration::class, MeReportApiIntegrationTest.FakeConfig::class)
// 살아 있다는 표시(`active_at`)를 적는 주기를 길게 둬, 테스트가 그 값을 옮겨 놓은 사이에 다시 적히지 않게 한다.
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.heartbeat-seconds=3600"],
)
class MeReportApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun fakeMeReportGenerator() = FakeMeReportGenerator()

		@Bean
		@Primary
		fun switchableEntitlement() = SwitchableEntitlement()

		@Bean
		@Primary
		fun countingQuota() = CountingQuota()

		@Bean
		fun meDoneEvents() = MeDoneEvents()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeMeReportGenerator

	@Autowired
	private lateinit var entitlement: SwitchableEntitlement

	@Autowired
	private lateinit var quota: CountingQuota

	@Autowired
	private lateinit var events: MeDoneEvents

	@Autowired
	private lateinit var profiles: BirthProfileService

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
		jdbc.update("delete from audit_logs")
		generator.reset()
		events.owners.clear()
		entitlement.allow = true
		entitlement.asked.clear()
		quota.owners.clear()
	}

	private fun rows(): Int = jdbc.queryForObject("select count(*) from detail_reports", Int::class.java)!!

	private fun storedBodies(): List<String?> = jdbc.queryForList("select body::text from detail_reports", String::class.java)

	private fun ownerId(): UUID = jdbc.queryForObject("select id from accounts", UUID::class.java)!!

	private fun tokenWithProfile(profile: String): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	private fun post(token: String) = http(port, "POST", "/me-report", token = token)

	private fun get(token: String) = http(port, "GET", "/me-report", token = token)

	private fun closeGate(): CountDownLatch = CountDownLatch(1).also { generator.gate = it }

	/** 상태가 [expected] 가 될 때까지 `GET /me-report` 를 다시 부른다. */
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
	private fun awaitCalls(count: Int) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (generator.requests.size < count) {
			check(System.nanoTime() < deadline) { "생성기가 요청을 받지 않는다" }
			Thread.sleep(10)
		}
	}

	@Test
	fun `POST 는 기다리지 않고 202 를 주고 GET 은 NONE 에서 WRITING 을 거쳐 DONE 이 된다 - 영역 다섯의 순서와 칸`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()
		assertEquals(NONE, get(token).body())

		val posted = post(token)

		// 생성기는 아직 답하지 않았다: 요청은 글을 기다리지 않고 돌아왔다
		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals(WRITING, posted.body())
		assertEquals(WRITING, get(token).body())
		assertEquals(listOf("ME WRITING"), jdbc.queryForList("select kind || ' ' || status from detail_reports", String::class.java))
		assertEquals(listOf<String?>(null), storedBodies(), "완성 전에는 본문이 없다")
		assertTrue(events.owners.isEmpty())

		gate.countDown()
		val done = await(token, "DONE")

		assertEquals(listOf("status", "report"), done.propertyNames().toList())
		val report = done["report"]
		assertEquals(listOf("areas"), report.propertyNames().toList())
		val areas = report["areas"].values().toList()
		assertEquals(ME_AREAS, areas.map { it["area"].asString() }, "다섯 영역, 이 순서")
		val extra = mapOf(
			"PERSONALITY" to listOf("glance"),
			"LOVE" to listOf("practices", "reason"),
			"RELATIONSHIP" to listOf("closer"),
			"WORK" to listOf("workStyle"),
			"EMOTION" to listOf("shaken"),
		)
		for (area in areas) {
			val name = area["area"].asString()
			assertEquals(listOf("area", "title", "keywords", "stars", "paragraphs") + extra.getValue(name), area.propertyNames().toList(), name)
			assertEquals("가짜영역제목", area["title"].asString())
			assertEquals(3, area["keywords"].size())
			assertEquals(if (name == "LOVE") 0 else 4, area["paragraphs"].size(), name)
			// 근거는 해 · 달이 놓인 자리(P-SUN · P-MOON) → 별 둘
			assertEquals(listOf("SUN", "MOON"), area["stars"].values().map { it["body"].asString() }, name)
			assertEquals("PISCES", area["stars"][0]["sign"].asString())
		}
		val paragraph = areas[0]["paragraphs"][0]
		assertEquals(listOf("title", "body", "reason"), paragraph.propertyNames().toList())
		assertEquals("BIRTH", paragraph["reason"]["when"].asString())
		assertEquals(listOf("PLACEMENT", "PLACEMENT"), paragraph["reason"]["items"].values().map { it["kind"].asString() })
		assertEquals(listOf("shining", "tiring"), areas[0]["glance"].propertyNames().toList())
		assertEquals(3, areas[1]["practices"].size())
		assertEquals(listOf("title", "body"), areas[1]["practices"][0].propertyNames().toList())
		assertEquals("BIRTH", areas[1]["reason"]["when"].asString())
		assertEquals(listOf("first", "close", "longtime"), areas[2]["closer"].propertyNames().toList())
		assertEquals(listOf("when", "comfort"), areas[4]["shaken"].propertyNames().toList())

		// 일 방식은 서버가 지금 차트로 계산한 값이다(결정 038)
		val workStyle = areas[3]["workStyle"]
		val expected = computeWorkStyle(profiles.chartInput(ownerId())!!.chart())
		assertEquals(listOf("together", "flexible", "fast"), workStyle.propertyNames().toList())
		assertEquals(listOf(expected.together, expected.flexible, expected.fast), workStyle.values().map { it.asInt() })

		assertEquals(ME_AREAS.sorted(), generator.requests.map(::meAreaOf).sorted(), "영역마다 한 번씩 부른다")
		assertEquals(done, mapper.readTree(get(token).body()), "다시 열면 같은 글")
		assertEquals(listOf(ownerId()), events.owners.toList(), "MeReportDone 은 한 번")
		assertEquals(listOf(ownerId()), quota.owners.toList(), "생성 한도는 요청 하나에 한 번(영역 다섯이어도)")
		assertEquals(listOf(DetailReportKind.ME), entitlement.asked.toList(), "새로 시작할 때 받을 수 있는지 한 번 묻는다")
		// 감사 기록(결정 015): 옛 리포트와 같은 행동 코드에 종류만 붙는다
		assertEquals(listOf("""{"kind": "ME"}"""), jdbc.queryForList("select detail::text from audit_logs where action = 'REPORT_REQUESTED'", String::class.java))
	}

	@Test
	fun `이미 완성된 차트는 다시 만들지 않고 출생 정보를 바꾸면 NONE 이 되어 새로 만든다`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		val original = await(token, "DONE")

		val again = post(token)
		assertEquals(202, again.statusCode())
		assertEquals(DONE, again.body())
		settle()
		assertEquals(5, generator.requests.size, "다시 생성하지 않는다")
		assertEquals(1, quota.owners.size, "저장본은 한도를 쓰지 않는다")
		assertEquals(1, entitlement.asked.size, "완성된 풀이는 받을 수 있는지 묻지 않는다")

		http(port, "PUT", "/profile", UNKNOWN, token)
		assertEquals(NONE, get(token).body(), "지금 차트의 풀이는 아직 없다")
		assertEquals(0, rows(), "바꾸면 옛 차트의 풀이를 지운다(결정 041)")
		assertEquals(WRITING, post(token).body())
		val changed = await(token, "DONE")
		assertEquals(10, generator.requests.size)
		assertEquals(1, rows())
		assertEquals(2, quota.owners.size)
		assertTrue(original != changed)

		http(port, "PUT", "/profile", KNOWN, token)
		assertEquals(NONE, get(token).body(), "되돌려도 옛 풀이는 지워졌으니 없다")
		assertEquals(WRITING, post(token).body())
		await(token, "DONE")
		settle()
		assertEquals(15, generator.requests.size, "되돌린 차트를 다시 만든다")
		assertEquals(1, rows())
		assertEquals(3, events.owners.size)
	}

	@Test
	fun `동시에 온 POST 는 생성을 한 번만 시작하고 쓰는 중에 다시 POST 해도 시작하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()

		val posts = (1..6).map { CompletableFuture.supplyAsync { post(token) } }.map { it.get(20, TimeUnit.SECONDS) }

		for (response in posts) {
			assertEquals(202, response.statusCode(), response.body())
			assertEquals(WRITING, response.body())
		}
		assertEquals(WRITING, post(token).body())
		gate.countDown()
		await(token, "DONE")
		settle()
		assertEquals(5, generator.requests.size, "다섯 영역을 한 번씩만")
		assertEquals(1, rows())
		assertEquals(1, events.owners.size)
		assertEquals(1, quota.owners.size)
	}

	@Test
	fun `틀린 영역만 다시 생성하고 나머지는 다시 부르지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { request, call ->
			val area = validMeArea(request, call)
			if (meAreaOf(request) == "WORK" && generator.calls("WORK") == 1) area["paragraphs"] = (area["paragraphs"] as List<*>).take(3)
			meJson(area)
		}

		post(token)
		await(token, "DONE")

		assertEquals(2, generator.calls("WORK"))
		assertEquals(6, generator.requests.size, "나머지 네 영역은 한 번씩")
		val work = generator.requests.filter { meAreaOf(it) == "WORK" }
		assertEquals(work[0], work[1], "같은 요청으로 다시 부른다")
		assertEquals(1, quota.owners.size, "다시 생성해도 한도는 한 번")
	}

	@Suppress("UNCHECKED_CAST")
	private fun MutableMap<String, Any>.everyParagraph(key: String, value: Any) {
		this["paragraphs"] = (this["paragraphs"] as List<Map<String, Any>>).map { it + (key to value) }
	}

	@Test
	fun `한 영역이 세 번 틀리면 FAILED 이고 아무것도 저장하지 않으며 다시 POST 하면 새로 시작한다`() {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Triple<String, String, (GenerationRequest, Int) -> JsonNode?>> = listOf(
			Triple("차트에 없는 근거", "PERSONALITY") { r, c -> meJson(validMeArea(r, c).also { it.everyParagraph("basis", listOf("P-NOPE")) }) },
			Triple("근거가 셋", "WORK") { r, c -> meJson(validMeArea(r, c) { ids, _ -> ids.take(3) }) },
			Triple("같은 근거 둘", "EMOTION") { r, c -> meJson(validMeArea(r, c) { ids, _ -> listOf(ids[0], ids[0]) }) },
			Triple("근거 없는 문단", "RELATIONSHIP") { r, c -> meJson(validMeArea(r, c) { _, _ -> emptyList() }) },
			Triple("문단이 다섯", "WORK") { r, c -> meJson(validMeArea(r, c).also { it["paragraphs"] = (it["paragraphs"] as List<*>) + (it["paragraphs"] as List<*>).take(1) }) },
			Triple("키워드가 둘", "EMOTION") { r, c -> meJson(validMeArea(r, c).also { it["keywords"] = listOf("가", "나") }) },
			Triple("같은 키워드", "PERSONALITY") { r, c -> meJson(validMeArea(r, c).also { it["keywords"] = listOf("가", "가", "나") }) },
			Triple("빈 영역 제목", "WORK") { r, c -> meJson(validMeArea(r, c).also { it["title"] = " " }) },
			Triple("글에 숫자", "WORK") { r, c -> meJson(validMeArea(r, c).also { it.everyParagraph("body", "혼자 하는 쪽이 70 정도예요") }) },
			Triple("글에 퍼센트", "RELATIONSHIP") { r, c -> meJson(validMeArea(r, c).also { it["closer"] = mapOf("first" to "열에 일곱 %", "close" to "가", "longtime" to "나") }) },
			Triple("앞일을 단정", "EMOTION") { r, c -> meJson(validMeArea(r, c).also { it.everyParagraph("body", "곧 마음이 편해질 거예요") }) },
			Triple("실천이 기로 끝나지 않음", "LOVE") { r, c -> meJson(validMeArea(r, c).also { it["practices"] = listOf("전해요", "말하기", "듣기").map { t -> mapOf("title" to t, "body" to "이유") } }) },
			Triple("실천이 둘", "LOVE") { r, c -> meJson(validMeArea(r, c).also { it["practices"] = (it["practices"] as List<*>).take(2) }) },
			Triple("연애의 근거가 없음", "LOVE") { r, c -> meJson(validMeArea(r, c).also { it["basis"] = emptyList<String>() }) },
			Triple("한눈에 보기가 하나", "PERSONALITY") { r, c -> meJson(validMeArea(r, c).also { it["glance"] = mapOf("shining" to listOf("가"), "tiring" to listOf("가", "나")) }) },
			Triple("흔들릴 때가 빠짐", "EMOTION") { r, c -> meJson(validMeArea(r, c).also { it.remove("shaken") }) },
			Triple("응답을 쓸 수 없음", "RELATIONSHIP") { _, _ -> null },
			Triple("타입이 틀림", "WORK") { _, _ -> mapper.readTree("""{"title":1,"keywords":"x","paragraphs":[]}""") },
		)
		for ((name, brokenArea, breakIt) in broken) {
			generator.reset()
			generator.respond = { request, call -> if (meAreaOf(request) == brokenArea) breakIt(request, call) else meJson(validMeArea(request, call)) }

			val posted = post(token)
			assertEquals(202, posted.statusCode(), name)
			assertEquals(WRITING, posted.body(), "$name: FAILED 뒤의 POST 는 다시 시작한다")
			await(token, "FAILED")
			settle()

			assertEquals(FAILED, get(token).body(), name)
			assertEquals(3, generator.calls(brokenArea), "$name: 처음 1번 + 다시 2번")
			for (area in ME_AREAS - brokenArea) assertTrue(generator.calls(area) <= 1, "$name: 맞은 영역은 다시 부르지 않는다")
			assertEquals(1, rows(), name)
			assertEquals(listOf<String?>(null), storedBodies(), "$name: 본문을 저장하지 않는다")
			assertTrue(events.owners.isEmpty(), name)
		}

		generator.reset()
		assertEquals(WRITING, post(token).body())
		await(token, "DONE")
		settle() // DONE 을 커밋한 뒤 일꾼이 완성 이벤트를 낸다 — 세기 전에 끝나길 기다린다
		assertEquals(5, generator.requests.size)
		assertEquals(1, rows())
		assertEquals(1, events.owners.size)
	}

	@Test
	fun `생성 호출이 실패하면 그 영역을 다시 부르지 않고 FAILED 이다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { request, call ->
			if (meAreaOf(request) == "RELATIONSHIP") throw GenerationException("HTTP_429") else meJson(validMeArea(request, call))
		}

		post(token)
		await(token, "FAILED")
		settle()

		assertEquals(1, generator.calls("RELATIONSHIP"), "한도 · 키 문제는 다시 불러도 같다")
		assertEquals(listOf<String?>(null), storedBodies())
		assertTrue(events.owners.isEmpty())
	}

	@Test
	fun `쓰는 도중 서버가 꺼진 풀이는 살아 있다는 표시가 2분 끊기면 FAILED 이고 다시 시작할 수 있다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		post(token)
		await(token, "FAILED")
		settle()
		generator.reset()
		// 다른 서버(또는 재시작하기 전의 이 서버)가 30분 전에 자리를 잡고 쓰다가 꺼졌다: 이 서버에는 그 생성이 없다
		jdbc.update("update detail_reports set status = 'WRITING', started_at = now() - interval '30 minutes', active_at = now() - interval '1 minute'")

		assertEquals(WRITING, get(token).body(), "표시가 끊긴 지 2분이 안 됐으면 쓰는 중으로 본다")
		assertEquals(WRITING, post(token).body())
		settle()
		assertEquals(0, generator.requests.size, "다시 시작하지 않았다")

		jdbc.update("update detail_reports set active_at = now() - interval '3 minutes'")
		assertEquals(FAILED, get(token).body(), "멈춘 것으로 본다")
		assertEquals(WRITING, post(token).body(), "다시 시작한다")
		await(token, "DONE")
		settle()

		assertEquals(5, generator.requests.size)
		assertEquals(1, rows())
		assertEquals(1, events.owners.size)
	}

	@Test
	fun `다른 생성이 자리를 잡으면 옛 생성은 모델을 더 부르지 않고 덮어쓰지도 못한다`() {
		val token = tokenWithProfile(KNOWN)
		val gate = closeGate()
		// 옛 생성이 받을 답(1~5번째 호출)은 모두 틀렸다: 자리를 잃지 않았다면 다시 불렀을 것이다
		generator.respond = { request, call ->
			val area = validMeArea(request, call)
			if (call <= 5) area["keywords"] = listOf("가")
			meJson(area)
		}
		post(token)
		awaitCalls(5)

		jdbc.update("update detail_reports set active_at = now() - interval '3 minutes'")
		assertEquals(FAILED, get(token).body(), "멈춘 것으로 본다")
		assertEquals(WRITING, post(token).body(), "다시 시작한다")

		gate.countDown()
		val done = await(token, "DONE")
		settle()

		assertEquals(10, generator.requests.size, "옛 생성이 이미 부른 5번 + 다시 시작한 5번")
		assertEquals(1, rows())
		assertEquals(1, events.owners.size, "옛 생성은 아무것도 남기지 않는다")
		val written = done["report"]["areas"].values().flatMap { it["paragraphs"].values() }.map { it["body"].asString() }.toSet()
		assertTrue(written.none { it in (1..5).map { c -> "가짜본문-${callWord(c)}" } }, "저장된 글은 다시 시작한 생성의 것이다: $written")
	}

	@Test
	fun `시각을 모르면 응답과 프롬프트 어디에도 하우스 · 상승점 · 중천 · 하우스 모임이 없다`() {
		// 근거 후보를 고루 고르게 한다(문단마다 다른 후보 — 각도 · 다스리는 별 · 하우스 모임까지)
		val spread: (List<String>, Int) -> List<String> = { ids, i -> listOf(ids[(i * 5) % ids.size], ids[(i * 5 + 3) % ids.size]).distinct() }
		generator.respond = { request, call ->
			val offset = ME_AREAS.indexOf(meAreaOf(request))
			meJson(validMeArea(request, call) { ids, i -> spread(ids, i + offset * 4) })
		}

		val token = tokenWithProfile(KNOWN)
		post(token)
		val known = await(token, "DONE").toString()
		// 시각을 알면 나오는 것(아래에서 없음을 보는 것이 헛되지 않게)
		assertTrue(known.contains("\"house\""), "시각을 알면 놓인 자리에 하우스가 있다")
		for (request in generator.requests) {
			assertTrue("P-ASC" in meCandidateIds(request) && "P-MC" in meCandidateIds(request))
			assertTrue(request.userText.contains("상승점: "))
			assertFalse(request.instructions.contains("계산되지 않았습니다"))
		}

		settle()
		generator.requests.clear()
		http(port, "PUT", "/profile", UNKNOWN, token)
		post(token)
		val unknown = await(token, "DONE")
		val text = unknown.toString()
		for (word in listOf("\"house\"", "\"ASC\"", "\"MC\"", "CLUSTER", "VERTEX")) assertFalse(text.contains(word), "시각 모름 응답에 $word")
		assertEquals(5, generator.requests.size)
		for (request in generator.requests) {
			for (word in TIME_ONLY_WORDS) assertFalse(request.userText.contains(word), "차트 사실에 '$word' 가 있다")
			assertTrue(meCandidateIds(request).none { it.startsWith("C-") || it.endsWith("-ASC") || it.endsWith("-MC") || "-ASC-" in it || "-MC-" in it }, "후보에 시각이 필요한 것")
			assertTrue(request.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다"), "모델에게 쓰지 말라고 알린다")
		}
		val expected = computeWorkStyle(profiles.chartInput(ownerId())!!.chart())
		assertEquals(listOf(expected.together, expected.flexible, expected.fast), unknown["report"]["areas"][3]["workStyle"].values().map { it.asInt() })
	}

	@Test
	fun `영역마다 그 영역의 지시문과 구조가 가고 공통 글쓰기 규칙이 함께 간다`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		await(token, "DONE")

		val byArea = generator.requests.associateBy(::meAreaOf)
		assertEquals(ME_AREAS.toSet(), byArea.keys)
		assertEquals(1, byArea.values.map { it.userText }.distinct().size, "차트는 다섯 영역이 같다")
		assertEquals(5, byArea.values.map { it.instructions }.distinct().size, "지시문은 영역마다 다르다")
		for (request in byArea.values) {
			assertTrue(request.instructions.contains("점성술 용어를 하나도 쓰지 않습니다"), "공통 규칙")
			assertTrue(request.instructions.contains("자세한 풀이"), "나 탭 풀이 공통")
			assertTrue(request.userText.contains("[근거 후보]"))
		}
		assertTrue(byArea.getValue("RELATIONSHIP").instructions.contains("나는 사람을 어떻게 대하는 사람인가"))
		assertTrue(byArea.getValue("LOVE").instructions.contains("practices"))
		val workSchema = mapper.valueToTree<JsonNode>(byArea.getValue("WORK").schema)
		assertEquals(listOf("title", "keywords", "paragraphs"), workSchema["properties"].propertyNames().toList(), "일 방식은 모델이 쓰지 않는다")
	}

	@Test
	fun `프롬프트에 출생 날짜와 시각과 장소가 없다`() {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":null,"placeId":$MAPO}""", token)
		post(token)
		await(token, "DONE")

		assertEquals(10, generator.requests.size)
		for (request in generator.requests) {
			val all = request.instructions + "\n" + request.userText + "\n" + mapper.writeValueAsString(request.schema)
			for (secret in listOf("1977", "07-07", "07:07", "7월", "마포", "서울", "37.5", "126.9", MAPO.toString())) {
				assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
			}
		}
	}

	@Test
	fun `프로필이 없으면 POST 는 404 이고 GET 은 NONE 이며 세션이 없으면 401 이다`() {
		for (method in listOf("POST", "GET")) {
			val noSession = http(port, method, "/me-report")
			assertEquals(401, noSession.statusCode(), method)
			assertEquals("""{"error":"UNAUTHORIZED"}""", noSession.body())
			assertEquals(401, http(port, method, "/me-report", token = "wrong-token").statusCode())
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
		assertTrue(quota.owners.isEmpty())
	}

	@Test
	fun `받을 수 없으면 402 이고 행을 만들지 않으며 한도도 세지 않는다 - 완성된 풀이는 묻지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		entitlement.allow = false

		val denied = post(token)

		assertEquals(402, denied.statusCode())
		assertEquals("""{"error":"PURCHASE_REQUIRED"}""", denied.body())
		assertEquals(0, rows(), "FAILED 로 남는 행도 없다")
		assertTrue(quota.owners.isEmpty(), "생성 한도를 세기 전에 묻는다")
		assertEquals(NONE, get(token).body())
		settle()
		assertEquals(0, generator.requests.size)

		entitlement.allow = true
		assertEquals(WRITING, post(token).body())
		await(token, "DONE")
		settle()

		entitlement.allow = false
		entitlement.asked.clear()
		val again = post(token)
		assertEquals(202, again.statusCode())
		assertEquals(DONE, again.body(), "완성된 풀이는 그대로 준다")
		assertTrue(entitlement.asked.isEmpty(), "완성된 풀이의 POST 와 GET 은 묻지 않는다")
		assertEquals("DONE", mapper.readTree(get(token).body())["status"].asString())

		http(port, "PUT", "/profile", UNKNOWN, token)
		assertEquals(402, post(token).statusCode(), "새 차트의 풀이는 새로 시작이라 묻는다")
		assertEquals(0, rows(), "옛 차트의 풀이는 바꿀 때 지워졌고(결정 041) 402 는 행을 만들지 않는다")
		assertEquals(listOf(DetailReportKind.ME), entitlement.asked.toList())
	}

	// 소유자는 토큰에서만 얻는다. 차트가 같아도 남의 풀이를 보여 주지 않는다.
	@Test
	fun `남의 풀이는 보이지 않고 계정을 지우면 그 사람의 풀이만 지워진다`() {
		val mine = tokenWithProfile(KNOWN)
		post(mine)
		val myReport = await(mine, "DONE")

		val theirs = "another-accounts-session-token"
		val other = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", other)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(theirs), other)
		assertEquals(NONE, get(theirs).body(), "프로필이 없는 남에게 내 풀이가 나가지 않는다")
		http(port, "PUT", "/profile", KNOWN, theirs)
		assertEquals(NONE, get(theirs).body(), "차트가 같아도 남의 풀이는 없는 것이다")
		assertEquals(WRITING, post(theirs).body())
		await(theirs, "DONE")
		assertEquals(10, generator.requests.size, "남의 글을 돌려쓰지 않고 새로 만든다")
		assertEquals(2, rows())

		assertEquals(204, http(port, "DELETE", "/account", token = theirs).statusCode())
		assertEquals(1, rows())
		assertEquals(myReport, mapper.readTree(get(mine).body()))
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
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보와 프롬프트와 글을 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")
		val facts = generator.requests.first().userText
		// 실패하는 길도 본다: 숫자가 있어 버려지는 글, 호출 실패
		settle()
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":null,"placeId":$MAPO}""", token)
		generator.respond = { request, call -> meJson(validMeArea(request, call).also { it["title"] = "가짜 제목 셋째 3" }) }
		post(token)
		await(token, "FAILED")
		settle()
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		post(token)
		await(token, "FAILED")
		settle()

		for (secret in listOf("1977", "07:07", "가짜영역제목", "가짜본문", "가짜제목", "가짜실천", "셋째", "태양: ", "상승점", "하우스", "P-SUN", facts.lines()[1])) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=me_report_started"))
		assertTrue(output.all.contains("event=me_report_done"))
		assertTrue(Regex("event=me_report_attempt_rejected area=[A-Z]+ reason=HAS_NUMBER attempt=1").containsMatchIn(output.all))
		assertTrue(Regex("event=me_report_area_failed area=[A-Z]+ reason=ATTEMPTS_EXHAUSTED attempt=3").containsMatchIn(output.all))
		assertTrue(Regex("event=me_report_area_failed area=[A-Z]+ reason=HTTP_500 attempt=1").containsMatchIn(output.all))
		assertTrue(output.all.contains("event=me_report_failed"))
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
