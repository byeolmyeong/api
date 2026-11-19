package com.byeolmyeong.api.auth

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

/** 결정 026(약관) · 027(푸시) · 028(프로필) · V21 의 계정 칸. 모두 null 허용(옛 계정)이고 있던 행은 채우지 않는다. */
private val V21_COLUMNS = mapOf(
	"terms_version" to "text",
	"terms_agreed_at" to "timestamp with time zone",
	"privacy_version" to "text",
	"privacy_agreed_at" to "timestamp with time zone",
	"push_consented_at" to "timestamp with time zone",
	"avatar" to "smallint",
)

/**
 * V21(결정 026 · 027 · 028): 약관 · 처리방침 버전과 동의 시각, 푸시 수신 동의 일시, 프로필 번호. 깨끗한 DB(테스트 서버가 띄운 DB)와
 * V19 · V20 까지만 돈 DB(같은 DB 의 다른 스키마) 양쪽에서 돈다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class TermsPushAvatarMigrationIntegrationTest {

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var dataSource: DataSource

	private fun accountColumns(schema: String): Map<String, Pair<String, String>> = jdbc.query(
		"select column_name, data_type, is_nullable from information_schema.columns where table_schema = ? and table_name = 'accounts'",
		{ rs, _ -> rs.getString("column_name") to (rs.getString("data_type") to rs.getString("is_nullable")) },
		schema,
	).toMap()

	@Test
	fun `깨끗한 DB 에 약관 · 푸시 · 프로필 칸이 null 허용으로 생기고 프로필은 1~8 만 받는다`() {
		val columns = accountColumns("public")
		for ((name, type) in V21_COLUMNS) assertEquals(type to "YES", columns[name], name)

		val id = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'GOOGLE', ?)", id, "migration-$id")
		for (ok in listOf(1, 8)) assertEquals(1, jdbc.update("update accounts set avatar = ? where id = ?", ok, id))
		for (bad in listOf(0, 9, -1)) {
			assertFailsWith<DataIntegrityViolationException>("avatar $bad") { jdbc.update("update accounts set avatar = ? where id = ?", bad, id) }
		}
		jdbc.update("delete from accounts where id = ?", id)
	}

	@Test
	fun `V19 · V20 까지 돈 DB 도 올라가고, 있던 계정의 새 칸은 비어 있다(채우지 않는다)`() {
		for (from in listOf("19", "20")) {
			val schema = "upgrade_from_v$from"
			try {
				flyway(schema, target = from).migrate()
				jdbc.update("insert into $schema.accounts (id, provider, provider_user_id) values (gen_random_uuid(), 'GOOGLE', 'old')")
				flyway(schema, target = "latest").migrate()

				assertEquals(V21_COLUMNS.keys, accountColumns(schema).keys.intersect(V21_COLUMNS.keys), "V$from 에서 올라간 칸")
				val row = jdbc.queryForMap("select ${V21_COLUMNS.keys.joinToString()} from $schema.accounts")
				assertEquals(V21_COLUMNS.keys.associateWith { null }, row.toMap(), "V$from 의 옛 계정")
			} finally {
				jdbc.execute("drop schema if exists $schema cascade")
			}
		}
	}

	/** 같은 DB 의 [schema] 에 마이그레이션을 [target] 까지 돌린다. */
	private fun flyway(schema: String, target: String): Flyway =
		Flyway.configure().dataSource(dataSource).schemas(schema).target(target).load()
}
