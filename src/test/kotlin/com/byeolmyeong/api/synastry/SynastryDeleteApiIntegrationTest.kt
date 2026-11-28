package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOT_FOUND = """{"error":"NOT_FOUND"}"""

/** 궁합 지우기 `DELETE /synastry/{id}`(결정 019): 리포트와 그 궁합의 방향성 제시도 함께, 공유 카드는 그대로. */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000", "byeolmyeong.rate-limit.shared-per-minute=100000"],
)
class SynastryDeleteApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private lateinit var token: String
	private lateinit var me: UUID

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
	fun setUp() {
		settle()
		jdbc.update("delete from accounts")
		generator.reset()
		token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
		me = jdbc.queryForObject("select id from accounts", UUID::class.java)!!
		jdbc.update("delete from audit_logs")
	}

	@AfterEach
	fun tearDown() = settle()

	/** 궁합 하나를 만들기 시작하고 id 를 준다(완성을 기다리지 않는다). */
	private fun start(name: String = "지우", relation: String = "LOVE"): String {
		val response = http(port, "POST", "/synastry", directBody(name = name, relation = relation, date = "1988-07-21", time = "06:10", placeId = MAPO), token)
		assertEquals(202, response.statusCode(), response.body())
		return mapper.readTree(response.body())["id"].asString()
	}

	/** 완성된 궁합 하나의 id. */
	private fun synastry(name: String = "지우", relation: String = "LOVE"): String = start(name, relation).also { awaitSynastry(port, token, it) }

	private fun delete(id: String, token: String? = this.token) = http(port, "DELETE", "/synastry/$id", token = token)

	private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

	private fun direction(owner: UUID, topic: String, target: String) {
		jdbc.update("insert into directions (id, owner_id, topic, target, body) values (?, ?, ?, ?, '{}'::jsonb)", UUID.randomUUID(), owner, topic, target)
	}

	private fun listedIds(path: String): List<String> = mapper.readTree(http(port, "GET", path, token = token).body()).toList().map { it["id"].asString() }

	@Test
	fun `지우면 궁합과 리포트와 그 궁합의 방향성 제시가 사라지고 나머지는 그대로다`() {
		val gone = synastry("지우")
		val kept = synastry("하늘")
		direction(me, "SYNASTRY", gone)
		direction(me, "SYNASTRY", kept)
		direction(me, "LOVE", "some-chart-hash")
		direction(me, "FUTURE", "$gone|2026-01-01|2026-12-31")

		val response = delete(gone)
		assertEquals(204, response.statusCode(), response.body())
		assertEquals("", response.body())

		assertEquals(404, http(port, "GET", "/synastry/$gone", token = token).statusCode())
		assertEquals(404, http(port, "POST", "/synastry/$gone/retry", token = token).statusCode())
		assertEquals(listOf(kept), listedIds("/synastry"))
		assertEquals(listOf(kept), listedIds("/synastry/ranking?relation=LOVE"))
		assertEquals(listOf(kept), jdbc.queryForList("select synastry_id::text from synastry_reports", String::class.java))
		assertEquals(
			listOf("FUTURE" to "$gone|2026-01-01|2026-12-31", "LOVE" to "some-chart-hash", "SYNASTRY" to kept),
			jdbc.query("select topic, target from directions order by topic") { rs, _ -> rs.getString(1) to rs.getString(2) },
			"그 궁합에 붙은 궁합 주제의 글만 지운다",
		)
		assertEquals(200, http(port, "GET", "/synastry/$kept", token = token).statusCode())
	}

	@Test
	fun `이미 만든 공유 카드는 그대로 열린다`() {
		val id = synastry()
		val share = http(port, "POST", "/shares", """{"kind":"SCORE","synastryId":"$id","myName":"나"}""", token)
		assertEquals(200, share.statusCode(), share.body())
		val shareId = mapper.readTree(share.body())["shareId"].asString()
		val ranking = mapper.readTree(http(port, "POST", "/shares", """{"kind":"RANKING","relation":"LOVE","showNames":true}""", token).body())["shareId"].asString()
		val before = http(port, "GET", "/shared/$shareId").body()

		assertEquals(204, delete(id).statusCode())

		assertEquals(2, count("shares"))
		assertEquals(200, http(port, "GET", "/shared/$shareId").statusCode())
		assertEquals(before, http(port, "GET", "/shared/$shareId").body())
		assertEquals(200, http(port, "GET", "/shared/$ranking").statusCode())
		// 지운 궁합으로 새 카드는 만들 수 없다.
		assertEquals(404, http(port, "POST", "/shares", """{"kind":"SCORE","synastryId":"$id","myName":"나"}""", token).statusCode())
		assertEquals(204, http(port, "DELETE", "/shares/$shareId", token = token).statusCode(), "공유는 따로 지운다")
	}

	@Test
	fun `남의 것·없는 것·id 가 아닌 값은 같은 404 이고 아무것도 지우지 않는다`() {
		val mine = synastry()
		val other = UUID.randomUUID()
		val theirs = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'TEST', 'someone-else')", other)
		jdbc.update(
			"insert into synastries (id, owner_id, name, relation, score, headline, keywords, owner_chart_hash) values (?, ?, '남의것', 'LOVE', 70, '한줄', '[\"a\",\"b\",\"c\"]'::jsonb, 'hash')",
			theirs, other,
		)
		direction(other, "SYNASTRY", theirs.toString())
		jdbc.update("delete from audit_logs")

		for (id in listOf(theirs.toString(), UUID.randomUUID().toString(), "not-a-uuid", "1")) {
			val response = delete(id)
			assertEquals(404, response.statusCode(), id)
			assertEquals(NOT_FOUND, response.body(), id)
		}
		assertEquals(401, delete(mine, token = null).statusCode())
		assertEquals(401, delete(mine, token = "no-such-token").statusCode())
		assertEquals(2, count("synastries"))
		assertEquals(1, count("directions"))
		assertEquals(0, count("audit_logs"), "거절된 요청은 감사 기록에 없다")

		assertEquals(204, delete(mine).statusCode())
		assertEquals(404, delete(mine).statusCode(), "두 번째는 없는 것이다")
		assertEquals(1, count("synastries"))
	}

	@Test
	fun `쓰는 중인 궁합은 지운 뒤에 되살아나지 않는다`() {
		val gate = CountDownLatch(1).also { generator.gate = it }
		val id = start()
		assertEquals(1, count("synastries"))

		assertEquals(204, delete(id).statusCode())
		assertEquals(0, count("synastries"))

		gate.countDown()
		settle()
		assertEquals(0, count("synastry_reports"), "끝난 생성이 아무것도 넣지 않는다")
		assertEquals(0, count("synastries"))
		assertEquals(404, http(port, "GET", "/synastry/$id", token = token).statusCode())
	}

	@Test
	fun `지운 것이 감사 기록에 남는다`() {
		val id = synastry()
		jdbc.update("delete from audit_logs")

		assertEquals(204, delete(id).statusCode())

		val row = jdbc.queryForList("select actor_type, actor_id, action, target_type, target_id, outcome, detail::text as detail from audit_logs").single()
		assertEquals(listOf("USER", me.toString(), "SYNASTRY_DELETED", "SYNASTRY", id, "SUCCESS", null), listOf("actor_type", "actor_id", "action", "target_type", "target_id", "outcome", "detail").map { row[it] })
		val dump = jdbc.queryForList("select row_to_json(a)::text from audit_logs a", String::class.java).joinToString("\n")
		assertFalse("지우" in dump || "1988-07-21" in dump)
		assertTrue(count("audit_logs") == 1)
	}
}
