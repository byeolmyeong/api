package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@AdminIntegrationTest
class AdminStatsApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@BeforeEach
	fun clean() = resetAdminData(jdbc)

	private fun account(daysAgo: Int): UUID {
		val id = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id, created_at) values (?, 'TEST', ?, now() - make_interval(days => ?))", id, "user-$id", daysAgo)
		return id
	}

	private fun stats(): JsonNode {
		val response = http(port, "GET", "/admin/stats", token = adminToken(port))
		assertEquals(200, response.statusCode())
		return response.json()
	}

	/** 센 때(`generatedAt`)와 지금에 따라 바뀌는 시각(`inquiryQueue.oldestWaitingAt`)을 뺀 나머지. 그 둘은 따로 본다. */
	private fun JsonNode.numbers(): JsonNode = (deepCopy() as ObjectNode).also {
		it.remove("generatedAt")
		(it["inquiryQueue"] as ObjectNode).remove("oldestWaitingAt")
	}

	/** 잎의 경로(`a.b`)와 값. */
	private fun leaves(node: JsonNode, path: String = ""): List<Pair<String, JsonNode>> =
		if (node.isObject) node.propertyNames().flatMap { leaves(node[it], if (path.isEmpty()) it else "$path.$it") } else listOf(path to node)

	@Test
	fun `아무것도 없으면 모든 수가 0 이고 상태 키는 그대로 있다`() {
		val json = stats()
		assertEquals(
			mapper.readTree(
				"""
				{
					"accounts": {"total": 0, "last7Days": 0, "last30Days": 0},
					"profiles": {"total": 0},
					"readings": {"total": 0},
					"reports": {"total": 0, "byStatus": {"DONE": 0, "WRITING": 0, "FAILED": 0}},
					"synastries": {"total": 0},
					"synastryReports": {"total": 0, "byStatus": {"DONE": 0, "WRITING": 0, "FAILED": 0}},
					"directions": {"total": 0, "byTopic": {}},
					"inquiries": {"total": 0, "byStatus": {"NEW": 0, "IN_PROGRESS": 0, "ANSWERED": 0, "DONE": 0}},
					"users": {"total": 0, "joinedToday": 0, "joined7d": 0},
					"nicknames": {"total": 0},
					"detailReports": {"me": 0, "timing": 0, "generating": 0},
					"failuresToday": {"nickname": null, "report": 0},
					"inquiryQueue": {"waiting": 0},
					"consents": {"pushRate": 0.0, "marketingRate": 0.0, "adReachable": 0},
					"todaySky": null,
					"pushToday": null
				}
				""",
			),
			json.numbers(),
		)
		assertTrue(json["inquiryQueue"]["oldestWaitingAt"].isNull, "기다리는 문의가 없다")
		val generatedAt = Instant.parse(json["generatedAt"].asString())
		assertTrue(Duration.between(generatedAt, Instant.now()).abs() < Duration.ofMinutes(1))
	}

	@Test
	fun `수치는 넣어 둔 행의 수와 같고 개인 정보가 없다`() {
		val today = account(0)
		val threeDays = account(3)
		val tenDays = account(10)
		val fortyDays = account(40)

		jdbc.update("insert into profiles (account_id, birth_date, birth_time, place_id) values (?, '1991-02-03', '04:05', 1841277)", today)
		jdbc.update("insert into profiles (account_id, birth_date) values (?, '1988-07-06')", threeDays)
		jdbc.update("insert into profiles (account_id, birth_date) values (?, '1975-12-25')", tenDays)

		val text = """{"headline":"비밀스러운해석글"}"""
		for ((owner, hash) in listOf(today to "h1", today to "h2", threeDays to "h1")) {
			jdbc.update("insert into readings (id, owner_id, kind, chart_hash, body, share_id) values (?, ?, 'SUMMARY', ?, ?::jsonb, ?)", UUID.randomUUID(), owner, hash, text, "share-$owner-$hash")
		}
		fun report(owner: UUID, hash: String, status: String) = jdbc.update(
			"insert into reports (id, owner_id, chart_hash, status, body) values (?, ?, ?, ?, ?::jsonb)",
			UUID.randomUUID(), owner, hash, status, if (status == "DONE") text else null,
		)
		report(today, "h1", "DONE"); report(today, "h2", "DONE"); report(threeDays, "h1", "DONE")
		report(tenDays, "h1", "WRITING"); report(fortyDays, "h1", "FAILED"); report(fortyDays, "h2", "FAILED")

		val synastries = (1..5).map {
			UUID.randomUUID().also { id ->
				jdbc.update(
					"insert into synastries (id, owner_id, name, relation, score, headline, keywords, owner_chart_hash) values (?, ?, '비밀상대이름', 'LOVE', 77, '비밀한줄요약', '[\"a\",\"b\",\"c\"]'::jsonb, 'h1')",
					id, if (it <= 3) today else tenDays,
				)
			}
		}
		for ((index, status) in listOf("DONE", "WRITING", "WRITING", "FAILED").withIndex()) {
			jdbc.update("insert into synastry_reports (synastry_id, status, body) values (?, ?, ?::jsonb)", synastries[index], status, if (status == "DONE") text else null)
		}
		for ((owner, topic) in listOf(today to "LOVE", threeDays to "LOVE", today to "FUTURE")) {
			jdbc.update("insert into directions (id, owner_id, topic, target, body) values (?, ?, ?, 'h1', ?::jsonb)", UUID.randomUUID(), owner, topic, text)
		}
		for ((index, status) in listOf("NEW", "NEW", "NEW", "IN_PROGRESS", "DONE", "DONE", "ANSWERED").withIndex()) {
			jdbc.update(
				"insert into inquiries (id, owner_id, category, body, reply_email, status) values (?, ?, 'BUG', '비밀스러운문의글 입니다 확인', 'hidden@secret-domain.example', ?)",
				UUID.randomUUID(), if (index % 2 == 0) today else fortyDays, status,
			)
		}

		val response = http(port, "GET", "/admin/stats", token = adminToken(port))
		assertEquals(200, response.statusCode())
		assertEquals(
			mapper.readTree(
				"""
				{
					"accounts": {"total": 4, "last7Days": 2, "last30Days": 3},
					"profiles": {"total": 3},
					"readings": {"total": 3},
					"reports": {"total": 6, "byStatus": {"DONE": 3, "WRITING": 1, "FAILED": 2}},
					"synastries": {"total": 5},
					"synastryReports": {"total": 4, "byStatus": {"DONE": 1, "WRITING": 2, "FAILED": 1}},
					"directions": {"total": 3, "byTopic": {"LOVE": 2, "FUTURE": 1}},
					"inquiries": {"total": 7, "byStatus": {"NEW": 3, "IN_PROGRESS": 1, "ANSWERED": 1, "DONE": 2}},
					"users": {"total": 4, "joinedToday": 1, "joined7d": 2},
					"nicknames": {"total": 0},
					"detailReports": {"me": 0, "timing": 0, "generating": 0},
					"failuresToday": {"nickname": null, "report": 0},
					"inquiryQueue": {"waiting": 5},
					"consents": {"pushRate": 0.0, "marketingRate": 0.0, "adReachable": 0},
					"todaySky": null,
					"pushToday": null
				}
				""",
			),
			response.json().numbers(),
		)
		// 답이 없고 끝나지 않은 문의 다섯 중 가장 오래된 것의 만든 때(UTC 초까지).
		assertTrue(response.json()["inquiryQueue"]["oldestWaitingAt"].asString().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))

		// 개수 말고는 아무것도 없다: 비율 둘은 실수, 아직 세지 못하는 칸은 null, 나머지 잎은 모두 정수이고, 넣어 둔 글·이름·이메일·출생 정보·id 가 없다.
		for ((path, leaf) in leaves(response.json().numbers())) when (path) {
			"consents.pushRate", "consents.marketingRate" -> assertTrue(leaf.isFloatingPointNumber, path)
			"failuresToday.nickname", "todaySky", "pushToday" -> assertTrue(leaf.isNull, path)
			else -> assertTrue(leaf.isIntegralNumber, "$path 는 정수다")
		}
		assertFalse(response.body().contains("["), "행 목록이 없다")
		for (hidden in listOf("비밀", "hidden", "secret-domain", "1991", "1988", "1975", "02-03", "04:05", "1841277", today.toString(), "share-", "h1")) {
			assertFalse(response.json().numbers().toString().contains(hidden), "응답에 '$hidden' 이 있다")
		}
	}

	@Test
	fun `계정이 지워지면 그 사람의 수도 빠진다`() {
		val gone = account(1)
		account(1)
		jdbc.update("insert into profiles (account_id, birth_date) values (?, '1988-07-06')", gone)
		jdbc.update("insert into inquiries (id, owner_id, category, body) values (?, ?, 'BUG', '지워질 계정의 문의입니다.')", UUID.randomUUID(), gone)
		assertEquals(listOf(2, 1, 1), stats().let { listOf(it["accounts"]["total"].asInt(), it["profiles"]["total"].asInt(), it["inquiries"]["total"].asInt()) })

		jdbc.update("delete from accounts where id = ?", gone)

		assertEquals(listOf(1, 0, 0), stats().let { listOf(it["accounts"]["total"].asInt(), it["profiles"]["total"].asInt(), it["inquiries"]["total"].asInt()) })
	}
}
