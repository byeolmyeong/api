package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.Places
import com.byeolmyeong.api.birth.toUtc
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.synastryAspects
import com.byeolmyeong.api.chart.synastryWeight
import com.byeolmyeong.api.devToken
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
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOW_LABEL = "다른 결의 사이"
private const val HAEUNDAE = 8462808

/** 궁합 만들기(결정 053): 입력 검증 · 점수 · 저장 · 목록 · 랭킹. 뒤에서 글을 쓰는 상태들은 SynastryGenerationIntegrationTest. */
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

	private fun post(token: String, body: String) = http(port, "POST", "/synastry", body, token)

	/** 만들고 완성될 때까지 기다린 `GET /synastry/{id}` 의 응답. */
	private fun created(token: String, body: String): JsonNode = createSynastry(port, token, body)

	private fun json(token: String, path: String): JsonNode {
		val response = http(port, "GET", path, token = token)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	private fun keys(node: JsonNode): Set<String> = node.propertyNames().toSet()

	@Test
	fun `만들면 202 로 바로 답하고 완성되면 서버가 계산한 점수 · 키워드 · 한 줄 · 이렇게 본 이유 · 네 영역을 준다`() {
		val token = tokenWithProfile()

		val response = post(token, directBody(name = "지우", relation = "LOVE", date = "1988-07-21", time = "06:10", placeId = MAPO))

		assertEquals(202, response.statusCode(), response.body())
		val started = mapper.readTree(response.body())
		assertEquals(setOf("id", "status"), keys(started))
		val id = started["id"].asString()
		UUID.fromString(id)
		val synastry = awaitSynastry(port, token, id)

		assertEquals("지우", synastry["name"].asString())
		assertEquals("LOVE", synastry["relation"].asString())
		assertEquals("DIRECT", synastry["source"].asString())
		assertEquals(expectedScore("1988-07-21", Relation.LOVE, partnerTime = "06:10"), synastry["score"].asInt())
		assertEquals(listOf("가짜키워드하나", "가짜키워드둘", "가짜키워드셋"), synastry["keywords"].values().map { it.asString() })
		assertEquals("가짜한줄요약", synastry["headline"].asString())
		assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(synastry["createdAt"].asString()), synastry["createdAt"].toString())
		assertEquals(REPORT_AREAS, synastry["report"]["sections"].values().map { it["area"].asString() })
		assertEquals(1, generator.summaries.size)
		assertEquals(4, generator.areas.size)
		assertEquals(1, rows())

		// 이렇게 본 이유: 점수에 쓴 각도 전부, 센 것부터(점수 × 가중치의 크기). 결은 서버의 점수 부호다.
		val mine = chartOf(MY_DATE, MY_TIME)
		val theirs = chartOf("1988-07-21", "06:10")
		val expected = synastryAspects(mine, theirs).sortedByDescending { abs(it.points) * synastryWeight(Relation.LOVE, it) }
		assertEquals(
			expected.map { listOf(it.mine.name, it.theirs.name, it.type.name, if (it.points >= 0) "EASY" else "TENSE") },
			synastry["reasons"].values().map { listOf(it["mine"].asString(), it["theirs"].asString(), it["type"].asString(), it["tone"].asString()) },
		)
		assertTrue(synastry["reasons"].values().all { keys(it) == setOf("mine", "theirs", "type", "tone") }, "황경 · 별자리는 싣지 않는다")
		assertFalse(synastry.has("overlay"), "겹친 휠은 없다(결정 053 의 2)")
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
		// 요약 · 네 영역 모두: 상대의 이름 · 출생 정보, 내 출생 정보가 지시문에도 재료에도 없다(결정 053 의 10)
		assertEquals(5, generator.requests.size)
		for (sent in generator.requests) {
			for (secret in listOf("별난이름", "1988", "07-21", "06:10", "1990", "14:30", "마포", MAPO.toString())) {
				assertFalse(sent.userText.contains(secret), "${sent.purpose} 재료에 '$secret' 이 있다")
				assertFalse(sent.instructions.contains(secret), "${sent.purpose} 지시문에 '$secret' 이 있다")
			}
			// 도수(소수)가 없다: 숫자는 점수와 기준 점수뿐이다
			assertFalse(Regex("""\d+\.\d""").containsMatchIn(sent.userText), sent.userText)
		}
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
	fun `상대의 출생 정보는 그 궁합 행에만 저장하고 응답 · 다른 표 · 로그 · 감사 기록에는 없다`(output: CapturedOutput) {
		val token = tokenWithProfile()

		val posted = post(token, directBody(name = "지우", date = "1988-07-21", time = "06:10", placeId = HAEUNDAE))
		val id = mapper.readTree(posted.body())["id"].asString()
		awaitSynastry(port, token, id)
		val responses = listOf(posted.body(), http(port, "GET", "/synastry/$id", token = token).body(), http(port, "GET", "/synastry", token = token).body())

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
		// 프로필(내 것)을 뺀 모든 테이블의 모든 행을 글로 떠서 본다(감사 기록 포함). 궁합 행의 상대 출생 정보 칸은 위에서 봤으니 뺀다.
		val tables = jdbc.queryForList(
			"select table_name from information_schema.tables where table_schema = 'public' and table_name not in ('profiles', 'flyway_schema_history', 'daily_active')",
			String::class.java,
		)
		val storedRaw = tables.joinToString("\n") { table ->
			jdbc.queryForList("select (to_jsonb(t) - 'partner_birth_date' - 'partner_birth_time' - 'partner_place_id')::text from $table t", String::class.java).joinToString("\n")
		}
		// 무작위 id 와 시각 표기에 우연히 같은 숫자가 나올 수 있어 그것들은 지우고 본다
		val noise = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d{4}-\d{2}-\d{2}[T ][\d:.]+|[0-9a-f]{64}""")
		val stored = noise.replace(storedRaw, "")
		val logged = noise.replace(output.all, "")
		val answered = noise.replace(responses.joinToString("\n"), "")
		assertTrue(stored.contains("지우"), "부를 이름은 저장한다")
		val place = Places().find(HAEUNDAE)!!
		val theirs = computeChart(toUtc(LocalDate.parse("1988-07-21"), LocalTime.parse("06:10")), place.lat, place.lon, timeKnown = true)
		val longitudes = theirs.positions.map { (floor(it.longitude * 100.0) / 100.0).toString() }
		assertEquals(10, longitudes.size)
		for (secret in listOf("1988", "07-21", "06:10", HAEUNDAE.toString()) + longitudes) {
			assertFalse(stored.contains(secret), "DB 에 상대의 '$secret' 이 있다")
			assertFalse(logged.contains(secret), "로그에 상대의 '$secret' 이 있다")
			assertFalse(answered.contains(secret), "응답에 상대의 '$secret' 이 있다")
		}
		for (secret in listOf("지우", "가짜키워드", "가짜한줄요약", "가짜본문", "상대의 ", MY_DATE, MY_TIME)) {
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

		val missing = post(token, directBody(date = "1988-07-21", time = "06:10", placeId = null))
		assertEquals(400, missing.statusCode())
		assertEquals("""{"error":"UNKNOWN_PLACE"}""", missing.body())
		assertEquals(400, post(token, directBody(date = "1988-07-21", time = "06:10", placeId = 999)).statusCode())
		assertEquals(2, rows())
	}

	@Test
	fun `내 태어난 시각을 모르면 상승점을 보지 않는다`() {
		val token = tokenWithProfile("""{"date":"$MY_DATE","time":null,"placeId":$MAPO}""")

		val synastry = created(token, directBody(date = "1988-07-21"))

		assertEquals(expectedScore("1988-07-21", Relation.LOVE, myTime = null), synastry["score"].asInt())
		assertTrue(synastry["reasons"].values().none { it["mine"].asString() == "ASC" })
		assertFalse(generator.summaries.single().userText.contains("상승점"))
	}

	@Test
	fun `사이(연애 · 친구)마다 서버가 계산한 점수를 저장한다`() {
		val token = tokenWithProfile()
		val relations = listOf(Relation.LOVE, Relation.FRIEND)
		// 사이에 따라 점수가 갈리는 상대를 찾는다
		val date = generateSequence(LocalDate.of(1985, 1, 1)) { it.plusDays(1) }.map { it.toString() }
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
		// 목록 · 랭킹에서도 같다
		assertEquals(listOf(null, LOW_LABEL), json(token, "/synastry").values().map { it["scoreLabel"]?.asString() })
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
			val response = post(token, body)
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
		assertEquals(401, http(port, "POST", "/synastry", directBody()).statusCode())
		assertEquals(401, http(port, "GET", "/synastry").statusCode())
		assertEquals(401, http(port, "GET", "/synastry/ranking?relation=LOVE").statusCode())
		assertEquals(401, http(port, "GET", "/synastry/${UUID.randomUUID()}").statusCode())
		assertEquals(401, http(port, "POST", "/synastry/${UUID.randomUUID()}/retry").statusCode())

		val token = devToken(port)
		val response = post(token, directBody())
		assertEquals(404, response.statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", response.body())
		assertTrue(generator.requests.isEmpty())
		assertEquals("[]", http(port, "GET", "/synastry", token = token).body())
	}

	@Test
	fun `옛 경로(직접 입력 · 따로 받는 리포트)는 없다`() {
		val token = tokenWithProfile()
		val id = created(token, directBody())["id"].asString()

		for ((method, path) in listOf("POST" to "/synastry/direct", "POST" to "/synastry/$id/report", "GET" to "/synastry/$id/report")) {
			val response = http(port, method, path, if (method == "POST") directBody() else null, token)
			assertTrue(response.statusCode() in setOf(404, 405), "$method $path: ${response.statusCode()}")
		}
		assertEquals(1, rows())
	}

	@Test
	fun `목록은 새것부터 주고 이유와 네 영역은 하나를 읽을 때만 싣는다`() {
		val token = tokenWithProfile()
		val first = created(token, directBody(name = "첫째", relation = "FRIEND"))
		val second = created(token, directBody(name = "둘째", relation = "LOVE", date = "1992-02-02"))

		val list = json(token, "/synastry").values().toList()

		assertEquals(listOf("둘째", "첫째"), list.map { it["name"].asString() })
		val listKeys = setOf("id", "name", "relation", "status", "source", "score", "headline", "keywords", "createdAt")
		for (item in list) assertEquals(listKeys, keys(item) - "scoreLabel")
		assertEquals(listKeys + setOf("reasons", "report"), keys(first) - "scoreLabel")
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

		for (response in listOf(http(port, "GET", "/synastry/$id", token = theirs), http(port, "POST", "/synastry/$id/retry", token = theirs))) {
			assertEquals(404, response.statusCode())
			assertEquals("""{"error":"NOT_FOUND"}""", response.body())
		}
		assertEquals("[]", http(port, "GET", "/synastry", token = theirs).body())
		assertEquals("[]", http(port, "GET", "/synastry/ranking?relation=LOVE", token = theirs).body())
		// 없는 id, id 가 아닌 값도 같은 404
		assertEquals("""{"error":"NOT_FOUND"}""", http(port, "GET", "/synastry/${UUID.randomUUID()}", token = mine).body())
		val garbage = http(port, "GET", "/synastry/not-an-id", token = mine)
		assertEquals(404, garbage.statusCode())
		assertEquals("""{"error":"NOT_FOUND"}""", garbage.body())
		assertEquals(404, http(port, "POST", "/synastry/not-an-id/retry", token = mine).statusCode())
		assertEquals(200, http(port, "GET", "/synastry/$id", token = mine).statusCode())
	}

	@Test
	fun `랭킹은 관계 유형별로 완성된 궁합을 점수가 높은 순서로 주고 인원 제한이 없다`() {
		val token = tokenWithProfile()
		// 점수가 서로 다른 상대 12명
		val dates = generateSequence(LocalDate.of(1985, 1, 1)) { it.plusDays(17) }.map { it.toString() }
			.distinctBy { expectedScore(it, Relation.FRIEND) }.take(12).toList()
		dates.forEachIndexed { index, date -> created(token, directBody(name = "친구$index", relation = "FRIEND", date = date)) }
		created(token, directBody(name = "연인", relation = "LOVE"))
		// 쓰는 중인 궁합은 랭킹에 없다
		val gate = java.util.concurrent.CountDownLatch(1).also { generator.gate = it }
		assertEquals(202, post(token, directBody(name = "쓰는중", relation = "FRIEND")).statusCode())

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
		// 옛 기록의 사이(일 · 가족)도 읽는다
		assertEquals("[]", http(port, "GET", "/synastry/ranking?relation=FAMILY", token = token).body())
		for (path in listOf("/synastry/ranking", "/synastry/ranking?relation=", "/synastry/ranking?relation=ENEMY")) {
			val response = http(port, "GET", path, token = token)
			assertEquals(400, response.statusCode(), path)
			assertEquals("""{"error":"INVALID_RELATION"}""", response.body())
		}
		gate.countDown()
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

/** 궁합 만들기의 주소별 요청 수 제한. 같은 경로의 목록(GET)은 세지 않는다. */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=2"],
)
class SynastryRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `만들기가 분당 한도를 넘으면 429 이고 읽기는 막지 않는다`() {
		val token = devToken(port)
		http(port, "PUT", "/profile", ME, token)

		assertEquals(202, http(port, "POST", "/synastry", directBody(), token).statusCode())
		assertEquals(202, http(port, "POST", "/synastry", directBody(), token).statusCode())
		val limited = http(port, "POST", "/synastry", directBody(), token)

		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
		repeat(5) { assertEquals(200, http(port, "GET", "/synastry", token = token).statusCode()) }
	}
}

/**
 * 하루 안전 한도(결정 053 의 6): 그날(한국 날짜) 만든 궁합이 50개 이상이면 더 만들지 않는다(429). 다른 테스트는 한도를 크게 둔다(테스트 설정).
 * 그날의 경계는 DB 의 한국 시각 자정이다.
 */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000", "byeolmyeong.synastry.daily-limit=50"],
)
class SynastryDailyLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	@BeforeEach
	@AfterEach
	fun clean() {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline) { "뒤에서 도는 생성이 끝나지 않는다" }
			Thread.sleep(10)
		}
		jdbc.update("delete from accounts")
		generator.reset()
	}

	/** 소유자의 궁합 행을 [count] 개 넣는다. [createdAt] 은 SQL 식이다. */
	private fun seed(owner: UUID, count: Int, createdAt: String) {
		repeat(count) {
			jdbc.update(
				"insert into synastries (id, owner_id, name, relation, score, headline, keywords, owner_chart_hash, created_at) values (?, ?, '이전', 'LOVE', 70, '한줄', '[\"a\",\"b\",\"c\"]'::jsonb, 'h', $createdAt)",
				UUID.randomUUID(), owner,
			)
		}
	}

	@Test
	fun `그날 만든 궁합이 50개가 되면 51번째는 429 이고 어제 만든 것과 남의 것은 세지 않는다`() {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
		val me = jdbc.queryForObject("select id from accounts", UUID::class.java)!!
		val other = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", other, "someone-$other")
		val todayStart = "(date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul')"
		seed(me, 49, "now()")
		seed(me, 5, "$todayStart - interval '1 minute'")
		seed(other, 60, "now()")

		val fiftieth = http(port, "POST", "/synastry", directBody(), token)
		assertEquals(202, fiftieth.statusCode(), fiftieth.body())
		awaitSynastry(port, token, mapper.readTree(fiftieth.body())["id"].asString())
		val calls = generator.requests.size

		val limited = http(port, "POST", "/synastry", directBody(), token)

		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
		assertEquals(55, jdbc.queryForObject("select count(*) from synastries where owner_id = ?", Int::class.java, me), "넣지 않는다")
		assertEquals(calls, generator.requests.size, "모델을 부르지 않는다")
		// 읽기와 남의 계정은 막지 않는다
		assertEquals(200, http(port, "GET", "/synastry", token = token).statusCode())
	}
}
