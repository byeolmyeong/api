package com.byeolmyeong.api.direction

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.birth.Places
import com.byeolmyeong.api.birth.toUtc
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.computeIntervals
import com.byeolmyeong.api.chart.computeMilestones
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.timelineFact
import com.byeolmyeong.api.http
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
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val UNKNOWN = """{"date":"1990-03-15","time":null}"""
private const val FUTURE = """{"topic":"FUTURE"}"""
private const val FAILED = """{"error":"GENERATION_FAILED"}"""
private val TIME_ONLY_WORDS = listOf("하우스", "상승점", "중천", "행운점", "버텍스")

/** 방향성 제시의 미래 주제: 서버가 계산한 "지금의 시기" 를 재료로, 그 시기가 같은 동안 한 편(결정 011). */
@Import(TestcontainersConfiguration::class, DirectionApiIntegrationTest.FakeGeneratorConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class DirectionFutureApiIntegrationTest {

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
		generator.respond = ::numberless
	}

	private fun rows(): Int = jdbc.queryForObject("select count(*) from directions", Int::class.java)!!

	private fun tokenWithProfile(profile: String): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	private fun future(token: String) = http(port, "POST", "/directions", FUTURE, token)

	private val today: LocalDate get() = LocalDate.now(KOREA_ZONE)

	/** 프롬프트에서 "[지금의 시기]" 아래의 줄. */
	private fun timingLines(facts: String): List<String> = facts.substringAfter("[지금의 시기]").lines().filter { it.startsWith("- ") }

	/** 서버와 같은 계산(`chart`)으로 구한 오늘의 줄. */
	private fun expectedLines(date: String, time: String?): List<String> {
		val place = Places().find(MAPO)!!
		val utc = toUtc(LocalDate.parse(date), time?.let(LocalTime::parse))
		val chart = computeChart(utc, place.lat, place.lon, timeKnown = time != null)
		fun day(instant: Instant) = instant.atZone(KOREA_ZONE).toLocalDate()
		val milestones = computeMilestones(utc, KOREA_ZONE)
		val intervals = computeIntervals(chart, utc)
		val current = milestones.filter { day(it.from) <= today && today <= day(it.to) }.map { timelineFact(it) } +
			intervals.filter { day(it.from) <= today && today <= day(it.to) }.map { timelineFact(it) }
		val next = listOfNotNull(
			intervals.firstOrNull { day(it.from) > today }?.let { timelineFact(it) },
			milestones.firstOrNull { day(it.from) > today }?.let { timelineFact(it) },
		)
		return current.ifEmpty { listOf("(없음)") }.map { "- 지금 지나는 중 | $it" } + next.ifEmpty { listOf("(없음)") }.map { "- 다음에 오는 것 | $it" }
	}

	@Test
	fun `지금의 시기가 같은 동안에는 한 번만 생성하고 저장된 글을 돌려준다`() {
		val token = tokenWithProfile(KNOWN)

		val first = future(token)
		val second = future(token)

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(first.body(), second.body())
		assertEquals(1, generator.prompts.size, "두 번째는 저장본이다")
		assertEquals(1, rows())

		val json = mapper.readTree(first.body())
		assertEquals(listOf("topic", "title", "items"), json.propertyNames().toList())
		assertEquals("FUTURE", json["topic"].asString())
		assertEquals("가짜제목-첫째", json["title"].asString())
		assertEquals(3, json["items"].size())
		assertEquals(listOf("title", "body", "basis"), json["items"][0].propertyNames().toList())

		// 대상: 차트 해시 | 그 동안의 첫날 | 마지막 날. 오늘이 그 사이다.
		val row = jdbc.queryForMap("select topic, target from directions")
		assertEquals("FUTURE", row["topic"])
		val (hash, from, until) = (row["target"] as String).split("|")
		assertTrue(Regex("[0-9a-f]{64}").matches(hash))
		assertTrue(from <= today.toString() && today.toString() <= until, "오늘 $today 이 $from ~ $until 안이 아니다")
	}

	@Test
	fun `재료는 서버가 계산한 지금의 시기이고 해와 날짜가 없다`() {
		future(tokenWithProfile(KNOWN))

		val prompt = generator.prompts.single()
		assertEquals("direction_future", prompt.purpose)
		assertEquals(expectedLines("1990-03-15", "14:30"), timingLines(prompt.facts))
		val timing = prompt.facts.substringAfter("[지금의 시기]").replace(Regex("\\d+하우스"), "")
		assertFalse(timing.any { it.isDigit() }, "시기의 줄에 하우스 이름 말고 숫자가 있다:\n$timing")
		assertFalse(Regex("(19|20|21)\\d{2}").containsMatchIn(prompt.facts), "프롬프트에 해가 있다")
		assertTrue(prompt.facts.startsWith("[천체]"), "차트가 먼저 온다")
		assertTrue(prompt.instructions.startsWith("당신은 한 사람의 출생 차트를 읽고"), "공통 글쓰기 규칙이 앞에 온다")
		assertTrue(prompt.instructions.contains("정확히 3개"))
		assertTrue(prompt.instructions.contains("이번 주제: 미래"))
		assertTrue(prompt.instructions.contains("숫자를 하나도 쓰지 않습니다"))
		assertFalse(prompt.instructions.contains("이번 주제: 이성"))
		assertFalse(prompt.instructions.contains("계산되지 않았습니다"), "시각을 알면 시각 모름 지시가 없다")
	}

	@Test
	fun `새 시기에 들어가면 새 글을 쓰고 지난 시기의 글은 돌려주지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val first = future(token).body()
		val hash = (jdbc.queryForObject("select target from directions", String::class.java)!!).substringBefore("|")

		// 그 글이 어제 끝난 시기의 것이었다고 해 둔다: 오늘은 새 시기다
		jdbc.update("update directions set target = ?", "$hash|${today.minusYears(1)}|${today.minusDays(1)}")
		val second = future(token)
		assertEquals(200, second.statusCode())
		assertNotEquals(first, second.body())
		assertEquals(2, generator.prompts.size)
		assertEquals(2, rows())

		// 아직 오지 않은 시기의 글도 오늘의 글이 아니다
		jdbc.update("delete from directions")
		jdbc.update(
			"insert into directions (id, owner_id, topic, target, body) select gen_random_uuid(), id, 'FUTURE', ?, ?::jsonb from accounts",
			"$hash|${today.plusDays(1)}|${today.plusYears(1)}", """{"title":"내일의 글","items":[]}""",
		)
		assertEquals("가짜제목-셋째", mapper.readTree(future(token).body())["title"].asString())
		assertEquals(future(token).body(), future(token).body())
		assertEquals(3, generator.prompts.size, "오늘의 글이 생긴 뒤로는 다시 생성하지 않는다")
	}

	@Test
	fun `출생 정보를 바꾸면 새 차트의 글을 만들고 되돌리면 예전 글이 나온다`() {
		val token = tokenWithProfile(KNOWN)
		val original = future(token).body()

		http(port, "PUT", "/profile", """{"date":"1991-08-02","time":"06:10","placeId":$MAPO}""", token)
		val changed = future(token)
		assertEquals(200, changed.statusCode())
		assertNotEquals(original, changed.body())
		assertEquals(2, rows())
		assertEquals(expectedLines("1991-08-02", "06:10"), timingLines(generator.prompts.last().facts))

		http(port, "PUT", "/profile", KNOWN, token)
		assertEquals(original, future(token).body())
		assertEquals(2, generator.prompts.size, "다시 생성하지 않는다")
	}

	@Test
	fun `미래와 이성은 서로의 글을 돌려쓰지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val love = http(port, "POST", "/directions", """{"topic":"LOVE"}""", token).body()

		val future = future(token)

		assertEquals("FUTURE", mapper.readTree(future.body())["topic"].asString())
		assertEquals(2, generator.prompts.size)
		assertEquals(listOf("direction_love", "direction_future"), generator.prompts.map { it.purpose })
		assertEquals(love, http(port, "POST", "/directions", """{"topic":"LOVE"}""", token).body())
		assertEquals(2, rows())
	}

	@Test
	fun `근거가 틀리거나 조언형이 아니거나 개수가 틀리거나 숫자가 있는 글은 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		val broken: List<Pair<String, (DirectionDraft) -> DirectionDraft?>> = listOf(
			"응답을 쓸 수 없음" to { _ -> null },
			"차트에 없는 근거" to { d -> d.copy(items = d.items.map { it.copy(basis = listOf("태양", "없는별")) }) },
			"근거 없는 항목" to { d -> d.copy(items = d.items.map { it.copy(basis = emptyList()) }) },
			"항목 제목이 단정" to { d -> d.copy(items = d.items.map { it.copy(title = "큰 변화가 찾아온다") }) },
			"본문이 앞일을 예고" to { d -> d.copy(items = d.items.map { it.copy(body = "새 기회를 만날 거예요.") }) },
			"항목이 2개" to { d -> d.copy(items = d.items.drop(1)) },
			"항목이 4개" to { d -> d.copy(items = d.items + d.items.first()) },
			"받지 않은 해를 지어냄" to { d -> d.copy(items = d.items.mapIndexed { i, it -> if (i == 0) it.copy(body = "2027년까지 이어지는 시기예요. 적어 볼 만해요.") else it }) },
			"나이를 지어냄" to { d -> d.copy(title = "서른 즈음, 30세를 준비해 볼 만한 때") },
			"숫자가 든 실천" to { d -> d.copy(items = d.items.map { it.copy(title = "하루 10분 걸어 보기") }) },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respond = { prompt, _ -> breakIt(numberless(prompt)) }
			val response = future(token)
			assertEquals(502, response.statusCode(), name)
			assertEquals(FAILED, response.body(), name)
			assertEquals(3, generator.prompts.size, "$name: 처음 1번 + 다시 2번")
			assertEquals(0, rows(), name)
		}

		// 한 번 틀리고 다음에 맞으면 맞은 글을 저장한다
		generator.reset()
		generator.respond = { prompt, call -> if (call == 1) numberless(prompt).copy(title = "2030년의 나") else numberless(prompt, call) }
		val retried = future(token)
		assertEquals(200, retried.statusCode())
		assertEquals("가짜제목-둘째", mapper.readTree(retried.body())["title"].asString())
		assertEquals(2, generator.prompts.size)
		assertEquals(1, rows())
	}

	@Test
	fun `생성 호출이 실패하면 다시 부르지 않고 502 이며 저장하지 않는다`() {
		val token = tokenWithProfile(KNOWN)
		generator.respond = { _, _ -> throw GenerationException("HTTP_429") }

		val response = future(token)

		assertEquals(502, response.statusCode())
		assertEquals(FAILED, response.body())
		assertEquals(1, generator.prompts.size)
		assertEquals(0, rows())
	}

	@Test
	fun `시각을 모르는 차트도 만들고 재료와 근거에 하우스와 상승점이 없다`() {
		val token = tokenWithProfile(UNKNOWN)

		val response = future(token)

		assertEquals(200, response.statusCode(), response.body())
		assertEquals(3, mapper.readTree(response.body())["items"].size())
		val prompt = generator.prompts.single()
		for (word in TIME_ONLY_WORDS) {
			assertFalse(prompt.facts.contains(word), "재료에 '$word' 가 있다:\n${prompt.facts}")
			assertFalse(prompt.basisVocabulary.any { it.contains(word) }, "근거 말에 '$word' 가 있다")
		}
		assertFalse(prompt.facts.contains("운명적 만남 구간"), "시각을 모르면 버텍스 구간이 없다")
		assertEquals(expectedLines("1990-03-15", null), timingLines(prompt.facts))
		assertTrue(prompt.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다"))

		// 시각을 모르는데 하우스나 상승점을 근거로 들면 버린다
		jdbc.update("delete from directions")
		for (basis in listOf("7하우스", "상승점")) {
			generator.reset()
			generator.respond = { p, _ -> numberless(p).let { d -> d.copy(items = d.items.map { it.copy(basis = listOf("금성", basis)) }) } }
			assertEquals(502, future(token).statusCode(), basis)
		}
		assertEquals(0, rows())
	}

	@Test
	fun `프로필이 없으면 404 이고 세션이 없으면 401 이다`() {
		assertEquals(401, http(port, "POST", "/directions", FUTURE).statusCode())

		val noProfile = future(devToken(port))
		assertEquals(404, noProfile.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", noProfile.body())
		assertEquals(0, generator.prompts.size)
		assertEquals(0, rows())
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `프롬프트와 저장과 로그에 출생 정보가 없다`(output: CapturedOutput) {
		val token = tokenWithProfile("""{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""")
		assertEquals(200, future(token).statusCode())

		val prompt = generator.prompts.single()
		val all = prompt.instructions + "\n" + prompt.facts + "\n" + prompt.basisVocabulary.joinToString(",")
		for (secret in listOf("1977", "07-07", "07:07", "7월", "마포", "서울", "37.5", "126.9", MAPO.toString())) {
			assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
		}
		val stored = jdbc.queryForMap("select topic, target, body::text as body from directions").values.joinToString("\n")
		for (secret in listOf("1977", "07-07", "07:07")) assertFalse(stored.contains(secret), "'$secret' 이 저장됐다")

		jdbc.update("delete from directions")
		generator.respond = { p, _ -> numberless(p).copy(title = "2031년의 나") }
		assertEquals(502, future(token).statusCode())
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		assertEquals(502, future(token).statusCode())

		for (secret in listOf("1977", "07:07", "가짜제목", "가짜본문", "2031", "태양: ", "상승점", "하우스", "지금 지나는 중", "변화 구간", "정확히 3개")) {
			assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=direction_attempt_rejected topic=FUTURE reason=HAS_NUMBER attempt=1"))
		assertTrue(output.all.contains("event=direction_generation_failed topic=FUTURE reason=ATTEMPTS_EXHAUSTED"))
		assertTrue(output.all.contains("event=direction_generation_failed topic=FUTURE reason=HTTP_500 attempt=1"))
	}
}

private val ORDINALS = listOf("첫째", "둘째", "셋째", "넷째", "다섯째", "여섯째")

/** 검증을 통과하는 글(숫자 없음). 미래·궁합 주제는 글에 숫자가 있으면 버린다. 부를 때마다 제목이 다르다([call] 번째). */
fun numberless(prompt: DirectionPrompt, call: Int = 1): DirectionDraft = DirectionDraft(
	title = "가짜제목-${ORDINALS[call - 1]}",
	items = listOf("하나", "둘", "셋").map { DirectionItem("가짜실천$it 해 보기", "가짜본문$it 해 볼 만해요.", prompt.basisVocabulary.take(2)) },
)
