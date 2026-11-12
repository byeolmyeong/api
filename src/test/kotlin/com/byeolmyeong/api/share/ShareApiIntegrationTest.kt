package com.byeolmyeong.api.share

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.MilestoneKind
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import com.byeolmyeong.api.report.Report
import com.byeolmyeong.api.report.ReportArea
import com.byeolmyeong.api.report.ReportParagraph
import com.byeolmyeong.api.report.ReportSection
import com.byeolmyeong.api.report.ReportTiming
import com.byeolmyeong.api.report.TimingInterval
import com.byeolmyeong.api.report.TimingMilestone
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
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val BIRTH_DATE = "1990-03-15"
private const val BIRTH_TIME = "14:30"
private const val ME = """{"date":"$BIRTH_DATE","time":"$BIRTH_TIME","placeId":$MAPO}"""
private const val APP = "http://localhost:8081"
private const val NOT_FOUND = """{"error":"NOT_FOUND"}"""
private const val REPORT_BODY = "리포트본문비밀"
private const val READING_BODY = "해석본문비밀"
private const val BASIS = "물고기"

private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

/** 공유: 만들기(`POST /shares`) · 공개 조회(`GET /shared/{shareId}`) · 지우기. 글은 표에 직접 넣는다(모델을 부르지 않는다). */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.cors.allowed-origins=$APP", "byeolmyeong.rate-limit.shared-per-minute=100000"],
)
class ShareApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var mapper: JsonMapper

	@Autowired
	private lateinit var profiles: BirthProfileService

	private lateinit var token: String
	private lateinit var me: UUID

	private val today: LocalDate = LocalDate.now(KOREA_ZONE)

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from accounts")
		token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
		me = jdbc.queryForObject("select id from accounts", UUID::class.java)!!
	}

	// ── 재료를 표에 넣는다 ──

	private fun chartHash(): String = profiles.chartInput(me)!!.hash

	private fun insertReading(owner: UUID = me, hash: String = chartHash()) {
		val body = mapOf(
			"headline" to "조용히 깊어지는 사람",
			"sections" to listOf("단단한 중심", "섬세한 마음", "부드러운 첫인상", "몰입하는 힘", "내려놓는 연습").map {
				mapOf("title" to it, "body" to READING_BODY, "basis" to listOf(BASIS, "태양"))
			},
			"strengths" to listOf("강점비밀1", "강점비밀2", "강점비밀3"),
			"cautions" to listOf("보완비밀1", "보완비밀2", "보완비밀3"),
		)
		jdbc.update(
			"insert into readings (id, owner_id, kind, chart_hash, body, share_id) values (?, ?, 'SUMMARY', ?, ?::jsonb, ?)",
			UUID.randomUUID(), owner, hash, mapper.writeValueAsString(body), "old-${UUID.randomUUID()}",
		)
	}

	private fun insertSynastry(name: String, score: Int, relation: String = "FRIEND", owner: UUID = me, headline: String = "서로의 속도를 맞춰 가는 사이"): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"""
			insert into synastries (id, owner_id, name, relation, score, headline, keywords, owner_chart_hash)
			values (?, ?, ?, ?, ?, ?, '["키워드비밀1","키워드비밀2","키워드비밀3"]'::jsonb, 'hash')
			""".trimIndent(),
			id, owner, name, relation, score, headline,
		)
		return id
	}

	/** 완성된 리포트. 다음 마디는 내후년 4월 15일(봄)이다. */
	private fun insertReport(nextDate: LocalDate = LocalDate.of(today.year + 2, 4, 15), allPast: Boolean = false) {
		fun milestone(kind: MilestoneKind, date: LocalDate, title: String) =
			TimingMilestone(kind, date, date.year, 30, date.minusMonths(2), date.plusMonths(3), ChangeSize.BIG, title, REPORT_BODY)
		val dates = if (allPast) (4 downTo 1).map { today.minusYears(it.toLong()) } else listOf(today.minusYears(7), nextDate, nextDate.plusYears(14), nextDate.plusYears(29))
		val report = Report(
			sections = ReportArea.entries.map { area ->
				ReportSection(area, "영역제목비밀", (1..4).map { ReportParagraph("문단제목비밀", REPORT_BODY, listOf(BASIS)) })
			},
			timing = ReportTiming(
				timeKnown = true,
				milestones = MilestoneKind.entries.zip(dates).map { (kind, date) -> milestone(kind, date, "주제-${kind.name}") },
				intervals = listOf(
					TimingInterval("i1", IntervalKind.OPPORTUNITY, today.plusMonths(1), today.plusMonths(4), LifeArea.PEOPLE, null, "구간제목비밀", REPORT_BODY),
				),
			),
		)
		jdbc.update(
			"insert into reports (id, owner_id, chart_hash, status, body) values (?, ?, ?, 'DONE', ?::jsonb)",
			UUID.randomUUID(), me, chartHash(), mapper.writeValueAsString(report),
		)
	}

	/** 다른 계정과 그 세션. 개발용 로그인은 늘 같은 계정이라 직접 만든다. */
	private fun otherAccount(): Pair<UUID, String> {
		val other = UUID.randomUUID()
		val otherToken = "other-token-$other"
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", other, "someone-$other")
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(otherToken), other)
		return other to otherToken
	}

	// ── 부르기 ──

	private fun post(body: String, withToken: String? = token) = http(port, "POST", "/shares", body, withToken)

	private fun share(body: String): JsonNode {
		val response = post(body)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	/** 세션 없이 공개 조회. */
	private fun shared(shareId: String) = http(port, "GET", "/shared/$shareId")

	private fun sharedCard(shareId: String): JsonNode {
		val response = shared(shareId)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	private fun keys(node: JsonNode): Set<String> = node.propertyNames().toSet()

	private fun shareRows(): Int = jdbc.queryForObject("select count(*) from shares", Int::class.java)!!

	private fun scoreBody(id: UUID, myName: String = "민서") = """{"kind":"SCORE","synastryId":"$id","myName":"$myName"}"""

	// ── 카드 4종 ──

	@Test
	fun `요약 해석 카드는 한 줄 총평과 앞의 세 문단 제목만 싣고 로그인 없이 열린다`() {
		insertReading()

		val created = share("""{"kind":"READING"}""")

		assertEquals(setOf("shareId", "card"), keys(created))
		val card = sharedCard(created["shareId"].asString())
		assertEquals(created["card"], card)
		assertEquals(setOf("kind", "headline", "keywords"), keys(card))
		assertEquals("READING", card["kind"].asString())
		assertEquals("조용히 깊어지는 사람", card["headline"].asString())
		assertEquals(listOf("단단한 중심", "섬세한 마음", "부드러운 첫인상"), card["keywords"].values().map { it.asString() })
	}

	@Test
	fun `궁합 점수 카드는 두 사람의 이름과 점수와 한 줄만 싣는다`() {
		val id = insertSynastry("지우", 87, relation = "LOVE")

		val created = share("""{"kind":"SCORE","synastryId":"$id","myName":"  민서 "}""")

		val card = sharedCard(created["shareId"].asString())
		assertEquals(created["card"], card)
		assertEquals(setOf("kind", "names", "score", "headline"), keys(card))
		assertEquals("SCORE", card["kind"].asString())
		assertEquals(listOf("민서", "지우"), card["names"].values().map { it.asString() })
		assertEquals(87, card["score"].asInt())
		assertEquals("서로의 속도를 맞춰 가는 사이", card["headline"].asString())
	}

	@Test
	fun `60점 미만인 궁합 점수 카드에는 서버가 정한 표시가 붙는다`() {
		val id = insertSynastry("지우", 52)

		val card = share(scoreBody(id))["card"]

		assertEquals(setOf("kind", "names", "score", "scoreLabel", "headline"), keys(card))
		assertEquals("다른 결의 사이", card["scoreLabel"].asString())
	}

	@Test
	fun `랭킹 카드는 그 관계 유형의 점수순 세 명까지만 싣고 이름은 기본으로 이니셜이다`() {
		insertSynastry("지영", 71)
		insertSynastry("김하늘", 95)
		insertSynastry("Mike", 88)
		insertSynastry("꼴찌", 45)
		insertSynastry("민준", 80)
		insertSynastry("연인", 99, relation = "LOVE")

		val created = share("""{"kind":"RANKING","relation":"FRIEND"}""")

		val card = sharedCard(created["shareId"].asString())
		assertEquals(created["card"], card)
		assertEquals(setOf("kind", "relation", "entries"), keys(card))
		assertEquals("RANKING", card["kind"].asString())
		assertEquals("FRIEND", card["relation"].asString())
		assertEquals(listOf("ㄱㅎㄴ" to 95, "M" to 88, "ㅁㅈ" to 80), card["entries"].values().map { it["name"].asString() to it["score"].asInt() })
		card["entries"].values().forEach { assertEquals(setOf("name", "score"), keys(it)) }
		// 4등부터와 원래 이름은 저장된 카드에도 없다
		val stored = jdbc.queryForObject("select card::text from shares", String::class.java)!!
		for (hidden in listOf("김하늘", "Mike", "민준", "지영", "꼴찌", "연인")) assertFalse(stored.contains(hidden), stored)
	}

	@Test
	fun `랭킹 카드는 공유하는 사람이 켜면 적은 이름 그대로 싣고 세 명보다 적으면 있는 만큼만 싣는다`() {
		insertSynastry("김하늘", 95)
		insertSynastry("Mike", 88)

		val card = share("""{"kind":"RANKING","relation":"FRIEND","showNames":true}""")["card"]

		assertEquals(listOf("김하늘" to 95, "Mike" to 88), card["entries"].values().map { it["name"].asString() to it["score"].asInt() })
		// 끈 것과 켠 것은 다른 공유다
		val masked = share("""{"kind":"RANKING","relation":"FRIEND","showNames":false}""")
		assertEquals(listOf("ㄱㅎㄴ", "M"), masked["card"]["entries"].values().map { it["name"].asString() })
		assertEquals(2, shareRows())
	}

	@Test
	fun `다음 변곡점 카드는 다음 마디의 해와 계절과 주제 한 줄만 싣는다`() {
		insertReport()

		val created = share("""{"kind":"NEXT_TURN"}""")

		val card = sharedCard(created["shareId"].asString())
		assertEquals(created["card"], card)
		assertEquals(setOf("kind", "year", "season", "theme"), keys(card))
		assertEquals("NEXT_TURN", card["kind"].asString())
		assertEquals(today.year + 2, card["year"].asInt())
		assertEquals("SPRING", card["season"].asString())
		assertEquals("주제-SATURN_RETURN", card["theme"].asString())
	}

	// ── 공개 응답에 나오면 안 되는 것 ──

	@Test
	fun `공개 응답에는 근거와 소유자와 출생 정보와 본문이 없다`(output: CapturedOutput) {
		insertReading()
		insertReport()
		val synastry = insertSynastry("지우", 87)
		val ids = listOf(
			"""{"kind":"READING"}""", scoreBody(synastry), """{"kind":"RANKING","relation":"FRIEND","showNames":true}""", """{"kind":"NEXT_TURN"}""",
		).map { share(it)["shareId"].asString() }

		val forbiddenKey = Regex("basis|owner|birth|date|time|place|hash|longitude|sections|paragraphs|body|strengths|cautions|synastryId|id$", RegexOption.IGNORE_CASE)
		fun walk(node: JsonNode, path: String) {
			if (node.isObject) node.propertyNames().forEach { key ->
				assertFalse(forbiddenKey.containsMatchIn(key), "키 $path.$key")
				walk(node[key], "$path.$key")
			}
			if (node.isArray) node.values().forEach { walk(it, "$path[]") }
		}
		for (id in ids) {
			val body = shared(id).body()
			walk(mapper.readTree(body), "")
			val secrets = listOf(
				BIRTH_DATE, "1990", "03-15", BIRTH_TIME, MAPO.toString(), me.toString(), synastry.toString(), chartHash(),
				BASIS, "태양", REPORT_BODY, READING_BODY, "강점비밀", "보완비밀", "키워드비밀", "영역제목비밀", "문단제목비밀", "구간제목비밀",
			)
			for (secret in secrets) assertFalse(body.contains(secret), "응답에 '$secret' 이 있다: $body")
			// 날짜 모양(YYYY-MM-DD)의 값이 없다
			assertFalse(Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(body), body)
		}
		// 로그에 이름·소유자·공유 식별자를 남기지 않는다
		for (secret in listOf("민서", "지우", me.toString()) + ids) assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
	}

	// ── 식별자 ──

	@Test
	fun `없는 공유 식별자는 404 다`() {
		assertEquals(404, shared("AAAAAAAAAAAAAAAAAAAAAA").statusCode())
		assertEquals(NOT_FOUND, shared("AAAAAAAAAAAAAAAAAAAAAA").body())
		// 예전 요약 해석의 공유 식별자(readings.share_id)는 더 쓰지 않는다
		insertReading()
		val old = jdbc.queryForObject("select share_id from readings", String::class.java)!!
		assertEquals(404, shared(old).statusCode())
	}

	@Test
	fun `공유 식별자는 무작위 128비트이고 공유마다 다르다`() {
		val synastry = insertSynastry("지우", 87)

		val ids = (1..40).map { share(scoreBody(synastry, myName = "이름$it"))["shareId"].asString() }

		assertEquals(40, ids.toSet().size)
		for (id in ids) {
			assertTrue(Regex("[A-Za-z0-9_-]{22}").matches(id), id)
			assertEquals(16, Base64.getUrlDecoder().decode(id).size)
		}
		// 무작위인지: 식별자들의 비트가 한쪽으로 쏠리지 않는다(40 × 128 = 5120비트 가운데 1 이 40~60%)
		val ones = ids.sumOf { id -> Base64.getUrlDecoder().decode(id).sumOf { Integer.bitCount(it.toInt() and 0xff) } }
		assertTrue(ones in 2048..3072, "1 인 비트 $ones / 5120")
	}

	@Test
	fun `같은 내용의 카드를 다시 만들면 있던 공유를 준다`() {
		val synastry = insertSynastry("지우", 87)

		val first = share(scoreBody(synastry))
		val second = share(scoreBody(synastry))

		assertEquals(first, second)
		assertEquals(1, shareRows())
	}

	// ── 스냅샷 ──

	@Test
	fun `공유한 뒤 원본이 바뀌거나 없어져도 카드는 공유한 때의 내용이다`() {
		val synastry = insertSynastry("지우", 87)
		val shareId = share(scoreBody(synastry))["shareId"].asString()
		val before = shared(shareId).body()

		jdbc.update("delete from synastries where id = ?", synastry)

		assertEquals(before, shared(shareId).body())
	}

	// ── 소유자 ──

	@Test
	fun `세션이 없으면 공유를 만들 수 없다`() {
		insertReading()
		assertEquals(401, post("""{"kind":"READING"}""", withToken = null).statusCode())
		assertEquals(0, shareRows())
	}

	@Test
	fun `남의 궁합과 남의 요약 해석으로는 공유를 만들 수 없다`() {
		insertReading()
		insertReport()
		val mine = insertSynastry("지우", 87)
		val (other, otherToken) = otherAccount()

		// 남의 궁합 id
		assertEquals(404, post(scoreBody(mine), otherToken).statusCode())
		assertEquals(NOT_FOUND, post(scoreBody(mine), otherToken).body())
		// 자기 글이 없는 사람은 남의 글이 있어도 404
		assertEquals(404, post("""{"kind":"READING"}""", otherToken).statusCode())
		assertEquals(404, post("""{"kind":"NEXT_TURN"}""", otherToken).statusCode())
		assertEquals(404, post("""{"kind":"RANKING","relation":"FRIEND"}""", otherToken).statusCode())
		assertEquals(0, shareRows())

		// 그 사람의 랭킹에는 그 사람의 궁합만 들어간다
		insertSynastry("남의친구", 70, owner = other)
		val response = post("""{"kind":"RANKING","relation":"FRIEND","showNames":true}""", otherToken)
		assertEquals(200, response.statusCode(), response.body())
		assertEquals(listOf("남의친구"), mapper.readTree(response.body())["card"]["entries"].values().map { it["name"].asString() })
	}

	@Test
	fun `공유할 글이 없으면 404 다`() {
		assertEquals(404, post("""{"kind":"READING"}""").statusCode())
		assertEquals(404, post("""{"kind":"NEXT_TURN"}""").statusCode())
		assertEquals(404, post("""{"kind":"RANKING","relation":"WORK"}""").statusCode())
		assertEquals(404, post(scoreBody(UUID.randomUUID())).statusCode())
		assertEquals(404, post("""{"kind":"SCORE","synastryId":"uuid-가-아님","myName":"민서"}""").statusCode())
		// 다른 차트의 요약 해석(출생 정보를 고치기 전의 글)은 지금 것이 아니다
		insertReading(hash = "old-chart")
		assertEquals(404, post("""{"kind":"READING"}""").statusCode())
		assertEquals(0, shareRows())
	}

	@Test
	fun `다음 마디가 없으면 다음 변곡점 카드는 404 다`() {
		insertReport(allPast = true)
		assertEquals(NOT_FOUND, post("""{"kind":"NEXT_TURN"}""").body())
	}

	@Test
	fun `틀린 요청은 400 이다`() {
		val synastry = insertSynastry("지우", 87)
		fun error(body: String): Pair<Int, String> = post(body).let { it.statusCode() to it.body() }

		assertEquals(400 to """{"error":"INVALID_REQUEST"}""", error("not json"))
		assertEquals(400 to """{"error":"INVALID_KIND"}""", error("{}"))
		assertEquals(400 to """{"error":"INVALID_KIND"}""", error("""{"kind":"REPORT"}"""))
		assertEquals(400 to """{"error":"INVALID_REQUEST"}""", error("""{"kind":"SCORE","myName":"민서"}"""))
		assertEquals(400 to """{"error":"INVALID_NAME"}""", error("""{"kind":"SCORE","synastryId":"$synastry"}"""))
		assertEquals(400 to """{"error":"INVALID_NAME"}""", error(scoreBody(synastry, myName = "   ")))
		assertEquals(400 to """{"error":"INVALID_NAME"}""", error(scoreBody(synastry, myName = "가".repeat(21))))
		assertEquals(400 to """{"error":"INVALID_RELATION"}""", error("""{"kind":"RANKING"}"""))
		assertEquals(400 to """{"error":"INVALID_RELATION"}""", error("""{"kind":"RANKING","relation":"ENEMY"}"""))
		assertEquals(0, shareRows())
	}

	// ── 지우기 ──

	@Test
	fun `내 공유를 지우면 링크가 404 가 되고 남의 공유는 지울 수 없다`() {
		val synastry = insertSynastry("지우", 87)
		val shareId = share(scoreBody(synastry))["shareId"].asString()
		val (_, otherToken) = otherAccount()

		val byOther = http(port, "DELETE", "/shares/$shareId", token = otherToken)
		assertEquals(404, byOther.statusCode())
		assertEquals(NOT_FOUND, byOther.body())
		assertEquals(200, shared(shareId).statusCode())
		assertEquals(401, http(port, "DELETE", "/shares/$shareId").statusCode())

		assertEquals(204, http(port, "DELETE", "/shares/$shareId", token = token).statusCode())
		assertEquals(404, shared(shareId).statusCode())
		assertEquals(404, http(port, "DELETE", "/shares/$shareId", token = token).statusCode())
		// 다시 만들면 새 식별자다
		assertNotEquals(shareId, share(scoreBody(synastry))["shareId"].asString())
	}

	@Test
	fun `계정을 지우면 그 사람의 공유가 모두 사라진다`() {
		insertReading()
		val synastry = insertSynastry("지우", 87)
		val ids = listOf(share("""{"kind":"READING"}"""), share(scoreBody(synastry))).map { it["shareId"].asString() }

		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		assertEquals(0, shareRows())
		for (id in ids) assertEquals(404, shared(id).statusCode())
	}

	// ── 브라우저의 다른 출처 요청 ──

	@Test
	fun `앱의 웹 주소에서 공개 조회를 부를 수 있다`() {
		val synastry = insertSynastry("지우", 87)
		val shareId = share(scoreBody(synastry))["shareId"].asString()

		val allowed = http(port, "GET", "/shared/$shareId", null, null, "Origin", APP)
		assertEquals(200, allowed.statusCode())
		assertEquals(APP, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
		// 없는 식별자의 404 도 앱이 읽을 수 있다
		assertEquals(APP, http(port, "GET", "/shared/none", null, null, "Origin", APP).headers().firstValue("Access-Control-Allow-Origin").orElse(null))

		val evil = http(port, "GET", "/shared/$shareId", null, null, "Origin", "https://evil.example")
		assertFalse(evil.headers().firstValue("Access-Control-Allow-Origin").isPresent)
	}
}

/** 공개 조회의 주소별 요청 수 제한. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["byeolmyeong.rate-limit.shared-per-minute=3"],
)
class ShareRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `한 주소가 공개 조회를 분당 한도보다 많이 부르면 429 다`() {
		// 창(1분)의 경계에 걸리면 한도가 다시 차므로, 한도의 두 배 넘게 불러 그 안에 429 가 나오는지 본다
		val statuses = (1..8).map { http(port, "GET", "/shared/AAAAAAAAAAAAAAAAAAAAAA").statusCode() }
		assertEquals(404, statuses.first())
		assertTrue(429 in statuses, statuses.toString())
		assertTrue(statuses.count { it == 404 } <= 6, statuses.toString())
		val limited = (1..8).map { http(port, "GET", "/shared/AAAAAAAAAAAAAAAAAAAAAA") }.first { it.statusCode() == 429 }
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
	}
}
