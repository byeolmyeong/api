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
import com.byeolmyeong.api.report.ReportTiming
import com.byeolmyeong.api.report.TimingInterval
import com.byeolmyeong.api.report.TimingMilestone
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val MAPO = 1841277
// 1990-03-15 생 — 해 별자리는 물고기자리(PISCES)
private const val ME = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val SECRET = "본문비밀 — 카드에 나가면 안 되는 글"
private val PISCES_SUN = mapOf("when" to "BIRTH", "items" to listOf(mapOf("kind" to "PLACEMENT", "body" to "SUN", "sign" to "PISCES")))

/** 공유 v2(결정 036): 내 별명 · 나 탭 영역 · 그날의 하늘 · 다음 큰 변화. 카드 글은 그 글을 가진 기능에게 이벤트로 묻는다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.shared-per-minute=100000"],
)
class ShareV2ApiIntegrationTest {

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
		jdbc.update("update accounts set nickname = '새벽별' where id = ?", me)
	}

	private fun chartHash(): String = profiles.chartInput(me)!!.hash

	private fun insertReading(hash: String = chartHash()) {
		val body = mapOf(
			"headline" to "조용히 깊어지는 사람",
			"sections" to listOf("단단한 중심", "섬세한 마음", "부드러운 첫인상", "몰입하는 힘", "내려놓는 연습").map { mapOf("title" to it, "body" to SECRET, "reason" to PISCES_SUN) },
			"strengths" to listOf("강점비밀1", "강점비밀2", "강점비밀3"),
			"cautions" to listOf("보완비밀1", "보완비밀2"),
		)
		jdbc.update(
			"insert into readings (id, owner_id, kind, chart_hash, body, share_id) values (?, ?, 'SUMMARY_V2', ?, ?::jsonb, ?)",
			UUID.randomUUID(), me, hash, mapper.writeValueAsString(body), "old-${UUID.randomUUID()}",
		)
	}

	private fun insertDetail(kind: String, body: Any?, status: String = "DONE", hash: String = chartHash()) {
		jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, ?, ?, ?, ?::jsonb)",
			UUID.randomUUID(), me, kind, hash, status, body?.let(mapper::writeValueAsString),
		)
	}

	/** 완성된 나 탭 풀이. 영역마다 제목(한 줄 요약)은 "<영역>-영역제목", 첫 문단 제목은 "<영역>-첫문단". 연애는 문단이 없다. */
	private fun meReport(): Map<String, Any> = mapOf(
		"areas" to listOf("PERSONALITY", "LOVE", "RELATIONSHIP", "WORK", "EMOTION").map { area ->
			val paragraphs = if (area == "LOVE") emptyList() else (1..4).map { i ->
				mapOf("title" to if (i == 1) "$area-첫문단" else "$area-문단$i", "body" to SECRET, "reason" to PISCES_SUN)
			}
			buildMap {
				put("area", area)
				put("title", "$area-영역제목")
				put("keywords", listOf("키워드1", "키워드2", "키워드3"))
				put("stars", listOf(mapOf("body" to "SUN", "sign" to "PISCES")))
				put("paragraphs", paragraphs)
				if (area == "LOVE") {
					put("practices", listOf(mapOf("title" to "천천히 말하기", "body" to SECRET)))
					put("reason", PISCES_SUN)
				}
			}
		},
	)

	/** 완성된 시기 풀이. 다음 큰 마디는 [next](없으면 모두 지난 마디). */
	private fun timing(next: LocalDate?): ReportTiming {
		fun milestone(kind: MilestoneKind, date: LocalDate, age: Int, title: String) =
			TimingMilestone(kind, date, date.year, age, date.minusMonths(2), date.plusMonths(3), ChangeSize.BIG, title, SECRET)
		val dates = if (next == null) (4 downTo 1).map { today.minusYears(it.toLong()) } else listOf(today.minusYears(7), next, next.plusYears(14), next.plusYears(29))
		return ReportTiming(
			timeKnown = true,
			milestones = MilestoneKind.entries.zip(dates).mapIndexed { i, (kind, date) -> milestone(kind, date, 20 + i * 10, "마디-${kind.name}") },
			intervals = listOf(TimingInterval("i1", IntervalKind.OPPORTUNITY, today.plusMonths(1), today.plusMonths(4), LifeArea.PEOPLE, null, "구간제목", SECRET)),
		)
	}

	/** 저장된 그날의 하늘(결정 034). */
	private fun insertDaySky(date: LocalDate, title: String, hash: String = chartHash()) {
		val body = mapOf(
			"title" to title,
			"category" to "PEOPLE",
			"flow" to mapOf("people" to SECRET, "work" to SECRET, "mind" to SECRET),
			"reason" to mapOf("when" to "DAY", "items" to listOf(mapOf("kind" to "PLACEMENT", "body" to "SUN", "sign" to "PISCES"))),
			"practices" to listOf(mapOf("title" to "천천히 걷기")),
		)
		jdbc.update(
			"insert into day_skies (id, owner_id, chart_hash, date, body) values (?, ?, ?, ?, ?::jsonb)",
			UUID.randomUUID(), me, hash, date, mapper.writeValueAsString(body),
		)
	}

	private fun post(body: String): HttpResponse<String> = http(port, "POST", "/shares", body, token)

	private fun preview(body: String, session: String? = token): HttpResponse<String> = http(port, "POST", "/shares/preview", body, session)

	private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

	private fun share(body: String): JsonNode {
		val response = post(body)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	private fun sharedCard(shareId: String): JsonNode = mapper.readTree(http(port, "GET", "/shared/$shareId").body())

	private fun keys(node: JsonNode): Set<String> = node.propertyNames().toSet()

	private fun assertError(status: Int, code: String, response: HttpResponse<String>, what: String = "") {
		assertEquals(status, response.statusCode(), "$what ${response.body()}")
		assertEquals("""{"error":"$code"}""", response.body(), what)
	}

	@Test
	fun `내 별명 카드는 닉네임 · 해 별자리 · 한 줄 별명만 싣고 공개 조회가 그대로 준다`() {
		insertReading()

		val created = share("""{"kind":"NICKNAME"}""")

		val card = created["card"]
		assertEquals(setOf("kind", "nickname", "sunSign", "headline"), keys(card))
		assertEquals("NICKNAME", card["kind"].asString())
		assertEquals("새벽별", card["nickname"].asString())
		assertEquals("PISCES", card["sunSign"].asString())
		assertEquals("조용히 깊어지는 사람", card["headline"].asString())
		assertEquals(card, sharedCard(created["shareId"].asString()))
	}

	@Test
	fun `닉네임이 없는 옛 계정이면 카드에 nickname 키가 없다`() {
		jdbc.update("update accounts set nickname = null where id = ?", me)
		insertReading()

		assertEquals(setOf("kind", "sunSign", "headline"), keys(share("""{"kind":"NICKNAME"}""")["card"]))
	}

	@Test
	fun `요약 해석이 없거나 옛 차트의 것뿐이면 내 별명 카드는 404`() {
		assertError(404, "NOT_FOUND", post("""{"kind":"NICKNAME"}"""), "없음")
		insertReading(hash = "old-chart")
		assertError(404, "NOT_FOUND", post("""{"kind":"NICKNAME"}"""), "옛 차트")
	}

	@Test
	fun `나 탭 영역 카드는 모든 영역에서 그 영역의 한 줄 요약(영역 제목)을 싣는다`() {
		insertDetail("ME", meReport())

		val personality = share("""{"kind":"ME_AREA","area":"PERSONALITY"}""")["card"]
		assertEquals(setOf("kind", "nickname", "area", "title"), keys(personality))
		assertEquals("ME_AREA", personality["kind"].asString())
		assertEquals("PERSONALITY", personality["area"].asString())
		assertEquals("PERSONALITY-영역제목", personality["title"].asString(), "첫 문단 제목이 아니다")
		assertEquals("LOVE-영역제목", share("""{"kind":"ME_AREA","area":"LOVE"}""")["card"]["title"].asString())
		assertEquals("WORK-영역제목", share("""{"kind":"ME_AREA","area":"WORK"}""")["card"]["title"].asString())
		assertEquals("EMOTION-영역제목", mapper.readTree(preview("""{"kind":"ME_AREA","area":"EMOTION"}""").body())["card"]["title"].asString(), "미리 받기도 같다")
	}

	@Test
	fun `나 탭 영역이 다섯 밖이면 400 INVALID_AREA, 풀이가 완성되지 않았으면 404`() {
		assertError(400, "INVALID_AREA", post("""{"kind":"ME_AREA","area":"MONEY"}"""))
		assertError(400, "INVALID_AREA", post("""{"kind":"ME_AREA"}"""))
		assertError(404, "NOT_FOUND", post("""{"kind":"ME_AREA","area":"PERSONALITY"}"""), "없음")
		insertDetail("ME", null, status = "WRITING")
		assertError(404, "NOT_FOUND", post("""{"kind":"ME_AREA","area":"PERSONALITY"}"""), "쓰는 중")
	}

	@Test
	fun `다음 큰 변화 카드는 다음 마디의 해 · 만 나이 · 제목을 싣는다`() {
		val next = LocalDate.of(today.year + 2, 4, 15)
		insertDetail("TIMING", timing(next))

		val card = share("""{"kind":"NEXT_BIG"}""")["card"]

		assertEquals(setOf("kind", "nickname", "year", "age", "title"), keys(card))
		assertEquals("NEXT_BIG", card["kind"].asString())
		assertEquals(next.year, card["year"].asInt())
		assertEquals(30, card["age"].asInt())
		assertEquals("마디-${MilestoneKind.entries[1].name}", card["title"].asString())
	}

	@Test
	fun `시기 풀이가 없거나 오늘 뒤의 마디가 없으면 다음 큰 변화 카드는 404`() {
		assertError(404, "NOT_FOUND", post("""{"kind":"NEXT_BIG"}"""), "없음")
		insertDetail("TIMING", timing(null))
		assertError(404, "NOT_FOUND", post("""{"kind":"NEXT_BIG"}"""), "모두 지난 마디")
	}

	@Test
	fun `그날의 하늘 카드의 날짜 꼴이 틀리면 400 INVALID_DATE, 만들지 않은 날이면 404`() {
		assertError(400, "INVALID_DATE", post("""{"kind":"DAY_SKY","date":"2026-13-01"}"""))
		assertError(400, "INVALID_DATE", post("""{"kind":"DAY_SKY","date":"2026-1-5"}"""))
		assertError(400, "INVALID_DATE", post("""{"kind":"DAY_SKY"}"""))
		assertError(404, "NOT_FOUND", post("""{"kind":"DAY_SKY","date":"$today"}"""))
	}

	@Test
	fun `그날의 하늘 카드는 그 날짜와 저장된 그날의 하늘 제목을 싣는다`() {
		insertDaySky(today, "말이 부드럽게 닿는 날")

		val card = share("""{"kind":"DAY_SKY","date":"$today"}""")["card"]

		assertEquals(setOf("kind", "nickname", "date", "title"), keys(card))
		assertEquals("DAY_SKY", card["kind"].asString())
		assertEquals(today.toString(), card["date"].asString())
		assertEquals("말이 부드럽게 닿는 날", card["title"].asString())
		assertError(404, "NOT_FOUND", post("""{"kind":"DAY_SKY","date":"${today.plusDays(1)}"}"""), "만들지 않은 날")
		insertDaySky(today.minusDays(1), "옛 차트의 날", hash = "old-chart")
		assertError(404, "NOT_FOUND", post("""{"kind":"DAY_SKY","date":"${today.minusDays(1)}"}"""), "옛 차트")
	}

	@Test
	fun `같은 내용을 다시 공유하면 같은 식별자, 다른 영역은 다른 식별자다`() {
		insertDetail("ME", meReport())

		val first = share("""{"kind":"ME_AREA","area":"PERSONALITY"}""")["shareId"].asString()
		val again = share("""{"kind":"ME_AREA","area":"PERSONALITY"}""")["shareId"].asString()
		val other = share("""{"kind":"ME_AREA","area":"EMOTION"}""")["shareId"].asString()

		assertEquals(first, again)
		assertNotEquals(first, other)
		assertEquals(2, jdbc.queryForObject("select count(*) from shares where kind = 'ME_AREA'", Int::class.java))
	}

	@Test
	fun `미리 받기는 만들기와 같은 카드만 주고 공유 행 · 감사 기록 · 식별자를 만들지 않는다`() {
		insertReading()
		insertDetail("ME", meReport())

		val previewed = preview("""{"kind":"NICKNAME"}""")
		assertEquals(200, previewed.statusCode(), previewed.body())
		val body = mapper.readTree(previewed.body())
		assertEquals(setOf("card"), keys(body))
		val area = mapper.readTree(preview("""{"kind":"ME_AREA","area":"LOVE"}""").body())["card"]
		assertEquals(0, count("select count(*) from shares"), "행을 만들지 않는다")
		assertEquals(0, count("select count(*) from audit_logs where action = 'SHARE_CREATED'"), "감사 기록도 없다")

		assertEquals(share("""{"kind":"NICKNAME"}""")["card"], body["card"], "만들 때와 같은 카드")
		assertEquals(share("""{"kind":"ME_AREA","area":"LOVE"}""")["card"], area)
		assertEquals(2, count("select count(*) from audit_logs where action = 'SHARE_CREATED'"), "만들기만 남는다")
	}

	@Test
	fun `미리 받기도 400 · 404 규칙이 같고 세션이 필요하다`() {
		assertError(404, "NOT_FOUND", preview("""{"kind":"NICKNAME"}"""), "요약 해석 없음")
		assertError(400, "INVALID_KIND", preview("""{"kind":"NOPE"}"""))
		assertError(400, "INVALID_AREA", preview("""{"kind":"ME_AREA","area":"MONEY"}"""))
		assertError(400, "INVALID_DATE", preview("""{"kind":"DAY_SKY","date":"2026-1-5"}"""))
		assertEquals(401, preview("""{"kind":"NICKNAME"}""", session = null).statusCode())
	}
}
