package com.byeolmyeong.api.direction

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.generation.houseLabel
import com.byeolmyeong.api.http
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
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val UNKNOWN = """{"date":"1990-03-15","time":null,"placeId":$MAPO}"""
private const val LOVE = """{"topic":"LOVE"}"""
private const val FAILED = """{"error":"GENERATION_FAILED"}"""
private val TIME_ONLY_WORDS = listOf("하우스", "상승점", "중천", "행운점", "버텍스")

/** 테스트가 보는 요청의 모습. [basisVocabulary] 는 요청의 구조(스키마)에서 근거로 고를 수 있게 한 말이다. */
data class DirectionPrompt(val purpose: String, val instructions: String, val facts: String, val basisVocabulary: List<String>)

/** 실제 생성기 대신 끼우는 가짜. 받은 프롬프트를 기억하고, 테스트가 정한 대로 답한다. */
class FakeDirectionGenerator : TextGenerator {
	private val mapper = JsonMapper()
	val prompts = CopyOnWriteArrayList<DirectionPrompt>()

	@Volatile
	var respond: (DirectionPrompt, Int) -> DirectionDraft? = ::validDirection

	override fun generate(request: GenerationRequest): JsonNode? {
		// 궁합 주제의 테스트는 먼저 궁합을 만든다(`POST /synastry`). 그 요약 · 네 영역 요청은 방향성 제시의 것이 아니라 세지 않는다.
		// 방향성 제시에는 궁합의 사이 · 점수만 있으면 되므로 네 영역은 실패시킨다(그 궁합은 FAILED 로 남는다).
		if (request.purpose == "synastry_summary") return mapper.valueToTree(mapOf("keywords" to listOf("가짜키워드하나", "가짜키워드둘", "가짜키워드셋"), "headline" to "가짜한줄요약"))
		if (request.purpose.startsWith("synastry_report_")) throw GenerationException("NOT_A_DIRECTION")
		val schema: JsonNode = mapper.valueToTree(request.schema)
		val vocabulary = schema["properties"]["items"]["items"]["properties"]["basis"]["items"]["enum"].values().map { it.asString() }
		val prompt = DirectionPrompt(request.purpose, request.instructions, request.userText, vocabulary)
		prompts += prompt
		return respond(prompt, prompts.size)?.let { mapper.valueToTree(it) }
	}

	fun reset() {
		prompts.clear()
		respond = ::validDirection
	}
}

/** 검증을 통과하는 글. 부를 때마다 글이 다르다([call] 번째). */
fun validDirection(prompt: DirectionPrompt, call: Int): DirectionDraft = DirectionDraft(
	title = "가짜제목-$call",
	items = (1..3).map { DirectionItem("가짜실천$it 해 보기", "가짜본문$it-$call 해 볼 만해요.", prompt.basisVocabulary.take(2)) },
)

@Import(TestcontainersConfiguration::class, DirectionApiIntegrationTest.FakeGeneratorConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class DirectionApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeGeneratorConfig {
		@Bean
		@Primary
		fun fakeDirectionGenerator() = FakeDirectionGenerator()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeDirectionGenerator

	private val mapper = JsonMapper()

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		generator.reset()
	}

	private fun rows(): Int = jdbc.queryForObject("select count(*) from directions", Int::class.java)!!

	private fun tokenWithProfile(profile: String): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	private fun love(token: String) = http(port, "POST", "/directions", LOVE, token)

	private fun otherAccountToken(): Pair<String, UUID> {
		val token = "another-accounts-session-token"
		val id = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", id)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(token), id)
		return token to id
	}

	@Test
	fun `같은 차트는 한 번만 생성하고 저장된 글을 돌려준다`() {
		val token = tokenWithProfile(KNOWN)

		val first = love(token)
		val second = love(token)

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(first.body(), second.body())
		assertEquals(1, generator.prompts.size, "두 번째는 저장본이다")
		assertEquals(1, rows())

		val json = mapper.readTree(first.body())
		assertEquals(listOf("topic", "title", "items"), json.propertyNames().toList())
		assertEquals("LOVE", json["topic"].asString())
		assertEquals("가짜제목-1", json["title"].asString())
		assertEquals(3, json["items"].size())
		assertEquals(listOf("title", "body", "basis"), json["items"][0].propertyNames().toList())
		assertEquals("가짜실천1 해 보기", json["items"][0]["title"].asString())
		assertEquals(listOf("태양", "달"), json["items"][0]["basis"].values().map { it.asString() })
	}

	@Test
	fun `출생 정보를 바꾸면 새 차트의 글을 만들고 되돌리면 예전 글이 나온다`() {
		val token = tokenWithProfile(KNOWN)
		val original = love(token).body()

		http(port, "PUT", "/profile", """{"date":"1991-08-02","time":"06:10","placeId":$MAPO}""", token)
		val changed = love(token)
		assertEquals(200, changed.statusCode())
		assertNotEquals(original, changed.body())
		assertEquals(2, generator.prompts.size)
		assertEquals(2, rows())

		http(port, "PUT", "/profile", KNOWN, token)
		assertEquals(original, love(token).body())
		assertEquals(2, generator.prompts.size, "다시 생성하지 않는다")
	}

	@Test
	fun `동시에 온 두 요청은 한 행만 남기고 같은 글을 받는다`() {
		val token = tokenWithProfile(KNOWN)
		val bothGenerating = CyclicBarrier(2)
		generator.respond = { prompt, call ->
			bothGenerating.await(10, TimeUnit.SECONDS)
			validDirection(prompt, call)
		}

		val a = CompletableFuture.supplyAsync { love(token) }
		val b = CompletableFuture.supplyAsync { love(token) }
		val first = a.get(20, TimeUnit.SECONDS)
		val second = b.get(20, TimeUnit.SECONDS)

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(200, second.statusCode(), second.body())
		assertEquals(1, rows())
		assertEquals(first.body(), second.body())
	}

	@Test
	fun `차트에 없는 근거가 오면 다시 생성하고 두 번 뒤에도 틀리면 502 이며 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { prompt, call ->
			val draft = validDirection(prompt, call)
			if (call == 1) draft.copy(items = draft.items.map { it.copy(basis = listOf("태양", "없는별")) }) else draft
		}
		val retried = love(token)
		assertEquals(200, retried.statusCode())
		assertEquals("가짜제목-2", mapper.readTree(retried.body())["title"].asString())
		assertEquals(generator.prompts[0], generator.prompts[1], "같은 프롬프트로 다시 부른다")
		assertEquals(1, rows())

		jdbc.update("delete from directions")
		generator.reset()
		// 아무 천체도 없는 하우스는 근거 말에 없다
		generator.respond = { prompt, call ->
			val emptyHouse = (1..12).map(::houseLabel).first { it !in prompt.basisVocabulary }
			validDirection(prompt, call).let { d -> d.copy(items = d.items.map { it.copy(basis = listOf(emptyHouse)) }) }
		}
		val response = love(token)
		assertEquals(502, response.statusCode())
		assertEquals(FAILED, response.body())
		assertEquals(3, generator.prompts.size, "처음 1번 + 다시 2번")
		assertEquals(0, rows())

		// 실패는 남지 않는다: 다음 요청은 처음부터 다시 생성한다
		generator.respond = ::validDirection
		assertEquals(200, love(token).statusCode())
		assertEquals(1, rows())
	}

	@Test
	fun `조언형이 아니거나 앞일을 단정한 글은 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Pair<String, (DirectionDraft) -> DirectionDraft>> = listOf(
			"항목 제목이 단정" to { d -> d.copy(items = d.items.map { it.copy(title = "좋은 사람이 나타난다") }) },
			"본문이 앞일을 예고" to { d -> d.copy(items = d.items.map { it.copy(body = "곧 좋은 사람을 만날 거예요.") }) },
			"한 항목만 예고" to { d -> d.copy(items = d.items.mapIndexed { i, it -> if (i == 2) it.copy(body = "먼저 말해 볼 만해요. 그러면 가까워지게 됩니다.") else it }) },
			"제목이 예고" to { d -> d.copy(title = "반드시 찾아오는 인연") },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respond = { prompt, call -> breakIt(validDirection(prompt, call)) }
			val response = love(token)
			assertEquals(502, response.statusCode(), name)
			assertEquals(FAILED, response.body(), name)
			assertEquals(3, generator.prompts.size, name)
			assertEquals(0, rows(), name)
		}
	}

	@Test
	fun `비었거나 구조가 틀린 글은 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Pair<String, (DirectionDraft) -> DirectionDraft?>> = listOf(
			"응답을 쓸 수 없음" to { _ -> null },
			"빈 제목" to { d -> d.copy(title = " ") },
			"항목이 2개" to { d -> d.copy(items = d.items.drop(1)) },
			"항목이 4개" to { d -> d.copy(items = d.items + d.items.first()) },
			"빈 본문" to { d -> d.copy(items = d.items.map { it.copy(body = "") }) },
			"근거 없는 항목" to { d -> d.copy(items = d.items.map { it.copy(basis = emptyList()) }) },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respond = { prompt, call -> breakIt(validDirection(prompt, call)) }
			val response = love(token)
			assertEquals(502, response.statusCode(), name)
			assertEquals(FAILED, response.body(), name)
			assertEquals(3, generator.prompts.size, name)
			assertEquals(0, rows(), name)
		}
	}

	@Test
	fun `생성 호출이 실패하면 다시 부르지 않고 502 이다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { _, _ -> throw GenerationException("HTTP_429") }

		val response = love(token)

		assertEquals(502, response.statusCode())
		assertEquals(FAILED, response.body())
		assertEquals(1, generator.prompts.size)
		assertEquals(0, rows())
	}

	@Test
	fun `시각을 모르는 차트도 만들고 프롬프트와 근거에 하우스와 상승점이 없다`() {
		val token = tokenWithProfile(UNKNOWN)

		val response = love(token)

		assertEquals(200, response.statusCode(), response.body())
		assertEquals(3, mapper.readTree(response.body())["items"].size())
		val prompt = generator.prompts.single()
		for (word in TIME_ONLY_WORDS) {
			assertFalse(prompt.facts.contains(word), "차트 사실에 '$word' 가 있다:\n${prompt.facts}")
			assertFalse(prompt.basisVocabulary.any { it.contains(word) }, "근거 말에 '$word' 가 있다")
		}
		assertTrue(prompt.facts.contains("금성: "))
		assertTrue(prompt.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다"))

		// 시각을 모르는데 하우스나 상승점을 근거로 들면 버린다
		jdbc.update("delete from directions")
		for (basis in listOf("7하우스", "상승점")) {
			generator.reset()
			generator.respond = { p, call -> validDirection(p, call).let { d -> d.copy(items = d.items.map { it.copy(basis = listOf("금성", basis)) }) } }
			assertEquals(502, love(token).statusCode(), basis)
		}
		assertEquals(0, rows())
	}

	@Test
	fun `지시문은 공통 글쓰기 규칙에 이성 주제의 조언형 규칙을 붙인 것이다`() {
		love(tokenWithProfile(KNOWN))

		val prompt = generator.prompts.single()
		assertEquals("direction_love", prompt.purpose)
		assertTrue(prompt.instructions.startsWith("당신은 한 사람의 출생 차트를 읽고"), "공통 글쓰기 규칙이 앞에 온다")
		assertTrue(prompt.instructions.contains("정확히 3개"))
		assertTrue(prompt.instructions.contains("\"보기\"로 끝"))
		assertTrue(prompt.instructions.contains("연애"))
		assertFalse(prompt.instructions.contains("계산되지 않았습니다"), "시각을 알면 시각 모름 지시가 없다")
		assertTrue(prompt.facts.contains("하우스") && prompt.facts.contains("상승점: "))
	}

	@Test
	fun `프롬프트에 출생 날짜와 시각과 장소가 없다`() {
		love(tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}"""))

		val prompt = generator.prompts.single()
		val all = prompt.instructions + "\n" + prompt.facts + "\n" + prompt.basisVocabulary.joinToString(",")
		for (secret in listOf("1977", "07-07", "07:07", "7월", "마포", "서울", "37.5", "126.9", MAPO.toString())) {
			assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
		}
	}

	@Test
	fun `주제가 없거나 아직 받지 않는 주제면 400 이다`() {
		val token = tokenWithProfile(KNOWN)
		for (body in listOf("""{"topic":"synastry","synastryId":"x"}""", """{"topic":"love"}""", """{"topic":"future"}""", """{"topic":"MONEY"}""", """{}""", """{"topic":null}""")) {
			val response = http(port, "POST", "/directions", body, token)
			assertEquals(400, response.statusCode(), body)
			assertEquals("""{"error":"INVALID_TOPIC"}""", response.body(), body)
		}
		val notJson = http(port, "POST", "/directions", "topic=LOVE", token)
		assertEquals(400, notJson.statusCode())
		assertEquals("""{"error":"INVALID_REQUEST"}""", notJson.body())
		assertEquals(0, generator.prompts.size)
		assertEquals(0, rows())
	}

	@Test
	fun `프로필이 없으면 404 이고 세션이 없으면 401 이다`() {
		val noSession = http(port, "POST", "/directions", LOVE)
		assertEquals(401, noSession.statusCode())
		assertEquals("""{"error":"UNAUTHORIZED"}""", noSession.body())
		assertEquals(401, http(port, "POST", "/directions", LOVE, "wrong-token").statusCode())

		val noProfile = love(devToken(port))
		assertEquals(404, noProfile.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", noProfile.body())
		assertEquals(0, generator.prompts.size)
		assertEquals(0, rows())
	}

	// 소유자는 토큰에서만 얻는다. 차트가 같아도 남의 글을 돌려쓰지 않는다.
	@Test
	fun `남의 글은 보이지도 돌려쓰이지도 않고 계정을 지우면 그 사람의 글만 지워진다`() {
		val mine = tokenWithProfile(KNOWN)
		val (theirs, other) = otherAccountToken()

		val myDirection = love(mine).body()
		assertEquals(404, love(theirs).statusCode(), "프로필이 없는 남에게 내 글이 나가지 않는다")

		http(port, "PUT", "/profile", KNOWN, theirs)
		val theirDirection = love(theirs)
		assertEquals(200, theirDirection.statusCode())
		assertEquals(2, generator.prompts.size, "같은 차트여도 남의 글을 돌려쓰지 않고 새로 만든다")
		assertEquals("가짜제목-2", mapper.readTree(theirDirection.body())["title"].asString())
		assertEquals(2, rows())
		assertEquals(1, jdbc.queryForObject("select count(*) from directions where owner_id = ?", Int::class.java, other))

		assertEquals(204, http(port, "DELETE", "/account", token = theirs).statusCode())
		assertEquals(1, rows())
		assertEquals(myDirection, love(mine).body())
	}

	@Test
	fun `directions 에는 주제와 차트 해시만 두고 출생 정보를 두지 않는다`() {
		love(tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}"""))

		val row = jdbc.queryForMap("select topic, target, body::text as body from directions")
		assertEquals("LOVE", row["topic"])
		assertTrue(Regex("[0-9a-f]{64}").matches(row["target"] as String))
		val stored = row.values.joinToString("\n")
		for (secret in listOf("1977", "07-07", "07:07")) assertFalse(stored.contains(secret), "'$secret' 이 저장됐다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보와 프롬프트와 글을 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		love(token)
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":null,"placeId":$MAPO}""", token)
		generator.respond = { prompt, call -> validDirection(prompt, call).let { d -> d.copy(items = d.items.map { it.copy(body = "곧 만날 거예요.") }) } }
		assertEquals(502, love(token).statusCode())
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		assertEquals(502, love(token).statusCode())

		val facts = generator.prompts.first().facts
		for (secret in listOf("1977", "07:07", "가짜제목", "가짜본문", "만날 거예요", "태양: ", "상승점", "하우스", facts.lines()[1], "정확히 3개")) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=direction_attempt_rejected topic=LOVE reason=NOT_ADVICE_FORM attempt=1"))
		assertTrue(output.all.contains("event=direction_generation_failed topic=LOVE reason=ATTEMPTS_EXHAUSTED"))
		assertTrue(output.all.contains("event=direction_generation_failed topic=LOVE reason=HTTP_500 attempt=1"))
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
