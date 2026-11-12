package com.byeolmyeong.api.audit

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.ownerRef
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 감사 기록의 듣는 쪽(결정 015): 이벤트 하나가 행 하나가 되고, 기록의 실패와 되돌려진 일이 어떻게 되는지. */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AuditRecordIntegrationTest {

	@Autowired
	private lateinit var events: ApplicationEventPublisher

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var transactions: TransactionTemplate

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from audit_logs")
	}

	private fun rows(): List<Map<String, Any?>> =
		jdbc.queryForList("select actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail::text as detail, occurred_at from audit_logs")

	@Test
	fun `이벤트 하나가 행 하나가 된다 - 관리자`() {
		val adminId = UUID.randomUUID()
		val targetId = UUID.randomUUID()
		events.publishEvent(AuditEvent(AuditAction.INQUIRY_UPDATED, AuditActor.admin(adminId, "root-admin"), "INQUIRY", targetId.toString(), detail = mapOf("status" to "DONE", "memoChanged" to true)))

		val row = rows().single()
		assertEquals("ADMIN", row["actor_type"])
		assertEquals(adminId.toString(), row["actor_id"])
		assertEquals("root-admin", row["actor_label"])
		assertEquals("INQUIRY_UPDATED", row["action"])
		assertEquals("INQUIRY", row["target_type"])
		assertEquals(targetId.toString(), row["target_id"])
		assertEquals("SUCCESS", row["outcome"])
		assertEquals("""{"status": "DONE", "memoChanged": true}""", row["detail"])
		assertNotNull(row["occurred_at"])
	}

	@Test
	fun `사용자의 표시는 ownerRef 이고 덧붙임이 없으면 null 이다`() {
		val accountId = UUID.randomUUID()
		events.publishEvent(AuditEvent(AuditAction.READING_GENERATED, AuditActor.user(accountId), success = false))

		val row = rows().single()
		assertEquals("USER", row["actor_type"])
		assertEquals(accountId.toString(), row["actor_id"])
		assertEquals(ownerRef(accountId), row["actor_label"])
		assertEquals("FAILURE", row["outcome"])
		assertNull(row["target_type"])
		assertNull(row["target_id"])
		assertNull(row["detail"])
	}

	@Test
	fun `SYSTEM 은 행위자가 비어 있다`() {
		events.publishEvent(AuditEvent(AuditAction.ADMIN_LOGIN, AuditActor.SYSTEM, success = false))

		val row = rows().single()
		assertEquals("SYSTEM", row["actor_type"])
		assertNull(row["actor_id"])
		assertNull(row["actor_label"])
	}

	@Test
	fun `코드 모양이 아닌 덧붙임은 통째로 버리고 행은 남긴다`(output: CapturedOutput) {
		val actor = AuditActor.user(UUID.randomUUID())
		for (bad in listOf<Any>("한글로 쓴 글", "someone@example.com", "a".repeat(41), "두 낱말 text", "", 1.5, listOf("A"), mapOf("a" to "b"))) {
			events.publishEvent(AuditEvent(AuditAction.INQUIRY_SUBMITTED, actor, detail = mapOf("category" to "BUG", "x" to bad)))
		}

		assertEquals(8, rows().size)
		assertTrue(rows().all { it["detail"] == null })
		assertTrue("event=audit_detail_dropped action=INQUIRY_SUBMITTED" in output.out)
		assertFalse("someone@example.com" in output.out)
		assertFalse("한글로 쓴 글" in output.out)
	}

	@Test
	fun `되돌려진 트랜잭션의 이벤트는 기록되지 않고 커밋된 것은 기록된다`() {
		val actor = AuditActor.user(UUID.randomUUID())
		transactions.executeWithoutResult { status ->
			events.publishEvent(AuditEvent(AuditAction.SHARE_CREATED, actor))
			status.setRollbackOnly()
		}
		assertEquals(0, rows().size)

		transactions.executeWithoutResult {
			events.publishEvent(AuditEvent(AuditAction.SHARE_DELETED, actor))
			// 커밋 전에는 아직 없다.
			assertEquals(0, jdbc.queryForObject("select count(*) from audit_logs", Int::class.java))
		}
		assertEquals("SHARE_DELETED", rows().single()["action"])
	}

	@Test
	fun `기록이 실패해도 이벤트를 낸 쪽은 예외를 받지 않고 로그에 코드만 남는다`(output: CapturedOutput) {
		val accountId = UUID.randomUUID()
		jdbc.execute("alter table audit_logs rename to audit_logs_away")
		try {
			// 트랜잭션 밖과 안 둘 다: 낸 쪽의 일은 그대로 끝난다.
			events.publishEvent(AuditEvent(AuditAction.PROFILE_SAVED, AuditActor.user(accountId), detail = mapOf("change" to "CREATED")))
			val kept = transactions.execute {
				jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'TEST', 'audit-fail')", accountId)
				events.publishEvent(AuditEvent(AuditAction.USER_LOGIN, AuditActor.user(accountId)))
				"done"
			}
			assertEquals("done", kept)
		} finally {
			jdbc.execute("alter table audit_logs_away rename to audit_logs")
		}

		assertEquals(1, jdbc.queryForObject("select count(*) from accounts where id = ?", Int::class.java, accountId))
		jdbc.update("delete from accounts where id = ?", accountId)
		assertEquals(0, rows().size)
		assertTrue("event=audit_record_failed action=PROFILE_SAVED" in output.out)
		assertTrue("event=audit_record_failed action=USER_LOGIN" in output.out)
		assertFalse(accountId.toString() in output.out)
		assertFalse(ownerRef(accountId) in output.out)
	}
}
