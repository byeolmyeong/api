package com.byeolmyeong.api.reading

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.ASCENDANT_LABEL
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val UNKNOWN = """{"date":"1990-03-15","time":null,"placeId":$MAPO}"""
private const val FAILED = """{"error":"GENERATION_FAILED"}"""
private val TIME_ONLY_WORDS = listOf("하우스", "상승점", "중천", "행운점", "버텍스")

/** 테스트가 보는 요청의 모습. [basisVocabulary] 는 요청의 구조(스키마)에서 근거로 고를 수 있게 한 말이다. */
data class ReadingPrompt(val instructions: String, val facts: String, val basisVocabulary: List<String>)

/** 실제 생성기 대신 끼우는 가짜. 받은 프롬프트를 기억하고, 테스트가 정한 대로 답한다. */
class FakeReadingGenerator : TextGenerator {
	private val mapper = JsonMapper()
	val prompts = CopyOnWriteArrayList<ReadingPrompt>()

	@Volatile
	var respond: (ReadingPrompt, Int) -> ReadingDraft? = ::validDraft

	override fun generate(request: GenerationRequest): JsonNode? {
		val schema: JsonNode = mapper.valueToTree(request.schema)
		val vocabulary = schema["properties"]["sections"]["items"]["properties"]["basis"]["items"]["enum"].values().map { it.asString() }
		val prompt = ReadingPrompt(request.instructions, request.userText, vocabulary)
		prompts += prompt
		return respond(prompt, prompts.size)?.let { mapper.valueToTree(it) }
	}

	fun reset() {
		prompts.clear()
		respond = ::validDraft
	}
}

/** 검증을 통과하는 글. 부를 때마다 글이 다르다([call] 번째). */
fun validDraft(prompt: ReadingPrompt, call: Int): ReadingDraft {
	val sections = if (ASCENDANT_LABEL in prompt.basisVocabulary) 5 else 4
	return ReadingDraft(
		headline = "가짜총평-$call",
		sections = (1..sections).map { ReadingSection("가짜제목$it", "가짜본문$it-$call", prompt.basisVocabulary.take(2)) },
		strengths = listOf("강점1", "강점2", "강점3"),
		cautions = listOf("보완1", "보완2", "보완3"),
	)
}

@Import(TestcontainersConfiguration::class, ReadingApiIntegrationTest.FakeGeneratorConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class ReadingApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeGeneratorConfig {
		@Bean
		@Primary
		fun fakeReadingGenerator() = FakeReadingGenerator()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeReadingGenerator

	private val mapper = JsonMapper()

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		generator.reset()
	}

	private fun rows(): Int = jdbc.queryForObject("select count(*) from readings", Int::class.java)!!

	private fun tokenWithProfile(profile: String): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	private fun read(token: String) = http(port, "POST", "/readings", token = token)

	@Test
	fun `같은 차트는 한 번만 생성하고 같은 글을 돌려준다`() {
		val token = tokenWithProfile(KNOWN)

		val first = read(token)
		val second = read(token)

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(first.body(), second.body())
		assertEquals(1, generator.prompts.size, "두 번째는 저장본이다")
		assertEquals(1, rows())

		val json = mapper.readTree(first.body())
		assertEquals(listOf("headline", "sections", "strengths", "cautions", "shareId"), json.propertyNames().toList())
		assertEquals("가짜총평-1", json["headline"].asString())
		assertEquals(5, json["sections"].size())
		assertEquals(listOf("title", "body", "basis"), json["sections"][0].propertyNames().toList())
		assertEquals(listOf("태양", "달"), json["sections"][0]["basis"].values().map { it.asString() })
		assertEquals(3, json["strengths"].size())
		assertEquals(3, json["cautions"].size())
		assertTrue(json["shareId"].asString().length >= 22, "공유 식별자는 무작위 16바이트 이상")
	}

	/** 같은 계정이 동시에 두 번 부른다. 두 요청이 생성기 안에서 겹치도록 생성기를 느리게 한다. */
	private fun readTwiceAtOnce(token: String): List<java.net.http.HttpResponse<String>> {
		val start = java.util.concurrent.CountDownLatch(1)
		val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
		try {
			val futures = (1..2).map { pool.submit<java.net.http.HttpResponse<String>> { start.await(); read(token) } }
			start.countDown()
			return futures.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `같은 계정이 같은 요약 해석을 동시에 두 번 부르면 모델은 한 번만 불리고 둘 다 같은 글을 받는다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { prompt, call -> Thread.sleep(1500); validDraft(prompt, call) }

		val (first, second) = readTwiceAtOnce(token)

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(200, second.statusCode(), second.body())
		assertEquals(first.body(), second.body())
		assertEquals(1, generator.prompts.size, "뒤의 요청은 진행 중인 생성을 기다린다(결정 022)")
		assertEquals(1, rows())
	}

	@Test
	fun `동시에 기다리던 요청도 같은 실패를 받고, 그 뒤 요청은 다시 생성한다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { _, _ -> Thread.sleep(500); null }

		val (first, second) = readTwiceAtOnce(token)

		assertEquals(502, first.statusCode())
		assertEquals(502, second.statusCode())
		val attemptsOfOneGeneration = generator.prompts.size
		assertTrue(attemptsOfOneGeneration in 1..3, "한 번의 생성만 돌았다(시도 $attemptsOfOneGeneration 번)")

		generator.respond = ::validDraft
		assertEquals(200, read(token).statusCode(), "실패한 생성의 진행 표시는 지워져 다시 만든다")
		assertEquals(attemptsOfOneGeneration + 1, generator.prompts.size)
	}

	@Test
	fun `출생 정보를 바꾸면 새 차트의 글을 만들고 되돌리면 예전 글이 나온다`() {
		val token = tokenWithProfile(KNOWN)
		val original = read(token).body()

		http(port, "PUT", "/profile", UNKNOWN, token)
		val changed = read(token)
		assertEquals(200, changed.statusCode())
		assertNotEquals(original, changed.body())
		assertEquals(4, mapper.readTree(changed.body())["sections"].size(), "시각을 모르면 4문단")
		assertEquals(2, generator.prompts.size)
		assertEquals(2, rows())

		http(port, "PUT", "/profile", KNOWN, token)
		assertEquals(original, read(token).body())
		assertEquals(2, generator.prompts.size, "다시 생성하지 않는다")
	}

	@Test
	fun `차트에 없는 근거가 오면 다시 생성한다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { prompt, call ->
			val draft = validDraft(prompt, call)
			if (call == 1) draft.copy(sections = draft.sections.map { it.copy(basis = listOf("태양", "없는별")) }) else draft
		}

		val response = read(token)

		assertEquals(200, response.statusCode())
		assertEquals("가짜총평-2", mapper.readTree(response.body())["headline"].asString())
		assertEquals(2, generator.prompts.size)
		assertEquals(generator.prompts[0], generator.prompts[1], "같은 프롬프트로 다시 부른다")
		assertEquals(1, rows())
	}

	@Test
	fun `다시 생성은 두 번까지이고 그래도 틀리면 502 이며 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		// 차트에 아무 천체도 없는 하우스는 없다고 본다: 근거 말에 없는 하우스를 고른다
		generator.respond = { prompt, call ->
			val emptyHouse = (1..12).map(::houseLabel).first { it !in prompt.basisVocabulary }
			val draft = validDraft(prompt, call)
			draft.copy(sections = draft.sections.map { it.copy(basis = listOf(emptyHouse)) })
		}

		val response = read(token)

		assertEquals(502, response.statusCode())
		assertEquals(FAILED, response.body())
		assertEquals(3, generator.prompts.size, "처음 1번 + 다시 2번")
		assertEquals(0, rows())

		// 실패는 남지 않는다: 다음 요청은 처음부터 다시 생성한다
		generator.respond = ::validDraft
		assertEquals(200, read(token).statusCode())
		assertEquals(1, rows())
	}

	@Test
	fun `시각을 모르는데 하우스나 상승점을 근거로 들면 버린다`() {
		val token = tokenWithProfile(UNKNOWN)
		for (basis in listOf("1하우스", "상승점", "중천")) {
			generator.reset()
			generator.respond = { prompt, call ->
				val draft = validDraft(prompt, call)
				draft.copy(sections = draft.sections.map { it.copy(basis = listOf("태양", basis)) })
			}
			assertEquals(502, read(token).statusCode(), basis)
			assertEquals(3, generator.prompts.size, basis)
		}
		assertEquals(0, rows())
	}

	@Test
	fun `비었거나 구조가 틀린 글은 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Pair<String, (ReadingDraft) -> ReadingDraft?>> = listOf(
			"응답을 쓸 수 없음" to { _ -> null },
			"빈 총평" to { d -> d.copy(headline = " ") },
			"문단이 4개" to { d -> d.copy(sections = d.sections.drop(1)) },
			"문단이 없음" to { d -> d.copy(sections = emptyList()) },
			"빈 본문" to { d -> d.copy(sections = d.sections.map { it.copy(body = "") }) },
			"근거 없는 문단" to { d -> d.copy(sections = d.sections.map { it.copy(basis = emptyList()) }) },
			"강점이 2개" to { d -> d.copy(strengths = d.strengths.drop(1)) },
			"빈 보완점" to { d -> d.copy(cautions = listOf("보완1", "", "보완3")) },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respond = { prompt, call -> breakIt(validDraft(prompt, call)) }
			val response = read(token)
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

		val response = read(token)

		assertEquals(502, response.statusCode())
		assertEquals(FAILED, response.body())
		assertEquals(1, generator.prompts.size, "한도·키 문제는 다시 불러도 같다")
		assertEquals(0, rows())
	}

	@Test
	fun `시각을 알면 프롬프트에 하우스와 상승점이 있고 모르면 없다`() {
		read(tokenWithProfile(KNOWN))
		val known = generator.prompts.single()
		assertTrue(known.facts.contains("하우스") && known.facts.contains("상승점: ") && known.facts.contains("중천: "))
		assertTrue(known.basisVocabulary.containsAll(listOf("상승점", "중천", "행운점", "버텍스")))
		assertTrue(known.basisVocabulary.any { it.endsWith("하우스") })
		assertTrue(known.instructions.contains("정확히 5개"))

		generator.reset()
		read(tokenWithProfile(UNKNOWN))
		val unknown = generator.prompts.single()
		for (word in TIME_ONLY_WORDS) {
			assertFalse(unknown.facts.contains(word), "차트 사실에 '$word' 가 있다:\n${unknown.facts}")
			assertFalse(unknown.basisVocabulary.any { it.contains(word) }, "근거 말에 '$word' 가 있다")
		}
		assertTrue(unknown.facts.contains("태양: 물고기"))
		assertTrue(unknown.instructions.contains("정확히 4개"))
		assertTrue(
			unknown.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다") &&
				unknown.instructions.contains("쓰지 않고"),
			"모델에게 쓰지 말라고 알린다",
		)
		assertFalse(unknown.instructions.contains("첫인상과 지향 —"), "시각 모름 구성에 첫인상 문단이 없다")
	}

	// 시각을 모르는 프로필에는 태어난 곳이 없다(결정 007). 곳을 보내 저장했던 때와 같은 차트다.
	@Test
	fun `태어난 곳이 없는 시각 모름 프로필로도 글을 만든다`() {
		val token = tokenWithProfile("""{"date":"1990-03-15","time":null}""")
		assertNull(jdbc.queryForObject("select place_id from profiles", Int::class.java))

		val response = read(token)
		assertEquals(200, response.statusCode(), response.body())
		assertEquals(4, mapper.readTree(response.body())["sections"].size())
		assertEquals(1, rows())
		val withoutPlace = generator.prompts.single()
		assertTrue(withoutPlace.facts.contains("태양: 물고기"))
		for (word in TIME_ONLY_WORDS) assertFalse(withoutPlace.facts.contains(word), "차트 사실에 '$word' 가 있다")

		// placeId 를 보내도 같은 차트라 저장된 글을 그대로 준다(다시 생성하지 않는다)
		assertEquals(200, http(port, "PUT", "/profile", UNKNOWN, token).statusCode())
		assertEquals(response.body(), read(token).body())
		assertEquals(1, generator.prompts.size)
		assertEquals(1, rows())
	}

	@Test
	fun `프롬프트에 출생 날짜와 시각과 장소가 없다`() {
		read(tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}"""))
		read(tokenWithProfile("""{"date":"1977-07-07","time":null,"placeId":$MAPO}"""))

		assertEquals(2, generator.prompts.size)
		for (prompt in generator.prompts) {
			val all = prompt.instructions + "\n" + prompt.facts + "\n" + prompt.basisVocabulary.joinToString(",")
			for (secret in listOf("1977", "07-07", "07:07", "7월", "마포", "서울", "37.5", "126.9", MAPO.toString())) {
				assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
			}
			assertFalse(Regex("""\d{2,}""").containsMatchIn(prompt.facts.replace(Regex("""\d{1,2}하우스"""), "")), "도수 같은 수치가 있다:\n${prompt.facts}")
		}
	}

	@Test
	fun `프로필이 없으면 404 이고 세션이 없으면 401 이다`() {
		val noSession = http(port, "POST", "/readings")
		assertEquals(401, noSession.statusCode())
		assertEquals("""{"error":"UNAUTHORIZED"}""", noSession.body())
		assertEquals(401, http(port, "POST", "/readings", token = "wrong-token").statusCode())

		val noProfile = read(devToken(port))
		assertEquals(404, noProfile.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", noProfile.body())
		assertEquals(0, generator.prompts.size)
		assertEquals(0, rows())
	}

	// 소유자는 토큰에서만 얻는다. 차트가 같아도 남의 글을 돌려쓰지 않는다.
	@Test
	fun `남의 글은 보이지도 돌려쓰이지도 않고 계정을 지우면 그 사람의 글만 지워진다`() {
		val mine = tokenWithProfile(KNOWN)
		val theirs = "another-accounts-session-token"
		val other = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", other)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(theirs), other)

		val myReading = read(mine).body()
		assertEquals(404, read(theirs).statusCode(), "프로필이 없는 남에게 내 글이 나가지 않는다")

		http(port, "PUT", "/profile", KNOWN, theirs)
		val theirReading = read(theirs)
		assertEquals(200, theirReading.statusCode())
		assertEquals(2, generator.prompts.size, "같은 차트여도 남의 글을 돌려쓰지 않고 새로 만든다")
		assertEquals("가짜총평-2", mapper.readTree(theirReading.body())["headline"].asString())
		assertNotEquals(mapper.readTree(myReading)["shareId"].asString(), mapper.readTree(theirReading.body())["shareId"].asString())
		assertEquals(2, rows())
		assertEquals(1, jdbc.queryForObject("select count(*) from readings where owner_id = ?", Int::class.java, other))

		assertEquals(204, http(port, "DELETE", "/account", token = theirs).statusCode())
		assertEquals(1, rows())
		assertEquals(myReading, read(mine).body())

		assertEquals(204, http(port, "DELETE", "/account", token = mine).statusCode())
		assertEquals(0, rows())
	}

	@Test
	fun `출생 정보와 차트 해시 원문을 readings 에 두지 않는다`() {
		read(tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}"""))

		val row = jdbc.queryForMap("select kind, chart_hash, body::text as body, share_id from readings")
		assertEquals("SUMMARY", row["kind"])
		assertTrue(Regex("[0-9a-f]{64}").matches(row["chart_hash"] as String))
		val stored = row.values.joinToString("\n")
		for (secret in listOf("1977", "07-07", "07:07")) assertFalse(stored.contains(secret), "'$secret' 이 저장됐다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보와 프롬프트와 글을 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		read(token)
		// 실패하는 길도 본다: 근거가 틀려 버려지는 글, 호출 실패
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":null,"placeId":$MAPO}""", token)
		generator.respond = { prompt, call -> validDraft(prompt, call).let { d -> d.copy(sections = d.sections.map { it.copy(basis = listOf("없는별")) }) } }
		assertEquals(502, read(token).statusCode())
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		assertEquals(502, read(token).statusCode())

		val facts = generator.prompts.first().facts
		for (secret in listOf("1977", "07:07", "가짜총평", "가짜본문", "없는별", "태양: ", "상승점", "하우스", facts.lines()[1], "정확히 5개")) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=reading_attempt_rejected reason=BASIS_NOT_IN_CHART attempt=1"))
		assertTrue(output.all.contains("event=reading_generation_failed reason=ATTEMPTS_EXHAUSTED"))
		assertTrue(output.all.contains("event=reading_generation_failed reason=HTTP_500 attempt=1"))
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
