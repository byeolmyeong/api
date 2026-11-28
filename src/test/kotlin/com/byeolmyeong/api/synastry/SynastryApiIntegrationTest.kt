package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.synastryAspects
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
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GENERATION_FAILED = """{"error":"GENERATION_FAILED"}"""
private const val LOW_LABEL = "다른 결의 사이"
private const val HAEUNDAE = 8462808

/** 직접 입력 궁합: 계산·저장·목록·랭킹. 리포트는 SynastryReportApiIntegrationTest. */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000"],
)
class SynastryApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@BeforeEach
	@AfterEach
	fun clean() {
		jdbc.update("delete from accounts")
		generator.reset()
	}

	private fun rows(): Int = jdbc.queryForObject("select count(*) from synastries", Int::class.java)!!

	private fun tokenWithProfile(profile: String = ME): String {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", profile, token).statusCode())
		return token
	}

	/** 다른 계정의 세션. 개발용 로그인은 늘 같은 계정이라 직접 만든다. */
	private fun otherToken(): String {
		val other = UUID.randomUUID()
		val token = "other-token-$other"
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", other, "someone-$other")
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(token), other)
		return token
	}

	private fun direct(token: String, body: String) = http(port, "POST", "/synastry/direct", body, token)

	private fun created(token: String, body: String): JsonNode {
		val response = direct(token, body)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	private fun json(token: String, path: String): JsonNode {
		val response = http(port, "GET", path, token = token)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	private fun keys(node: JsonNode): Set<String> = node.propertyNames().toSet()

	@Test
	fun `직접 입력하면 서버가 계산한 점수와 생성한 키워드 한 줄 요약과 겹친 휠을 준다`() {
		val token = tokenWithProfile()

		val synastry = created(token, directBody(name = "지우", relation = "LOVE", date = "1988-07-21", time = "06:10", placeId = MAPO))

		assertEquals("지우", synastry["name"].asString())
		assertEquals("LOVE", synastry["relation"].asString())
		assertEquals(expectedScore("1988-07-21", Relation.LOVE, partnerTime = "06:10"), synastry["score"].asInt())
		assertEquals(listOf("가짜키워드하나", "가짜키워드둘", "가짜키워드셋"), synastry["keywords"].values().map { it.asString() })
		assertEquals("가짜한줄요약", synastry["headline"].asString())
		assertEquals("NONE", synastry["reportStatus"].asString())
		assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(synastry["createdAt"].asString()), synastry["createdAt"].toString())
		UUID.fromString(synastry["id"].asString())
		assertEquals(1, generator.summaries.size)
		assertEquals(1, rows())

		// 겹친 휠: 내 천체 10개와 상승점, 상대 천체 10개, 점수에 쓴 각도의 선
		val overlay = synastry["overlay"]
		val mine = chartOf(MY_DATE, MY_TIME)
		val theirs = chartOf("1988-07-21", "06:10")
		fun hundredths(value: Double) = floor(value * 100.0) / 100.0
		assertEquals(mine.positions.map { it.body.name to hundredths(it.longitude) }, overlay["mine"].values().map { it["body"].asString() to it["longitude"].asDouble() })
		assertEquals(theirs.positions.map { it.body.name to hundredths(it.longitude) }, overlay["theirs"].values().map { it["body"].asString() to it["longitude"].asDouble() })
		assertEquals(hundredths(mine.houses!!.ascendant), overlay["mineAsc"].asDouble())
		assertEquals(
			synastryAspects(mine, theirs).map { Triple(it.mine.name, it.theirs.name, it.type.name) },
			overlay["lines"].values().map { Triple(it["mine"].asString(), it["theirs"].asString(), it["type"].asString()) },
		)
		assertEquals(setOf("mine", "mineAsc", "theirs", "lines"), keys(overlay))
	}

	@Test
	fun `점수는 서버가 계산해 모델에게 알려 주고 모델에게 이름과 생일과 도수를 주지 않는다`() {
		val token = tokenWithProfile()

		val synastry = created(token, directBody(name = "별난이름", relation = "FRIEND", date = "1988-07-21", time = "06:10", placeId = MAPO))

		val request = generator.summaries.single()
		assertTrue(request.userText.contains("${synastry["score"].asInt()}점"), request.userText)
		assertTrue(request.userText.contains("친구"), request.userText)
		assertTrue(request.userText.contains("나의 "), request.userText)
		assertTrue(request.userText.contains("상대의 "), request.userText)
		for (secret in listOf("별난이름", "1988", "07-21", "06:10", "1990", "14:30", "마포", MAPO.toString())) {
			assertFalse(request.userText.contains(secret), "프롬프트에 '$secret' 이 있다")
			assertFalse(request.instructions.contains(secret), "지시문에 '$secret' 이 있다")
		}
		// 도수(소수)가 없다: 숫자는 점수와 기준 점수뿐이다
		assertFalse(Regex("""\d+\.\d""").containsMatchIn(request.userText), request.userText)
		// 응답 구조에 점수 칸이 없다
		assertEquals(setOf("keywords", "headline"), (request.schema["properties"] as Map<*, *>).keys)
		// 공통 글쓰기 규칙을 쓴다
		assertTrue(request.instructions.contains("점성술 용어를 하나도 쓰지 않습니다"))
	}

	@Test
	fun `친구는 가족 · 동료까지 아우르는 가까운 사이로 읽어도 어색하지 않게 쓰게 한다`() {
		val token = tokenWithProfile()

		created(token, directBody(relation = "FRIEND"))

		val instructions = generator.summaries.single().instructions
		for (word in listOf("가까운 사이", "가족", "동료")) assertTrue(instructions.contains(word), "지시문에 '$word' 이 없다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `상대의 출생 정보는 그 궁합 행에만 저장하고 다른 표 · 로그 · 감사 기록에는 없다`(output: CapturedOutput) {
		val token = tokenWithProfile()

		val synastry = created(token, directBody(name = "지우", date = "1988-07-21", time = "06:10", placeId = HAEUNDAE))

		assertEquals(
			setOf(
				"id", "owner_id", "name", "relation", "score", "headline", "keywords", "owner_chart_hash", "created_at",
				"source", "status", "partner_birth_date", "partner_birth_time", "partner_place_id",
				"started_at", "active_at", "done_at", "seen_at", "notified_at",
			),
			jdbc.queryForList("select column_name from information_schema.columns where table_name = 'synastries'", String::class.java).toSet(),
		)
		// 그 궁합의 행에 상대의 출생 정보를 둔다(결정 053 의 2)
		val partner = jdbc.queryForMap("select partner_birth_date::text as d, partner_birth_time::text as t, partner_place_id as p, source, status from synastries")
		assertEquals(mapOf("d" to "1988-07-21", "t" to "06:10:00", "p" to HAEUNDAE, "source" to "DIRECT", "status" to "DONE"), partner)
		assertEquals(
			setOf("synastry_id", "status", "body", "started_at", "active_at", "updated_at"),
			jdbc.queryForList("select column_name from information_schema.columns where table_name = 'synastry_reports'", String::class.java).toSet(),
		)
		// 프로필(내 것)을 뺀 모든 테이블의 모든 행을 글로 떠서 본다
		val tables = jdbc.queryForList(
			"select table_name from information_schema.tables where table_schema = 'public' and table_name not in ('profiles', 'flyway_schema_history', 'daily_active')",
			String::class.java,
		)
		// 궁합 행의 상대 출생 정보 칸은 위에서 봤으니 빼고 본다
		val storedRaw = tables.joinToString("\n") { table ->
			jdbc.queryForList("select (to_jsonb(t) - 'partner_birth_date' - 'partner_birth_time' - 'partner_place_id')::text from $table t", String::class.java).joinToString("\n")
		}
		// 무작위 id 와 시각 표기에 우연히 같은 숫자가 나올 수 있어 그것들은 지우고 본다
		val noise = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{4}-\d{2}-\d{2}[T ][\d:.]+|[0-9a-f]{64}""")
		val stored = noise.replace(storedRaw, "")
		val logged = noise.replace(output.all, "")
		assertTrue(stored.contains("지우"), "부를 이름은 저장한다")
		val longitudes = synastry["overlay"]["theirs"].values().map { it["longitude"].asString() }
		assertEquals(10, longitudes.size)
		for (secret in listOf("1988", "07-21", "06:10", HAEUNDAE.toString()) + longitudes) {
			assertFalse(stored.contains(secret), "DB 에 상대의 '$secret' 이 있다")
			assertFalse(logged.contains(secret), "로그에 상대의 '$secret' 이 있다")
		}
		for (secret in listOf("지우", "가짜키워드", "가짜한줄요약", "상대의 ", MY_DATE, MY_TIME)) {
			assertFalse(logged.contains(secret), "로그에 '$secret' 이 있다")
		}
		assertTrue(output.all.contains("event=synastry_created"))
	}

	@Test
	fun `상대의 태어난 시각을 모르면 태어난 곳이 없어도 되고 시각이 있으면 곳이 있어야 한다`() {
		val token = tokenWithProfile()

		val withoutPlace = created(token, directBody(date = "1988-07-21", time = null, placeId = null))
		assertEquals(expectedScore("1988-07-21", Relation.LOVE), withoutPlace["score"].asInt())
		// 시각을 모르면 곳이 와도 읽지 않는다(목록에 없는 id 여도 된다)
		val ignoredPlace = created(token, directBody(date = "1988-07-21", time = null, placeId = 999))
		assertEquals(withoutPlace["score"].asInt(), ignoredPlace["score"].asInt())

		val missing = direct(token, directBody(date = "1988-07-21", time = "06:10", placeId = null))
		assertEquals(400, missing.statusCode())
		assertEquals("""{"error":"UNKNOWN_PLACE"}""", missing.body())
		assertEquals(400, direct(token, directBody(date = "1988-07-21", time = "06:10", placeId = 999)).statusCode())
		assertEquals(2, rows())
	}

	@Test
	fun `내 태어난 시각을 모르면 상승점을 보지 않는다`() {
		val token = tokenWithProfile("""{"date":"$MY_DATE","time":null,"placeId":$MAPO}""")

		val synastry = created(token, directBody(date = "1988-07-21"))

		assertEquals(expectedScore("1988-07-21", Relation.LOVE, myTime = null), synastry["score"].asInt())
		assertFalse(synastry["overlay"].has("mineAsc"))
		assertTrue(synastry["overlay"]["lines"].values().none { it["mine"].asString() == "ASC" })
		assertFalse(generator.summaries.single().userText.contains("상승점"))
	}

	@Test
	fun `사이(연애 · 친구)마다 서버가 계산한 점수를 저장한다`() {
		val token = tokenWithProfile()
		val relations = listOf(Relation.LOVE, Relation.FRIEND)
		// 사이에 따라 점수가 갈리는 상대를 찾는다
		val date = generateSequence(java.time.LocalDate.of(1985, 1, 1)) { it.plusDays(1) }.map { it.toString() }
			.first { day -> relations.map { expectedScore(day, it) }.toSet().size == 2 }

		for (relation in relations) {
			val synastry = created(token, directBody(relation = relation.name, date = date))
			assertEquals(expectedScore(date, relation), synastry["score"].asInt(), "$relation")
			assertEquals(relation.name, synastry["relation"].asString())
		}
		assertEquals(relations.map { expectedScore(date, it) }.toSet(), jdbc.queryForList("select score from synastries", Int::class.java).toSet())
	}

	@Test
	fun `60점 미만이면 다른 결의 사이를 붙이고 끌리는 점부터 쓰게 한다`() {
		val token = tokenWithProfile()
		val lowDate = partnerDate { it < 60 }
		val highDate = partnerDate { it >= 60 }

		val low = created(token, directBody(date = lowDate))
		val high = created(token, directBody(date = highDate))

		assertTrue(low["score"].asInt() in 40..59)
		assertEquals(LOW_LABEL, low["scoreLabel"].asString())
		assertTrue(generator.summaries[0].userText.contains(LOW_LABEL))
		assertTrue(generator.summaries[0].instructions.contains("끌리는 점"))
		assertTrue(high["score"].asInt() >= 60)
		assertFalse(high.has("scoreLabel"), "60점 이상이면 키가 없다")
		assertFalse(generator.summaries[1].userText.contains(LOW_LABEL))
		// 다시 읽어도, 목록·랭킹에서도 같다
		assertEquals(LOW_LABEL, json(token, "/synastry/${low["id"].asString()}")["scoreLabel"].asString())
		assertFalse(json(token, "/synastry/${high["id"].asString()}").has("scoreLabel"))
		val ranking = json(token, "/synastry/ranking?relation=LOVE").values().toList()
		assertEquals(listOf(null, LOW_LABEL), ranking.map { it["scoreLabel"]?.asString() })
	}

	@Test
	fun `요청이 틀리면 400 이고 생성하지도 저장하지도 않는다`() {
		val token = tokenWithProfile()
		val cases = listOf(
			"""{"name":"  ","relation":"LOVE","date":"1988-07-21","time":null}""" to "INVALID_NAME",
			"""{"relation":"LOVE","date":"1988-07-21","time":null}""" to "INVALID_NAME",
			"""{"name":"${"가".repeat(21)}","relation":"LOVE","date":"1988-07-21","time":null}""" to "INVALID_NAME",
			"""{"name":"지우","relation":"ENEMY","date":"1988-07-21","time":null}""" to "INVALID_RELATION",
			"""{"name":"지우","relation":"love","date":"1988-07-21","time":null}""" to "INVALID_RELATION",
			// 사이 입력은 연애 · 친구 둘이다(결정 053 의 1). 일 · 가족은 옛 기록을 읽을 때만 있다.
			"""{"name":"지우","relation":"WORK","date":"1988-07-21","time":null}""" to "INVALID_RELATION",
			"""{"name":"지우","relation":"FAMILY","date":"1988-07-21","time":null}""" to "INVALID_RELATION",
			"""{"name":"지우","date":"1988-07-21","time":null}""" to "INVALID_RELATION",
			"""{"name":"지우","relation":"LOVE","date":"1988-13-41","time":null}""" to "INVALID_DATE",
			"""{"name":"지우","relation":"LOVE","time":null}""" to "INVALID_DATE",
			"""{"name":"지우","relation":"LOVE","date":"2999-01-01","time":null}""" to "INVALID_DATE",
			"""{"name":"지우","relation":"LOVE","date":"1988-07-21","time":"25:00","placeId":$MAPO}""" to "INVALID_TIME",
			"""{"name":"지우","relation":"LOVE","date":"1988-07-21","time":"06:10"}""" to "UNKNOWN_PLACE",
			"""not json""" to "INVALID_REQUEST",
			"""{"name":"지우","relation":"LOVE","date":"1988-07-21","time":null,"placeId":"마포"}""" to "INVALID_REQUEST",
		)
		for ((body, code) in cases) {
			val response = direct(token, body)
			assertEquals(400, response.statusCode(), body)
			assertEquals("""{"error":"$code"}""", response.body(), body)
		}
		assertEquals(0, rows())
		assertTrue(generator.requests.isEmpty())
		// 이름은 앞뒤 공백을 떼고 20자까지 받는다
		assertEquals("가".repeat(20), created(token, directBody(name = " ${"가".repeat(20)} "))["name"].asString())
	}

	@Test
	fun `세션이 없으면 401 이고 저장한 출생 정보가 없으면 404 NO_PROFILE 이다`() {
		assertEquals(401, http(port, "POST", "/synastry/direct", directBody()).statusCode())
		assertEquals(401, http(port, "GET", "/synastry").statusCode())
		assertEquals(401, http(port, "GET", "/synastry/ranking?relation=LOVE").statusCode())
		assertEquals(401, http(port, "GET", "/synastry/${UUID.randomUUID()}").statusCode())

		val token = devToken(port)
		val response = direct(token, directBody())
		assertEquals(404, response.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", response.body())
		assertTrue(generator.requests.isEmpty())
		assertEquals("[]", http(port, "GET", "/synastry", token = token).body())
	}

	@Test
	fun `글을 만들지 못하면 502 이고 아무것도 저장하지 않는다`() {
		val token = tokenWithProfile()

		generator.respond = { _, _ -> throw GenerationException("HTTP_500") }
		val failed = direct(token, directBody())
		assertEquals(502, failed.statusCode())
		assertEquals(GENERATION_FAILED, failed.body())
		assertEquals(1, generator.requests.size, "호출 자체의 실패는 다시 부르지 않는다")
		assertEquals(0, rows())

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
			val response = direct(token, directBody())
			assertEquals(502, response.statusCode(), "경우 $index")
			assertEquals(GENERATION_FAILED, response.body())
			assertEquals(3, generator.requests.size, "경우 $index: 처음 한 번 + 다시 2번")
		}
		assertEquals(0, rows())
		assertEquals("[]", http(port, "GET", "/synastry", token = token).body())

		// 한 번 틀리고 다시 써서 맞으면 저장한다
		generator.reset()
		generator.respond = { _, call -> if (call == 1) summaryJson(headline = " ") else summaryJson(headline = "다시쓴한줄") }
		assertEquals("다시쓴한줄", created(token, directBody())["headline"].asString())
		assertEquals(2, generator.requests.size)
		assertEquals(1, rows())
	}

	@Test
	fun `목록은 새것부터 주고 다시 읽을 때는 겹친 휠이 없다`() {
		val token = tokenWithProfile()
		val first = created(token, directBody(name = "첫째", relation = "FRIEND"))
		val second = created(token, directBody(name = "둘째", relation = "LOVE", date = "1992-02-02"))

		val list = json(token, "/synastry").values().toList()

		assertEquals(listOf("둘째", "첫째"), list.map { it["name"].asString() })
		val expectedKeys = setOf("id", "name", "relation", "score", "headline", "keywords", "reportStatus", "createdAt")
		for (item in list) assertEquals(expectedKeys, keys(item) - "scoreLabel")
		val read = json(token, "/synastry/${first["id"].asString()}")
		assertEquals(expectedKeys, keys(read) - "scoreLabel")
		assertEquals((first as tools.jackson.databind.node.ObjectNode).deepCopy().also { it.remove("overlay") }, read)
		assertEquals(second["id"].asString(), list[0]["id"].asString())
		// 같은 상대를 다시 넣으면 새 궁합이 하나 더 생긴다(같은 점수)
		val again = created(token, directBody(name = "첫째", relation = "FRIEND"))
		assertEquals(first["score"].asInt(), again["score"].asInt())
		assertEquals(3, rows())
	}

	@Test
	fun `남의 궁합은 404 이고 남의 목록과 랭킹에 나오지 않는다`() {
		val mine = tokenWithProfile()
		val id = created(mine, directBody())["id"].asString()
		val theirs = otherToken()

		val response = http(port, "GET", "/synastry/$id", token = theirs)
		assertEquals(404, response.statusCode())
		assertEquals("""{"error":"NOT_FOUND"}""", response.body())
		assertEquals("[]", http(port, "GET", "/synastry", token = theirs).body())
		assertEquals("[]", http(port, "GET", "/synastry/ranking?relation=LOVE", token = theirs).body())
		// 없는 id, id 가 아닌 값도 같은 404
		assertEquals("""{"error":"NOT_FOUND"}""", http(port, "GET", "/synastry/${UUID.randomUUID()}", token = mine).body())
		val garbage = http(port, "GET", "/synastry/not-an-id", token = mine)
		assertEquals(404, garbage.statusCode())
		assertEquals("""{"error":"NOT_FOUND"}""", garbage.body())
		assertEquals(200, http(port, "GET", "/synastry/$id", token = mine).statusCode())
	}

	@Test
	fun `랭킹은 관계 유형별로 점수가 높은 순서이고 인원 제한이 없다`() {
		val token = tokenWithProfile()
		// 점수가 서로 다른 상대 12명
		val dates = generateSequence(java.time.LocalDate.of(1985, 1, 1)) { it.plusDays(17) }.map { it.toString() }
			.distinctBy { expectedScore(it, Relation.FRIEND) }.take(12).toList()
		dates.forEachIndexed { index, date -> created(token, directBody(name = "친구$index", relation = "FRIEND", date = date)) }
		created(token, directBody(name = "연인", relation = "LOVE"))

		val ranking = json(token, "/synastry/ranking?relation=FRIEND").values().toList()

		assertEquals(12, ranking.size)
		val scores = ranking.map { it["score"].asInt() }
		assertEquals(dates.map { expectedScore(it, Relation.FRIEND) }.sortedDescending(), scores)
		assertEquals(dates.indices.sortedByDescending { expectedScore(dates[it], Relation.FRIEND) }.map { "친구$it" }, ranking.map { it["name"].asString() })
		for (item in ranking) {
			assertEquals(setOf("id", "name", "score", "keywords"), keys(item) - "scoreLabel")
			assertEquals(3, item["keywords"].size())
		}
		assertEquals(listOf("연인"), json(token, "/synastry/ranking?relation=LOVE").values().map { it["name"].asString() })
		assertEquals("[]", http(port, "GET", "/synastry/ranking?relation=FAMILY", token = token).body())
		for (path in listOf("/synastry/ranking", "/synastry/ranking?relation=", "/synastry/ranking?relation=ENEMY")) {
			val response = http(port, "GET", path, token = token)
			assertEquals(400, response.statusCode(), path)
			assertEquals("""{"error":"INVALID_RELATION"}""", response.body())
		}
	}

	@Test
	fun `점수가 같으면 먼저 만든 궁합이 랭킹 위에 온다`() {
		val token = tokenWithProfile()
		created(token, directBody(name = "먼저"))
		created(token, directBody(name = "나중"))

		assertEquals(listOf("먼저", "나중"), json(token, "/synastry/ranking?relation=LOVE").values().map { it["name"].asString() })
	}

	@Test
	fun `계정을 지우면 그 사람의 궁합도 지워진다`() {
		val token = tokenWithProfile()
		created(token, directBody())
		assertEquals(1, rows())

		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		assertEquals(0, rows())
		assertNull(jdbc.queryForList("select id from synastries").firstOrNull())
	}

	@Test
	fun `상대의 시각을 모르면 날짜만 저장하고 궁합을 지우면 저장한 출생 정보도 함께 지워진다`() {
		val token = tokenWithProfile()
		val id = created(token, directBody(date = "1988-07-21", time = null, placeId = 999))["id"].asString()

		assertEquals(
			mapOf("d" to "1988-07-21", "t" to null, "p" to null),
			jdbc.queryForMap("select partner_birth_date::text as d, partner_birth_time::text as t, partner_place_id as p from synastries"),
		)

		assertEquals(204, http(port, "DELETE", "/synastry/$id", token = token).statusCode())

		assertEquals(0, jdbc.queryForObject("select count(*) from synastries where partner_birth_date is not null", Int::class.java))
	}
}

/** 직접 입력의 요청 수 제한. 한 번에 모델 호출이 하나씩 나가므로 주소별로 막는다. */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=2"],
)
class SynastryRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `직접 입력이 분당 한도를 넘으면 429 이고 읽기는 막지 않는다`() {
		val token = devToken(port)
		http(port, "PUT", "/profile", ME, token)

		assertEquals(200, http(port, "POST", "/synastry/direct", directBody(), token).statusCode())
		assertEquals(200, http(port, "POST", "/synastry/direct", directBody(), token).statusCode())
		val limited = http(port, "POST", "/synastry/direct", directBody(), token)

		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
		repeat(5) { assertEquals(200, http(port, "GET", "/synastry", token = token).statusCode()) }
	}
}
