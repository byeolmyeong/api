package com.byeolmyeong.api.report

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val HASH = "chart-hash"
private const val BODY = """{"areas":[]}"""

/**
 * V22(결정 029): 자세한 풀이 `detail_reports` — 나 탭 풀이(ME)와 시기 풀이(TIMING)를 한 테이블에 kind 로 나눈다. 꼴은 `reports`(V4 · V9)와 같다.
 * 깨끗한 DB(테스트 서버가 띄운 DB)와 V21 까지만 돈 DB(같은 DB 의 다른 스키마) 양쪽에서 돈다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class DetailReportsMigrationIntegrationTest {

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var dataSource: DataSource

	private val owner = UUID.randomUUID()

	@BeforeEach
	fun account() {
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", owner, "migration-$owner")
	}

	@AfterEach
	fun clean() {
		jdbc.update("delete from accounts where id = ?", owner)
	}

	private fun insert(kind: String = "ME", status: String = "WRITING", body: String? = null, chartHash: String = HASH): Int = jdbc.update(
		"insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, ?, ?, ?, ?::jsonb)",
		UUID.randomUUID(), owner, kind, chartHash, status, body,
	)

	private fun rows(): Int = jdbc.queryForObject("select count(*) from detail_reports where owner_id = ?", Int::class.java, owner)!!

	@Test
	fun `깨끗한 DB 에 detail_reports 가 reports 와 같은 꼴로 생긴다`() {
		val columns = jdbc.query(
			"select column_name, data_type, is_nullable from information_schema.columns where table_schema = 'public' and table_name = 'detail_reports'",
			{ rs, _ -> rs.getString("column_name") to (rs.getString("data_type") to rs.getString("is_nullable")) },
		).toMap()
		assertEquals(
			mapOf(
				"id" to ("uuid" to "NO"),
				"owner_id" to ("uuid" to "NO"),
				"kind" to ("text" to "NO"),
				"chart_hash" to ("text" to "NO"),
				"status" to ("text" to "NO"),
				"body" to ("jsonb" to "YES"),
				"started_at" to ("timestamp with time zone" to "NO"),
				"updated_at" to ("timestamp with time zone" to "NO"),
				"active_at" to ("timestamp with time zone" to "NO"),
			),
			columns,
		)
		// 시각 칸은 넣을 때 지금을 받는다
		assertEquals(1, insert())
		assertEquals(1, jdbc.queryForObject("select count(*) from detail_reports where owner_id = ? and started_at is not null and active_at is not null", Int::class.java, owner))
	}

	@Test
	fun `DONE 인데 본문이 없거나 DONE 이 아닌데 본문이 있으면 거절한다`() {
		assertFailsWith<DataIntegrityViolationException>("DONE · 본문 없음") { insert(status = "DONE", body = null) }
		assertFailsWith<DataIntegrityViolationException>("WRITING · 본문 있음") { insert(status = "WRITING", body = BODY) }
		assertFailsWith<DataIntegrityViolationException>("FAILED · 본문 있음") { insert(status = "FAILED", body = BODY) }
		assertEquals(0, rows())

		assertEquals(1, insert(status = "DONE", body = BODY, chartHash = "a"))
		assertEquals(1, insert(status = "WRITING", chartHash = "b"))
		assertEquals(1, insert(status = "FAILED", chartHash = "c"))
	}

	@Test
	fun `kind 는 ME · TIMING, status 는 WRITING · DONE · FAILED 만 받는다`() {
		assertFailsWith<DataIntegrityViolationException>("kind") { insert(kind = "REPORT") }
		assertFailsWith<DataIntegrityViolationException>("소문자 kind") { insert(kind = "me") }
		assertFailsWith<DataIntegrityViolationException>("status") { insert(status = "NONE") }
		assertEquals(0, rows())
	}

	@Test
	fun `같은 소유자 · 종류 · 차트는 한 행이고 종류가 다르면 따로다`() {
		assertEquals(1, insert(kind = "ME"))
		assertFailsWith<DataIntegrityViolationException> { insert(kind = "ME") }
		assertEquals(1, insert(kind = "TIMING"))
		assertEquals(1, insert(kind = "ME", chartHash = "other-chart"))
		assertEquals(3, rows())
	}

	@Test
	fun `계정을 지우면 함께 지워진다`() {
		insert(kind = "ME", status = "DONE", body = BODY)
		insert(kind = "TIMING")
		jdbc.update("delete from accounts where id = ?", owner)
		assertEquals(0, rows())
	}

	@Test
	fun `V21 까지 돈 DB 도 올라가고 있던 reports 행은 그대로다`() {
		val schema = "upgrade_from_v21"
		try {
			flyway(schema, target = "21").migrate()
			val old = UUID.randomUUID()
			jdbc.update("insert into $schema.accounts (id, provider, provider_user_id) values (?, 'GOOGLE', 'old')", old)
			jdbc.update("insert into $schema.reports (id, owner_id, chart_hash, status) values (gen_random_uuid(), ?, 'h', 'FAILED')", old)
			flyway(schema, target = "latest").migrate()

			assertEquals(1, jdbc.queryForObject("select count(*) from $schema.reports", Int::class.java), "옛 리포트 행은 그대로")
			assertEquals(0, jdbc.queryForObject("select count(*) from $schema.detail_reports", Int::class.java), "새 테이블은 비어 있다")
			jdbc.update("insert into $schema.detail_reports (id, owner_id, kind, chart_hash, status) values (gen_random_uuid(), ?, 'ME', 'h', 'WRITING')", old)
			assertFailsWith<DataIntegrityViolationException> {
				jdbc.update("insert into $schema.detail_reports (id, owner_id, kind, chart_hash, status) values (gen_random_uuid(), ?, 'TIMING', 'h', 'DONE')", old)
			}
		} finally {
			jdbc.execute("drop schema if exists $schema cascade")
		}
	}

	/** 같은 DB 의 [schema] 에 마이그레이션을 [target] 까지 돌린다. */
	private fun flyway(schema: String, target: String): Flyway =
		Flyway.configure().dataSource(dataSource).schemas(schema).target(target).load()
}
