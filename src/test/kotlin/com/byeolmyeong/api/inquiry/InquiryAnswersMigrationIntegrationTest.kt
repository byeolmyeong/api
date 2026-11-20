package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import org.flywaydb.core.Flyway
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

private val NEW_COLUMNS = listOf("answer_body", "answered_at", "answered_by", "answer_draft", "app_platform", "app_version")

/**
 * 문의 v2 마이그레이션(결정 039): 답 · 임시 저장 · 보낸 앱 칸을 더하고 상태에 ANSWERED 를 더한다. 더하기만 한다(앞으로만 호환) —
 * 깨끗한 DB 와 V22 까지만 돈 DB(같은 DB 의 다른 스키마) 양쪽에서 본다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class InquiryAnswersMigrationIntegrationTest {

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var dataSource: DataSource

	@Test
	fun `새 칸은 모두 null 을 받는다`() {
		val nullable = jdbc.query(
			"select column_name, is_nullable from information_schema.columns where table_schema = 'public' and table_name = 'inquiries'",
			{ rs, _ -> rs.getString("column_name") to rs.getString("is_nullable") },
		).toMap()
		assertEquals(NEW_COLUMNS.associateWith { "YES" }, nullable.filterKeys { it in NEW_COLUMNS })
	}

	@Test
	fun `V22 까지 돈 DB 도 올라가고 있던 문의는 그대로다`() {
		val schema = "upgrade_inquiries_from_v22"
		try {
			flyway(schema, target = "22").migrate()
			val owner = UUID.randomUUID()
			jdbc.update("insert into $schema.accounts (id, provider, provider_user_id) values (?, 'GOOGLE', 'old')", owner)
			for ((category, status) in listOf("READING" to "NEW", "SYNASTRY" to "IN_PROGRESS", "OTHER" to "DONE")) {
				jdbc.update(
					"insert into $schema.inquiries (id, owner_id, category, body, reply_email, status) values (gen_random_uuid(), ?, ?, '옛 문의 글입니다 확인', 'me@example.com', ?)",
					owner, category, status,
				)
			}
			val before = jdbc.queryForList("select id, category, body, reply_email, status, created_at from $schema.inquiries order by category")

			flyway(schema, target = "latest").migrate()

			assertEquals(before, jdbc.queryForList("select id, category, body, reply_email, status, created_at from $schema.inquiries order by category"), "있던 행은 그대로")
			assertEquals(3, jdbc.queryForObject("select count(*) from $schema.inquiries where ${NEW_COLUMNS.joinToString(" and ") { "$it is null" }}", Int::class.java))
			jdbc.update("update $schema.inquiries set status = 'ANSWERED', answer_body = '답', answered_at = now() where category = 'READING'")
			assertFailsWith<DataIntegrityViolationException>("모르는 상태는 여전히 거절한다") {
				jdbc.update("update $schema.inquiries set status = 'CLOSED' where category = 'OTHER'")
			}
		} finally {
			jdbc.execute("drop schema if exists $schema cascade")
		}
	}

	/** 같은 DB 의 [schema] 에 마이그레이션을 [target] 까지 돌린다. */
	private fun flyway(schema: String, target: String): Flyway =
		Flyway.configure().dataSource(dataSource).schemas(schema).target(target).load()
}
