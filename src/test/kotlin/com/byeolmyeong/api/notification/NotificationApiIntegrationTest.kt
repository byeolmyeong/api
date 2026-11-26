package com.byeolmyeong.api.notification

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.InquiryAnswered
import com.byeolmyeong.api.http
import com.byeolmyeong.api.reading.ReadingCreated
import com.byeolmyeong.api.report.MeReportDone
import com.byeolmyeong.api.report.TimingReportDone
import com.byeolmyeong.api.synastry.SynastryReportDone
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 알림함(결정 035): 완성 이벤트마다 한 줄 · 30일 · 새것부터 100개 · 누르면 읽음. 소유자는 세션에서만. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class NotificationApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var events: ApplicationEventPublisher

	@Autowired
	private lateinit var tx: TransactionTemplate

	@Autowired
	private lateinit var notifications: NotificationService

	private val mapper = JsonMapper()
	private lateinit var me: UUID
	private lateinit var token: String
	private lateinit var other: UUID
	private lateinit var otherToken: String

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		account("noti-me").let { (id, t) -> me = id; token = t }
		account("noti-other").let { (id, t) -> other = id; otherToken = t }
	}

	private fun account(name: String): Pair<UUID, String> {
		val id = UUID.randomUUID()
		val session = "notification-session-$name"
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'TEST', ?)", id, name)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(session.toByteArray())), id)
		return id to session
	}

	private fun list(session: String = token): JsonNode {
		val response = http(port, "GET", "/notifications", token = session)
		assertEquals(200, response.statusCode(), response.body())
		return mapper.readTree(response.body())
	}

	private fun read(id: Any, session: String = token) = http(port, "POST", "/notifications/$id/read", token = session)

	private fun synastry(owner: UUID): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into synastries (id, owner_id, name, relation, score, headline, keywords, owner_chart_hash) values (?, ?, '상대', 'FRIEND', 70, '한 줄', '[]'::jsonb, 'h')",
			id, owner,
		)
		return id
	}

	private fun inquiry(owner: UUID): UUID {
		val id = UUID.randomUUID()
		jdbc.update("insert into inquiries (id, owner_id, category, body) values (?, ?, 'BUG', '앱이 멈춰요 열 글자 넘게')", id, owner)
		return id
	}

	@Test
	fun `완성 이벤트마다 그 사람에게 한 줄이 생기고 새것부터 보인다`() {
		val syn = synastry(me)
		val inq = inquiry(me)
		events.publishEvent(ReadingCreated(me))
		events.publishEvent(MeReportDone(me))
		events.publishEvent(TimingReportDone(me))
		events.publishEvent(SynastryReportDone(me, syn))
		events.publishEvent(InquiryAnswered(me, inq))

		val body = list()
		assertEquals(5, body["unread"].asInt())
		val items = body["items"].toList()
		assertEquals(listOf("INQUIRY_ANSWERED", "SYNASTRY_REPORT_DONE", "TIMING_REPORT_DONE", "ME_REPORT_DONE", "READING_DONE"), items.map { it["kind"].asString() })
		assertEquals(setOf("id", "kind", "createdAt", "read", "inquiryId"), items[0].propertyNames().toSet(), "없는 칸은 빼고 보낸다")
		assertEquals(inq.toString(), items[0]["inquiryId"].asString())
		assertEquals(syn.toString(), items[1]["synastryId"].asString())
		assertEquals(setOf("id", "kind", "createdAt", "read"), items[2].propertyNames().toSet())
		assertTrue(items.all { !it["read"].asBoolean() })
		assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(items[0]["createdAt"].asString()))
		assertEquals(0, list(otherToken)["items"].size(), "남의 알림은 안 보인다")
	}

	@Test
	fun `트랜잭션 안에서 낸 이벤트는 커밋된 뒤에만 남고 되돌리면 남지 않는다`() {
		tx.executeWithoutResult { status ->
			events.publishEvent(MeReportDone(me))
			status.setRollbackOnly()
		}
		assertEquals(0, list()["items"].size())

		tx.executeWithoutResult { events.publishEvent(MeReportDone(me)) }
		assertEquals(1, list()["items"].size())
	}

	@Test
	fun `누르면 읽음이 되고 다시 눌러도 204 이며 남의 것 · 없는 것 · 모양이 틀린 것은 404 다`() {
		events.publishEvent(MeReportDone(me))
		events.publishEvent(MeReportDone(other))
		val mine = list()["items"][0]["id"].asString()
		val theirs = list(otherToken)["items"][0]["id"].asString()

		assertEquals(204, read(mine).statusCode())
		assertEquals(204, read(mine).statusCode(), "이미 읽어도 204")
		assertEquals(true, list()["items"][0]["read"].asBoolean())
		assertEquals(0, list()["unread"].asInt())

		for (bad in listOf(theirs, UUID.randomUUID().toString(), "not-a-uuid")) {
			val response = read(bad)
			assertEquals(404, response.statusCode(), bad)
			assertEquals("""{"error":"NOT_FOUND"}""", response.body())
		}
		assertEquals(1, list(otherToken)["unread"].asInt(), "남의 것은 그대로")
	}

	@Test
	fun `30일이 지난 줄은 보이지 않고 날마다 지운다`() {
		events.publishEvent(MeReportDone(me))
		events.publishEvent(TimingReportDone(me))
		jdbc.update("update notifications set created_at = now() - interval '31 days' where kind = 'ME_REPORT_DONE'")

		assertEquals(listOf("TIMING_REPORT_DONE"), list()["items"].toList().map { it["kind"].asString() })
		assertEquals(1, list()["unread"].asInt())

		notifications.deleteExpired()
		assertEquals(listOf("TIMING_REPORT_DONE"), jdbc.queryForList("select kind from notifications", String::class.java))
	}

	@Test
	fun `100개까지 주고 안 읽은 수는 30일 안 모두를 센다`() {
		repeat(105) { jdbc.update("insert into notifications (id, owner_id, kind, created_at) values (?, ?, 'ME_REPORT_DONE', now() - make_interval(secs => ?))", UUID.randomUUID(), me, it) }

		val body = list()
		assertEquals(100, body["items"].size())
		assertEquals(105, body["unread"].asInt())
	}

	@Test
	fun `궁합 · 문의 · 계정이 지워지면 그 알림도 지워진다`() {
		val syn = synastry(me)
		val inq = inquiry(me)
		events.publishEvent(SynastryReportDone(me, syn))
		events.publishEvent(InquiryAnswered(me, inq))
		events.publishEvent(MeReportDone(me))

		jdbc.update("delete from synastries where id = ?", syn)
		jdbc.update("delete from inquiries where id = ?", inq)
		assertEquals(listOf("ME_REPORT_DONE"), list()["items"].toList().map { it["kind"].asString() })

		jdbc.update("delete from accounts where id = ?", me)
		assertEquals(0, jdbc.queryForObject("select count(*) from notifications", Int::class.java))
	}

	@Test
	fun `세션이 없으면 401 이다`() {
		assertEquals(401, http(port, "GET", "/notifications").statusCode())
		assertEquals(401, http(port, "POST", "/notifications/${UUID.randomUUID()}/read").statusCode())
	}
}
