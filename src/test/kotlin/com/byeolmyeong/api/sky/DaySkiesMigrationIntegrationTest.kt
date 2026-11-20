package com.byeolmyeong.api.sky

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
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val HASH = "chart-hash"
private const val BODY = """{"title":"가짜"}"""
private val DAY = LocalDate.of(2026, 11, 21)

/**
 * V30(결정 034 의 4): 그날의 하늘 `day_skies` — 소유자 · 차트 해시 · 날짜마다 한 행, 본문은 언제나 있다(검증을 통과한 글만 넣는다).
 * 깨끗한 DB(테스트 서버가 띄운 DB)와 V24 까지만 돈 DB(같은 DB 의 다른 스키마) 양쪽에서 돈다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class DaySkiesMigrationIntegrationTest {

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

	private fun insert(chartHash: String = HASH, date: LocalDate = DAY, body: String? = BODY): Int = jdbc.update(
		"insert into day_skies (id, owner_id, chart_hash, date, body) values (?, ?, ?, ?, ?::jsonb)",
		UUID.randomUUID(), owner, chartHash, date, body,
	)

	private fun rows(): Int = jdbc.queryForObject("select count(*) from day_skies where owner_id = ?", Int::class.java, owner)!!

	@Test
	fun `깨끗한 DB 에 day_skies 가 생기고 만든 때는 넣을 때 지금을 받는다`() {
		val columns = jdbc.query(
			"select column_name, data_type, is_nullable from information_schema.columns where table_schema = 'public' and table_name = 'day_skies'",
			{ rs, _ -> rs.getString("column_name") to (rs.getString("data_type") to rs.getString("is_nullable")) },
		).toMap()
		assertEquals(
			mapOf(
				"id" to ("uuid" to "NO"),
				"owner_id" to ("uuid" to "NO"),
				"chart_hash" to ("text" to "NO"),
				"date" to ("date" to "NO"),
				"body" to ("jsonb" to "NO"),
				"created_at" to ("timestamp with time zone" to "NO"),
			),
			columns,
		)
		assertEquals(1, insert())
		assertEquals(1, jdbc.queryForObject("select count(*) from day_skies where owner_id = ? and created_at is not null", Int::class.java, owner))
	}

	@Test
	fun `본문이 없으면 거절한다`() {
		assertFailsWith<DataIntegrityViolationException> { insert(body = null) }
		assertEquals(0, rows())
	}

	@Test
	fun `같은 소유자 · 차트 · 날짜는 한 행이고 차트나 날짜가 다르면 따로다`() {
		assertEquals(1, insert())
		assertFailsWith<DataIntegrityViolationException> { insert() }
		assertEquals(1, insert(chartHash = "other-chart"))
		assertEquals(1, insert(date = DAY.plusDays(1)))
		assertEquals(3, rows())
	}

	@Test
	fun `계정을 지우면 함께 지워진다`() {
		insert()
		insert(date = DAY.plusDays(1))
		jdbc.update("delete from accounts where id = ?", owner)
		assertEquals(0, rows())
	}

	@Test
	fun `V24 까지 돈 DB 도 올라가고 있던 행은 그대로다`() {
		val schema = "upgrade_from_v24"
		try {
			flyway(schema, target = "24").migrate()
			val old = UUID.randomUUID()
			jdbc.update("insert into $schema.accounts (id, provider, provider_user_id) values (?, 'GOOGLE', 'old')", old)
			jdbc.update("insert into $schema.readings (id, owner_id, kind, chart_hash, body, share_id) values (gen_random_uuid(), ?, 'SUMMARY_V2', 'h', '{}'::jsonb, 's')", old)
			flyway(schema, target = "latest").migrate()

			assertEquals(1, jdbc.queryForObject("select count(*) from $schema.readings", Int::class.java), "있던 행은 그대로")
			assertEquals(0, jdbc.queryForObject("select count(*) from $schema.day_skies", Int::class.java), "새 테이블은 비어 있다")
			val insert = "insert into $schema.day_skies (id, owner_id, chart_hash, date, body) values (gen_random_uuid(), ?, 'h', ?, '{}'::jsonb)"
			jdbc.update(insert, old, DAY)
			assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, old, DAY) }
		} finally {
			jdbc.execute("drop schema if exists $schema cascade")
		}
	}

	/** 같은 DB 의 [schema] 에 마이그레이션을 [target] 까지 돌린다. */
	private fun flyway(schema: String, target: String): Flyway =
		Flyway.configure().dataSource(dataSource).schemas(schema).target(target).load()
}
