package com.byeolmyeong.api.report

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.BirthProfileService
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val UNKNOWN = """{"date":"1990-03-15","time":null}"""
private const val NONE = """{"status":"NONE"}"""
private const val WRITING = """{"status":"WRITING"}"""
private const val FAILED = """{"status":"FAILED"}"""
private const val DONE = """{"status":"DONE"}"""
private val TIME_ONLY_WORDS = listOf("하우스", "상승점", "중천", "행운점", "버텍스")
private val mapper = JsonMapper()

/** 검증을 통과하는 시기 풀이 한 묶음의 답: 받은 줄마다 해를 그대로 옮겨 적는다. [change] 로 줄마다 고쳐 틀린 답을 만든다. */
fun timingBatchJson(request: GenerationRequest, change: (TimingLine, MutableMap<String, Any>) -> Unit = { _, _ -> }): JsonNode {
	val lines = timingLines(request)
	return mapper.valueToTree(
		mapOf(
			"milestones" to lines.filter { !it.id.startsWith("i") }.map { line ->
				mutableMapOf<String, Any>("id" to line.id, "fromYear" to line.fromYear, "toYear" to line.toYear, "title" to "가짜마디제목-${line.id}", "body" to "가짜마디본문-${line.id}")
					.also { change(line, it) }
			},
			"intervals" to lines.filter { it.id.startsWith("i") }.map { line ->
				mutableMapOf<String, Any>(
					"id" to line.id, "fromYear" to line.fromYear, "toYear" to line.toYear,
					"summary" to "가짜한줄-${line.id}", "body" to "가짜설명-${line.id}",
					"detail" to mapOf("changes" to "가짜달라짐-${line.id}", "howTo" to "가짜지내기-${line.id}", "after" to "가짜지나면-${line.id}"),
					"practices" to listOf("가짜실천 하나 해 보기", "가짜실천 둘 적어 두기", "가짜실천 셋 나누기"),
				).also { change(line, it) }
			},
		),
	)
}

/**
 * 나 탭 풀이와 시기 풀이를 함께 받는 가짜 생성기(두 풀이가 같은 고리 · 같은 일꾼을 쓰는지 보려고).
 * 받은 요청을 기억하고, 문([gate])이 닫혀 있으면 열릴 때까지 답하지 않는다.
 */
class FakeDetailGenerator : TextGenerator {
	val requests = CopyOnWriteArrayList<GenerationRequest>()

	@Volatile
	var respondMe: (GenerationRequest, Int) -> JsonNode? = { request, call -> meJson(validMeArea(request, call)) }

	@Volatile
	var respondTiming: (GenerationRequest, Int) -> JsonNode? = { request, _ -> timingBatchJson(request) }

	@Volatile
	var gate: CountDownLatch? = null

	override fun generate(request: GenerationRequest): JsonNode? {
		requests += request
		val call = requests.size
		gate?.await(30, TimeUnit.SECONDS)
		return if (request.purpose == "timing_report") respondTiming(request, timingRequests().size) else respondMe(request, call)
	}

	fun timingRequests(): List<GenerationRequest> = requests.filter { it.purpose == "timing_report" }

	fun meRequests(): List<GenerationRequest> = requests.filter { it.purpose.startsWith("me_report_") }

	/** 그 줄(`- id |`)이 든 시기 묶음 요청의 수. */
	fun batchCalls(id: String): Int = timingRequests().count { request -> timingLines(request).any { it.id == id } }

	fun reset() {
		requests.clear()
		respondMe = { request, call -> meJson(validMeArea(request, call)) }
		respondTiming = { request, _ -> timingBatchJson(request) }
		gate = null
	}
}

class TimingDoneEvents {
	val owners = CopyOnWriteArrayList<UUID>()

	@EventListener
	fun on(event: TimingReportDone) {
		owners += event.ownerId
	}
}

@Import(TestcontainersConfiguration::class, TimingReportApiIntegrationTest.FakeConfig::class)
// 살아 있다는 표시(`active_at`)를 적는 주기를 길게 둔다(테스트가 그 값을 옮겨 놓는 동안 다시 적히지 않게).
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.heartbeat-seconds=3600"],
)
class TimingReportApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun fakeDetailGenerator() = FakeDetailGenerator()

		@Bean
		@Primary
		fun timingEntitlement(start: PurchaseStart, entitlements: EntitlementService) = SwitchableEntitlement(start, entitlements)

		@Bean
		@Primary
		fun timingQuota() = CountingQuota()

		@Bean
		fun timingDoneEvents() = TimingDoneEvents()

		@Bean
		fun meDoneEventsForTiming() = MeDoneEvents()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeDetailGenerator

	@Autowired
	private lateinit var entitlement: SwitchableEntitlement

	@Autowired
	private lateinit var quota: CountingQuota

	@Autowired
	private lateinit var events: TimingDoneEvents

	@Autowired
	private lateinit var profiles: BirthProfileService

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private val korea = ZoneId.of("Asia/Seoul")

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

	private fun post(token: String) = http(port, "POST", "/timing-report", token = token)

	private fun get(token: String) = http(port, "GET", "/timing-report", token = token)

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

	/** 지금 프로필로 서버 밖에서 다시 계산한 시기(표의 글)와 오늘 맡길 줄. */
	private fun expectedDraft(): Pair<TimingDraft, List<TimingItem>> {
		val birth = profiles.chartInput(ownerId())!!
		val draft = computeTiming(birth.chart(), birth.utc)
		return draft to timingItemsForModel(draft, LocalDate.now(korea))
	}

	@Test
	fun `POST 는 202 로 바로 답하고 GET 은 NONE 에서 WRITING 을 거쳐 DONE - 9줄 묶음 · 구간 이름 · 맡긴 구간의 칸 · 대표 구간`() {
		val token = tokenWithProfile(KNOWN)
		val gate = CountDownLatch(1).also { generator.gate = it }
		assertEquals(NONE, get(token).body())

		val posted = post(token)

		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals(WRITING, posted.body())
		assertEquals(WRITING, get(token).body())
		assertEquals(listOf("TIMING WRITING"), jdbc.queryForList("select kind || ' ' || status from detail_reports", String::class.java))
		gate.countDown()
		val done = await(token, "DONE")

		assertEquals(listOf("status", "report"), done.propertyNames().toList())
		val report = done["report"]
		assertEquals(listOf("timeKnown", "milestones", "intervals", "current"), report.propertyNames().toList())
		val (draft, items) = expectedDraft()
		// 묶음: 마디 넷 + 맡길 구간을 9줄씩, 약 4번
		val batches = generator.timingRequests()
		assertEquals((items.size + 8) / 9, batches.size)
		assertTrue(batches.all { timingLines(it).size <= 9 })
		assertEquals(items.map { it.id }.sorted(), batches.flatMap { timingLines(it) }.map { it.id }.sorted(), "맡긴 줄을 빠짐없이 한 번씩")

		val milestones = report["milestones"].values().toList()
		assertEquals(4, milestones.size)
		assertTrue(milestones.all { it["title"].asString() == "가짜마디제목-${it["kind"].asString()}" }, "마디의 제목 · 본문은 모델")
		val asked = items.filter { !it.milestone }.map { it.id }.toSet()
		val intervals = report["intervals"].values().toList()
		assertEquals(draft.timing.intervals.size, intervals.size)
		for ((interval, table) in intervals.zip(draft.timing.intervals)) {
			val id = interval["id"].asString()
			assertEquals(table.title, interval["title"].asString(), "구간 이름은 표의 제목(결정 033 의 3)")
			val keys = interval.propertyNames().toList()
			if (id in asked) {
				assertEquals(listOf("id", "kind", "from", "to", "area") + (if (table.size != null) listOf("size") else emptyList()) + listOf("title", "body", "summary", "detail", "practices", "reason"), keys)
				assertEquals("가짜설명-$id", interval["body"].asString())
				assertEquals(listOf("changes", "howTo", "after"), interval["detail"].propertyNames().toList())
				assertEquals(3, interval["practices"].size())
				assertEquals(listOf("title"), interval["practices"][0].propertyNames().toList())
				assertEquals(mapper.valueToTree<JsonNode>(transitReason(draft.sources.getValue(id))), interval["reason"], "근거는 그 구간을 만든 지나가는 별(when NOW)")
				assertEquals("NOW", interval["reason"]["when"].asString())
			} else {
				assertFalse(keys.any { it in listOf("summary", "detail", "practices", "reason") }, "표의 구간에는 칸이 없다: $id")
				assertEquals(table.body, interval["body"].asString())
			}
		}

		// 지금 · 대표 구간: 저장하지 않고 읽을 때 정한다
		val today = LocalDate.now(korea)
		val current = report["current"]
		assertEquals(today.toString(), current["date"].asString())
		val expectedMain = mainIntervalId(timingNow(draft.timing, today).intervals)
		assertEquals(expectedMain, current["mainId"]?.asString())
		val stored = mapper.readTree(storedBodies().single())
		assertNull(stored["current"], "지금은 저장하지 않는다")

		assertEquals(done, mapper.readTree(get(token).body()), "다시 열면 같은 글")
		assertEquals(listOf(ownerId()), events.owners.toList(), "TimingReportDone 은 한 번")
		assertEquals(listOf(ownerId()), quota.owners.toList(), "생성 한도는 요청 하나에 한 번(묶음이 여럿이어도)")
		assertEquals(listOf(DetailReportKind.TIMING), entitlement.asked.toList())
		assertEquals(listOf("""{"kind": "TIMING"}"""), jdbc.queryForList("select detail::text from audit_logs where action = 'REPORT_REQUESTED'", String::class.java))
	}

	@Test
	fun `완성된 풀이는 다시 만들지 않고 출생 정보를 바꾸면 NONE 이 되어 새로 만든다 - 나 탭 풀이와 따로다`() {
		val token = tokenWithProfile(KNOWN)
		post(token)
		await(token, "DONE")
		settle()
		val calls = generator.requests.size
		assertEquals(DONE, post(token).body())
		settle()
		assertEquals(calls, generator.requests.size, "다시 생성하지 않는다")
		assertEquals(1, quota.owners.size)
		assertEquals("""{"status":"NONE"}""", http(port, "GET", "/me-report", token = token).body(), "나 탭 풀이는 따로 받는다")

		http(port, "PUT", "/profile", UNKNOWN, token)
		assertEquals(NONE, get(token).body())
		assertEquals(0, rows(), "바꾸면 옛 차트의 풀이를 지운다(결정 041)")
		assertEquals(WRITING, post(token).body())
		await(token, "DONE")
		settle() // DONE 을 커밋한 뒤 일꾼이 완성 이벤트를 낸다 — 세기 전에 끝나길 기다린다
		assertEquals(1, rows())
		assertEquals(2, events.owners.size)
	}

	@Test
	fun `옮겨 적은 해가 다르면 그 묶음만 같은 요청으로 다시 부른다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respondTiming = { request, _ ->
			val first = generator.batchCalls("SATURN_RETURN") == 1 && timingLines(request).any { it.id == "SATURN_RETURN" }
			timingBatchJson(request) { line, map -> if (first && line.id == "SATURN_RETURN") map["fromYear"] = line.fromYear + 1 }
		}

		post(token)
		val done = await(token, "DONE")
		settle()

		val (_, items) = expectedDraft()
		val batches = (items.size + 8) / 9
		assertEquals(batches + 1, generator.timingRequests().size, "틀린 묶음만 한 번 더")
		val retried = generator.timingRequests().filter { request -> timingLines(request).any { it.id == "SATURN_RETURN" } }
		assertEquals(retried[0], retried[1], "같은 요청으로 다시 부른다")
		assertEquals(2020, done["report"]["milestones"][1]["year"].asInt(), "해는 서버가 계산한 값")
	}

	@Test
	fun `한 묶음이 세 번 틀리면 FAILED 이고 아무것도 저장하지 않으며 다시 POST 하면 새로 시작한다`() {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Pair<String, (GenerationRequest) -> JsonNode?>> = listOf(
			"실천이 기로 끝나지 않음" to { r -> timingBatchJson(r) { line, map -> if (line.id.startsWith("i")) map["practices"] = listOf("해 봐요", "적어 두기", "나누기") } },
			"실천이 둘" to { r -> timingBatchJson(r) { line, map -> if (line.id.startsWith("i")) map["practices"] = listOf("하나 해 보기", "둘 적어 두기") } },
			"글 안에 기간 밖의 해" to { r -> timingBatchJson(r) { line, map -> if (line.id == "SATURN_RETURN") map["body"] = "2099년에 큰 변화가 와요" } },
			"운명이라는 말" to { r -> timingBatchJson(r) { line, map -> if (line.id.startsWith("i")) map["summary"] = "운명 같은 시기예요" } },
			"줄이 빠짐" to { r -> mapper.valueToTree(mapOf("milestones" to timingBatchJson(r)["milestones"].values().drop(1), "intervals" to timingBatchJson(r)["intervals"])) },
			"빈 자세히" to { r -> timingBatchJson(r) { line, map -> if (line.id.startsWith("i")) map["detail"] = mapOf("changes" to "가", "howTo" to " ", "after" to "다") } },
			"응답을 쓸 수 없음" to { _ -> null },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respondTiming = { request, _ -> if (timingLines(request).any { it.id == "SATURN_RETURN" }) breakIt(request) else timingBatchJson(request) }

			assertEquals(WRITING, post(token).body(), name)
			await(token, "FAILED")
			settle()

			assertEquals(3, generator.batchCalls("SATURN_RETURN"), "$name: 처음 1번 + 다시 2번")
			val others = generator.timingRequests().filter { request -> timingLines(request).none { it.id == "SATURN_RETURN" } }
			assertTrue(others.groupBy { timingLines(it).first().id }.values.all { it.size <= 1 }, "$name: 맞은 묶음은 다시 부르지 않는다")
			assertEquals(listOf<String?>(null), storedBodies(), "$name: 저장하지 않는다")
			assertTrue(events.owners.isEmpty(), name)
		}

		generator.reset()
		assertEquals(WRITING, post(token).body())
		await(token, "DONE")
		settle() // DONE 을 커밋한 뒤 일꾼이 완성 이벤트를 낸다 — 세기 전에 끝나길 기다린다
		assertEquals(1, events.owners.size)
	}

	@Test
	fun `생성 호출이 실패하면 그 묶음을 다시 부르지 않고 FAILED 다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respondTiming = { _, _ -> throw GenerationException("HTTP_500") }
		post(token)
		await(token, "FAILED")
		settle()
		assertTrue(generator.timingRequests().groupBy { timingLines(it).first().id }.values.all { it.size == 1 })
		assertEquals(listOf<String?>(null), storedBodies())
	}

	@Test
	fun `시각을 모르면 운명적 만남 구간과 상승점 · 중천 · 버텍스 · 하우스가 응답과 프롬프트에 없다`() {
		val token = tokenWithProfile(UNKNOWN)
		post(token)
		val done = await(token, "DONE")

		val report = done["report"]
		assertFalse(report["timeKnown"].asBoolean())
		assertEquals(0, report["intervals"].values().count { it["kind"].asString() == "FATED" })
		val text = done.toString()
		for (word in listOf("\"ASC\"", "\"MC\"", "VERTEX", "\"house\"")) assertFalse(text.contains(word), "시각 모름 응답에 $word")
		for (request in generator.timingRequests()) {
			for (word in TIME_ONLY_WORDS) assertFalse(request.userText.contains(word), "시기 요청에 '$word'")
			assertTrue(request.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다"))
		}
	}

	@Test
	fun `지시문 · 프롬프트 - 이름은 정해져 있고 출생 날짜 · 시각 · 장소 · 태어난 해가 없다`() {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")

		for (request in generator.timingRequests()) {
			assertTrue(request.instructions.contains("점성술 용어를 하나도 쓰지 않습니다"), "공통 규칙")
			assertTrue(request.instructions.contains("이름은 정해져 있습니다"))
			assertTrue(timingLines(request).filter { it.id.startsWith("i") }.all { line -> request.userText.lines().single { it.startsWith("- ${line.id} |") }.contains(" | 이름: ") })
			val all = request.instructions + "\n" + request.userText + "\n" + mapper.writeValueAsString(request.schema)
			for (secret in listOf("1977", "07-07", "07:07", "7월", "마포", "서울", "37.5", "126.9", MAPO.toString())) {
				assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
			}
		}
	}

	@Test
	fun `받을 수 없으면 402 이고 출생 정보가 없으면 404 · 세션이 없으면 401 이다`() {
		assertEquals(401, http(port, "POST", "/timing-report").statusCode())
		assertEquals(401, http(port, "GET", "/timing-report").statusCode())
		val noProfile = devToken(port)
		assertEquals("""{"error":"NO_PROFILE"}""", post(noProfile).body())
		assertEquals(NONE, get(noProfile).body())

		http(port, "PUT", "/profile", KNOWN, noProfile)
		entitlement.allow = false
		val denied = post(noProfile)
		assertEquals(402, denied.statusCode())
		assertEquals("""{"error":"PURCHASE_REQUIRED"}""", denied.body())
		assertEquals(0, rows())
		assertTrue(quota.owners.isEmpty())
		settle()
		assertEquals(0, generator.requests.size)
	}

	@Autowired
	private lateinit var details: DetailReportService

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `끝내기에서 오류가 나면 쓰는 중으로 남기지 않고 바로 FAILED 로 둔다 - 공통 고리`(output: CapturedOutput) {
		devToken(port)
		val owner = ownerId()
		generator.respondMe = { _, _ -> mapper.readTree("{}") }
		val job = DetailJob(
			parts = listOf(DetailPart("ONE", GenerationRequest("me_report_test", "지시", "차트", emptyMap()), { it }, { null })),
			assemble = { error("본문을 만들다 실패") },
			done = ::MeReportDone,
		)

		assertEquals(ReportStatus.WRITING, details.request(owner, DetailReportKind.ME, "finish-fails") { job })
		// 맡긴 직후에는 일꾼이 아직 일을 잡기 전이라 "바쁜 일꾼 0 · 줄 0" 으로 보여 settle() 이 바로 지나갈 수 있다.
		// 행이 쓰는 중을 벗어날 때까지 본다 — 10초는 멈춘 것으로 보는 2분보다 한참 짧다("바로").
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		var statuses: List<String?>
		while (true) {
			statuses = jdbc.queryForList("select status from detail_reports where chart_hash = 'finish-fails'", String::class.java)
			if (statuses != listOf("WRITING")) break
			check(System.nanoTime() < deadline) { "10초 안에 쓰는 중을 벗어나지 않는다" }
			Thread.sleep(10)
		}
		settle()

		assertEquals(listOf("FAILED"), statuses, "2분 기다리지 않고 바로")
		assertTrue(output.all.contains("event=me_report_finish_failed error=IllegalStateException"))
		assertFalse(output.all.contains("본문을 만들다 실패"), "예외의 글은 로그에 남기지 않는다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보와 프롬프트와 글을 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		post(token)
		await(token, "DONE")
		settle()
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":null}""", token)
		generator.respondTiming = { request, _ -> timingBatchJson(request) { line, map -> if (line.id.startsWith("i")) map["practices"] = listOf("가짜 해 봐요", "둘", "셋") } }
		post(token)
		await(token, "FAILED")
		settle()

		for (secret in listOf("1977", "07:07", "가짜마디", "가짜설명", "가짜한줄", "가짜실천", "이름: ")) assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		assertTrue(output.all.contains("event=timing_report_started"))
		assertTrue(output.all.contains("event=timing_report_done"))
		assertTrue(Regex("event=timing_report_attempt_rejected area=BATCH\\d reason=NOT_ADVICE_FORM attempt=1").containsMatchIn(output.all))
		assertTrue(Regex("event=timing_report_area_failed area=BATCH\\d reason=ATTEMPTS_EXHAUSTED attempt=3").containsMatchIn(output.all))
	}
}
