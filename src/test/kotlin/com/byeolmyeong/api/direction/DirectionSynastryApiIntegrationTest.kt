package com.byeolmyeong.api.direction

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.Places
import com.byeolmyeong.api.birth.toUtc
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.synastryScore
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.generation.GenerationException
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val HAEUNDAE = 8462808
private const val MY_DATE = "1990-03-15"
private const val MY_TIME = "14:30"
private const val ME = """{"date":"$MY_DATE","time":"$MY_TIME","placeId":$MAPO}"""
private const val PARTNER_DATE = "1988-07-21"
private const val PARTNER_TIME = "06:10"
private const val PARTNER = """"date":"$PARTNER_DATE","time":"$PARTNER_TIME","placeId":$HAEUNDAE"""
private const val FAILED = """{"error":"GENERATION_FAILED"}"""
private val MY_SIDE_WORDS = listOf("태양", "달", "수성", "금성", "화성", "상승점")

/**
 * 방향성 제시의 궁합 주제: 궁합 한 건마다 한 편. 상대의 출생 정보는 저장돼 있지 않아 처음 만들 때 다시 받고, 글만 저장한다(결정 010·011).
 * 궁합은 실제 경로(`POST /synastry/direct`)로 만든다.
 */
@Import(TestcontainersConfiguration::class, DirectionApiIntegrationTest.FakeGeneratorConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000"],
)
class DirectionSynastryApiIntegrationTest {

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

	private fun tokenWithProfile(profile: String = ME): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	/** 궁합을 만들고 id 를 돌려준다. [partner] 는 상대의 출생 정보(JSON 의 키들). */
	private fun synastry(token: String, partner: String = PARTNER, relation: String = "LOVE", name: String = "지우"): JsonNode {
		val response = http(port, "POST", "/synastry/direct", """{"name":"$name","relation":"$relation",$partner}""", token)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	private fun direction(token: String, synastryId: String, partner: String? = PARTNER) =
		http(port, "POST", "/directions", """{"topic":"SYNASTRY","synastryId":"$synastryId"${partner?.let { ",$it" } ?: ""}}""", token)

	private fun chartOf(date: String, time: String?, placeId: Int = MAPO): Chart {
		val place = Places().find(placeId)!!
		return computeChart(toUtc(LocalDate.parse(date), time?.let(LocalTime::parse)), place.lat, place.lon, timeKnown = time != null)
	}

	/** 서버와 같은 계산으로, 원래 상대와 점수가 다른 생일(시각 모름)을 찾는다. */
	private fun dateWithOtherScore(score: Int): String = generateSequence(LocalDate.of(1985, 1, 1)) { it.plusDays(1) }
		.map { it.toString() }
		.first { synastryScore(chartOf(MY_DATE, MY_TIME), chartOf(it, null), Relation.LOVE) != score }

	private fun otherAccountToken(): String {
		val token = "another-accounts-session-token"
		val id = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", id)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(token), id)
		return token
	}

	@Test
	fun `궁합마다 한 번만 생성하고 그 뒤에는 출생 정보 없이도 저장된 글을 돌려준다`() {
		val token = tokenWithProfile()
		val id = synastry(token)["id"].asString()

		val first = direction(token, id)
		val second = direction(token, id, partner = null)
		val third = direction(token, id, partner = """"date":"1999-01-01","time":null""")

		assertEquals(200, first.statusCode(), first.body())
		assertEquals(first.body(), second.body())
		assertEquals(first.body(), third.body(), "이미 있으면 본문의 출생 정보를 읽지 않는다")
		assertEquals(1, generator.prompts.size)
		assertEquals(1, rows())

		val json = mapper.readTree(first.body())
		assertEquals(listOf("topic", "title", "items"), json.propertyNames().toList())
		assertEquals("SYNASTRY", json["topic"].asString())
		assertEquals("가짜제목-첫째", json["title"].asString())
		assertEquals(3, json["items"].size())
		assertEquals(listOf("title", "body", "basis"), json["items"][0].propertyNames().toList())
		assertEquals(listOf("태양", "달"), json["items"][0]["basis"].values().map { it.asString() })

		val row = jdbc.queryForMap("select topic, target from directions")
		assertEquals("SYNASTRY", row["topic"])
		assertEquals(id, row["target"], "대상은 궁합 id 다")
	}

	@Test
	fun `재료는 서버가 다시 계산한 연결과 점수이고 근거는 내 쪽 요소뿐이며 이름과 출생 정보를 주지 않는다`() {
		val token = tokenWithProfile()
		val created = synastry(token, relation = "WORK", name = "지우")

		assertEquals(200, direction(token, created["id"].asString()).statusCode())

		val prompt = generator.prompts.single()
		assertEquals("direction_synastry", prompt.purpose)
		val score = synastryScore(chartOf(MY_DATE, MY_TIME), chartOf(PARTNER_DATE, PARTNER_TIME, HAEUNDAE), Relation.WORK)
		assertEquals(score, created["score"].asInt())
		assertTrue(prompt.facts.contains("[관계 유형]\n일"), prompt.facts)
		assertTrue(prompt.facts.contains("[점수]") && prompt.facts.contains("${score}점"), prompt.facts)
		assertTrue(prompt.facts.contains("[잘 맞는 연결]") && prompt.facts.contains("[부딪히는 연결]"))
		assertTrue(Regex("나의 \\S+\\(\\S+\\) – 상대의 \\S+\\(\\S+\\): ").containsMatchIn(prompt.facts), prompt.facts)
		assertEquals(MY_SIDE_WORDS, prompt.basisVocabulary)
		assertTrue(prompt.facts.endsWith("[근거로 쓸 수 있는 말]\n" + MY_SIDE_WORDS.joinToString(", ")))

		assertTrue(prompt.instructions.startsWith("당신은 한 사람의 출생 차트를 읽고"), "공통 글쓰기 규칙이 앞에 온다")
		assertTrue(prompt.instructions.contains("이번에는 두 사람입니다"), "두 사람 글의 규칙")
		assertTrue(prompt.instructions.contains("정확히 3개"))
		assertTrue(prompt.instructions.contains("이번 주제: 궁합"))
		assertTrue(prompt.instructions.contains("숫자를 하나도 쓰지 않습니다"))
		assertFalse(prompt.instructions.contains("이번 주제: 이성") || prompt.instructions.contains("이번 주제: 미래"))

		val all = prompt.instructions + "\n" + prompt.facts
		for (secret in listOf("지우", "1988", "07-21", "06:10", "해운대", "부산", HAEUNDAE.toString(), "1990", "03-15", "14:30", "마포", MAPO.toString())) {
			assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
		}
		for (word in listOf("하우스", "°")) assertFalse(prompt.facts.contains(word), "재료에 '$word' 가 있다")
	}

	@Test
	fun `내 시각을 모르면 근거에 상승점이 없고 상대의 시각을 몰라도 만든다`() {
		val token = tokenWithProfile("""{"date":"$MY_DATE","time":null}""")
		val unknownPartner = """"date":"$PARTNER_DATE","time":null"""
		val id = synastry(token, partner = unknownPartner)["id"].asString()

		val response = direction(token, id, partner = unknownPartner)

		assertEquals(200, response.statusCode(), response.body())
		val prompt = generator.prompts.single()
		assertEquals(MY_SIDE_WORDS - "상승점", prompt.basisVocabulary)
		assertFalse(prompt.facts.contains("상승점"))

		// 내 시각을 모르는데 상승점을 근거로 들면 버린다
		jdbc.update("delete from directions")
		generator.reset()
		generator.respond = { p, _ -> numberless(p).let { d -> d.copy(items = d.items.map { it.copy(basis = listOf("상승점")) }) } }
		assertEquals(502, direction(token, id, partner = unknownPartner).statusCode())
		assertEquals(0, rows())
	}

	@Test
	fun `다른 궁합은 다른 글을 받는다`() {
		val token = tokenWithProfile()
		val first = synastry(token)["id"].asString()
		val again = synastry(token)["id"].asString()

		val a = direction(token, first)
		val b = direction(token, again)

		assertEquals(200, b.statusCode(), b.body())
		assertNotEquals(a.body(), b.body(), "같은 상대를 다시 입력한 궁합도 새 궁합이다")
		assertEquals(2, generator.prompts.size)
		assertEquals(2, rows())
		assertEquals(a.body(), direction(token, first, partner = null).body())
		assertEquals(b.body(), direction(token, again, partner = null).body())
		assertEquals(2, generator.prompts.size)
	}

	@Test
	fun `만든 글이 없는데 상대의 출생 정보가 없으면 400 BIRTH_REQUIRED 이고 틀린 출생 정보는 400 이다`() {
		val token = tokenWithProfile()
		val id = synastry(token)["id"].asString()

		val cases = listOf(
			null to "BIRTH_REQUIRED",
			""""date":null,"time":"06:10","placeId":$MAPO""" to "BIRTH_REQUIRED",
			""""date":"1988-13-45","time":null""" to "INVALID_DATE",
			""""date":"2999-01-01","time":null""" to "INVALID_DATE",
			""""date":"$PARTNER_DATE","time":"25:00","placeId":$MAPO""" to "INVALID_TIME",
			""""date":"$PARTNER_DATE","time":"06:10"""" to "UNKNOWN_PLACE",
			""""date":"$PARTNER_DATE","time":"06:10","placeId":1""" to "UNKNOWN_PLACE",
		)
		for ((partner, code) in cases) {
			val response = direction(token, id, partner)
			assertEquals(400, response.statusCode(), "$partner")
			assertEquals("""{"error":"$code"}""", response.body(), "$partner")
		}
		assertEquals(0, generator.prompts.size)
		assertEquals(0, rows())
	}

	@Test
	fun `다시 받은 출생 정보가 그 궁합의 것이 아니면 400 BIRTH_MISMATCH 이고 쓰지 않는다`() {
		val token = tokenWithProfile()
		val created = synastry(token)
		val other = dateWithOtherScore(created["score"].asInt())

		val response = direction(token, created["id"].asString(), partner = """"date":"$other","time":null""")

		assertEquals(400, response.statusCode())
		assertEquals("""{"error":"BIRTH_MISMATCH"}""", response.body())
		assertEquals(0, generator.prompts.size)
		assertEquals(0, rows())
	}

	@Test
	fun `내 출생 정보가 바뀌었으면 409 PROFILE_CHANGED 이고 이미 만든 글은 그대로 준다`() {
		val token = tokenWithProfile()
		val written = synastry(token)["id"].asString()
		val unwritten = synastry(token)["id"].asString()
		val stored = direction(token, written).body()

		http(port, "PUT", "/profile", """{"date":"1991-08-02","time":"06:10","placeId":$MAPO}""", token)

		val response = direction(token, unwritten)
		assertEquals(409, response.statusCode())
		assertEquals("""{"error":"PROFILE_CHANGED"}""", response.body())
		assertEquals(409, direction(token, unwritten, partner = null).statusCode(), "출생 정보를 다시 받아도 쓸 수 없으므로 먼저 알린다")
		assertEquals(stored, direction(token, written, partner = null).body())
		assertEquals(1, generator.prompts.size)
		assertEquals(1, rows())
	}

	// 소유자는 토큰에서만 얻는다. 남의 것과 없는 것은 같은 404 다.
	@Test
	fun `남의 궁합과 없는 궁합은 404 이고 남이 만든 글도 돌려주지 않는다`() {
		val mine = tokenWithProfile()
		val id = synastry(mine)["id"].asString()
		val myDirection = direction(mine, id)
		assertEquals(200, myDirection.statusCode())

		val theirs = otherAccountToken()
		assertEquals(200, http(port, "PUT", "/profile", ME, theirs).statusCode())
		for (target in listOf(id, UUID.randomUUID().toString(), "not-a-uuid")) {
			for (partner in listOf(PARTNER, null)) {
				val response = direction(theirs, target, partner)
				assertEquals(404, response.statusCode(), "$target $partner")
				assertEquals("""{"error":"NOT_FOUND"}""", response.body(), "$target $partner")
			}
		}
		assertEquals(404, direction(mine, UUID.randomUUID().toString()).statusCode())
		assertEquals(1, generator.prompts.size)
		assertEquals(1, rows())
		assertEquals(myDirection.body(), direction(mine, id, partner = null).body())
	}

	@Test
	fun `궁합 id 가 없으면 400 이고 세션이 없으면 401 이고 내 출생 정보가 없으면 404 NO_PROFILE 이다`() {
		val token = tokenWithProfile()
		val id = synastry(token)["id"].asString()

		for (body in listOf("""{"topic":"SYNASTRY"}""", """{"topic":"SYNASTRY","synastryId":null,$PARTNER}""")) {
			val response = http(port, "POST", "/directions", body, token)
			assertEquals(400, response.statusCode(), body)
			assertEquals("""{"error":"INVALID_REQUEST"}""", response.body(), body)
		}
		assertEquals(401, http(port, "POST", "/directions", """{"topic":"SYNASTRY","synastryId":"$id",$PARTNER}""").statusCode())

		jdbc.update("delete from profiles")
		val noProfile = direction(token, id)
		assertEquals(404, noProfile.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", noProfile.body())
		assertEquals(0, generator.prompts.size)
		assertEquals(0, rows())
	}

	@Test
	fun `근거가 내 쪽 요소가 아니거나 조언형이 아니거나 개수가 틀리거나 숫자가 있는 글은 저장하지 않는다`() {
		val token = tokenWithProfile()
		val id = synastry(token)["id"].asString()
		val broken: List<Pair<String, (DirectionDraft) -> DirectionDraft?>> = listOf(
			"응답을 쓸 수 없음" to { _ -> null },
			"내 쪽 요소가 아닌 근거(상대 쪽에만 있는 천체)" to { d -> d.copy(items = d.items.map { it.copy(basis = listOf("금성", "토성")) }) },
			"내 차트에는 있지만 궁합에서 보지 않는 근거" to { d -> d.copy(items = d.items.map { it.copy(basis = listOf("중천")) }) },
			"근거 없는 항목" to { d -> d.copy(items = d.items.map { it.copy(basis = emptyList()) }) },
			"항목 제목이 단정" to { d -> d.copy(items = d.items.map { it.copy(title = "두 사람은 결국 가까워진다") }) },
			"본문이 앞일을 예고" to { d -> d.copy(items = d.items.map { it.copy(body = "곧 마음이 통할 거예요.") }) },
			"항목이 2개" to { d -> d.copy(items = d.items.drop(1)) },
			"항목이 4개" to { d -> d.copy(items = d.items + d.items.first()) },
			"점수를 적음" to { d -> d.copy(items = d.items.mapIndexed { i, it -> if (i == 2) it.copy(body = "82점인 사이라 편하게 말해 볼 만해요.") else it }) },
			"숫자가 든 제목" to { d -> d.copy(title = "100% 통하는 사이를 가꿔 볼 만한 때") },
		)
		for ((name, breakIt) in broken) {
			generator.reset()
			generator.respond = { prompt, _ -> breakIt(numberless(prompt)) }
			val response = direction(token, id)
			assertEquals(502, response.statusCode(), name)
			assertEquals(FAILED, response.body(), name)
			assertEquals(3, generator.prompts.size, "$name: 처음 1번 + 다시 2번")
			assertEquals(0, rows(), name)
		}

		generator.reset()
		generator.respond = { _, _ -> throw GenerationException("HTTP_429") }
		assertEquals(502, direction(token, id).statusCode())
		assertEquals(1, generator.prompts.size, "호출 자체의 실패는 다시 부르지 않는다")
		assertEquals(0, rows())

		// 실패는 남지 않는다: 다음 요청은 처음부터 다시 생성한다
		generator.reset()
		generator.respond = { prompt, call -> if (call == 1) numberless(prompt).copy(title = "90점짜리 사이") else numberless(prompt, call) }
		val retried = direction(token, id)
		assertEquals(200, retried.statusCode())
		assertEquals("가짜제목-둘째", mapper.readTree(retried.body())["title"].asString())
		assertEquals(1, rows())
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `궁합의 방향성 제시를 만든 뒤에도 DB 와 로그에 상대의 출생 정보와 행성 위치가 없다`(output: CapturedOutput) {
		val token = tokenWithProfile()
		val created = synastry(token)
		val id = created["id"].asString()
		assertEquals(200, direction(token, id).statusCode())
		// 거절·실패의 로그도 본다
		jdbc.update("delete from directions")
		generator.respond = { p, _ -> numberless(p).copy(title = "77점의 사이") }
		assertEquals(502, direction(token, id).statusCode())
		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		assertEquals(502, direction(token, id).statusCode())
		assertEquals(400, direction(token, id, partner = """"date":"${dateWithOtherScore(created["score"].asInt())}","time":null""").statusCode())
		generator.respond = ::numberless
		assertEquals(200, direction(token, id).statusCode())

		assertEquals(
			setOf("id", "owner_id", "topic", "target", "body", "created_at"),
			jdbc.queryForList("select column_name from information_schema.columns where table_name = 'directions'", String::class.java).toSet(),
		)
		// 프로필(내 것)을 뺀 모든 테이블의 모든 행을 글로 떠서 본다
		val tables = jdbc.queryForList(
			"select table_name from information_schema.tables where table_schema = 'public' and table_name not in ('profiles', 'flyway_schema_history', 'daily_active')",
			String::class.java,
		)
		val storedRaw = tables.joinToString("\n") { table ->
			jdbc.queryForList("select row_to_json(t)::text from $table t", String::class.java).joinToString("\n")
		}
		// 무작위 id 와 시각 표기에 우연히 같은 숫자가 나올 수 있어 그것들은 지우고 본다
		val noise = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{4}-\d{2}-\d{2}[T ][\d:.]+|[0-9a-f]{64}""")
		val stored = noise.replace(storedRaw, "")
		val logged = noise.replace(output.all, "")
		assertTrue(stored.contains("가짜실천하나 해 보기"), "글은 저장한다")
		val longitudes = created["overlay"]["theirs"].values().map { it["longitude"].asString() }
		assertEquals(10, longitudes.size)
		for (secret in listOf("1988", "07-21", "06:10", HAEUNDAE.toString(), "상대의 ") + longitudes) {
			assertFalse(stored.contains(secret), "DB 에 상대의 '$secret' 이 있다")
			assertFalse(logged.contains(secret), "로그에 상대의 '$secret' 이 있다")
		}
		for (secret in listOf("지우", "가짜제목", "가짜본문", "77점", "나의 ", "[점수]", MY_DATE, MY_TIME)) {
			assertFalse(logged.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=direction_attempt_rejected topic=SYNASTRY reason=HAS_NUMBER attempt=1"))
		assertTrue(output.all.contains("event=direction_generation_failed topic=SYNASTRY reason=ATTEMPTS_EXHAUSTED"))
		assertTrue(output.all.contains("event=direction_generation_failed topic=SYNASTRY reason=HTTP_500 attempt=1"))
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
