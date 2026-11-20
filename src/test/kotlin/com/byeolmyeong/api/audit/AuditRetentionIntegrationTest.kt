package com.byeolmyeong.api.audit

import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 감사 기록 보관 1년(결정 043 의 8): 날마다 한 번 1년(365일) 지난 행을 지운다. 일정을 기다리지 않고 그 메서드를 직접 부른다. */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AuditRetentionIntegrationTest {

	@Autowired
	private lateinit var audit: AuditService

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from audit_logs")
	}

	/** [age] 는 PostgreSQL interval 글자(예 `366 days`)다 — DB 시계의 지금에서 그만큼 앞에 일어난 행. */
	private fun seed(age: String): String {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into audit_logs (id, occurred_at, actor_type, action, outcome) values (?, now() - ?::interval, 'SYSTEM', 'ADMIN_LOGIN', 'FAILURE')",
			id, age,
		)
		return id.toString()
	}

	private fun ids(): Set<String> = jdbc.queryForList("select id::text from audit_logs", String::class.java).filterNotNull().toSet()

	@Test
	fun `1년(365일) 지난 행만 지우고 지운 개수를 로그에 남긴다`(output: CapturedOutput) {
		seed("400 days")
		seed("365 days 1 minute")
		val almost = seed("364 days 23 hours")
		val recent = seed("1 day")
		val now = seed("0 seconds")

		assertEquals(2, audit.deleteExpired())
		assertEquals(setOf(almost, recent, now), ids())
		assertTrue("event=audit_retention_deleted count=2" in output.out)

		assertEquals(0, audit.deleteExpired(), "다시 불러도(다른 서버가 같이 돌아도) 남은 것은 그대로다")
		assertEquals(setOf(almost, recent, now), ids())
		assertTrue("event=audit_retention_deleted count=0" in output.out)
	}

	@Test
	fun `날마다 한국 시각 04시 30분에 돈다`() {
		val scheduled = AuditService::class.java.getMethod("deleteExpired").getAnnotation(Scheduled::class.java)
		assertEquals("0 30 4 * * *", scheduled.cron)
		assertEquals("Asia/Seoul", scheduled.zone)
	}
}
