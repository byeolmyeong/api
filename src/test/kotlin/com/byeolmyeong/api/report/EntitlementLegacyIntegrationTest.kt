package com.byeolmyeong.api.report

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** 결제 시작 시각(이 테스트의 설정). 한국 2026-12-10 00:00. */
private val STARTS_AT: Instant = Instant.parse("2026-12-09T15:00:00Z")

/** 시작 1초 전 · 1초 뒤(행의 때). */
private val BEFORE_START: Instant = STARTS_AT.minusSeconds(1)
private val AFTER_START: Instant = STARTS_AT.plusSeconds(1)

/**
 * 1.0.0 수령자 채우기(결정 056 의 6): 완성된 풀이 ∪ 감사 기록의 나 탭 · 시기 받기 가운데 **결제 시작 시각 전의 것**의 계정을 `LEGACY` 로 채운다.
 * 시작 뒤에도 같은 조건으로 돈다. 받기를 누를 때 적는 것은 `EntitlementCheckIntegrationTest`. 시계는 가짜로 바꿔 끼우고, 행의 때는 직접 적는다.
 */
@Import(TestcontainersConfiguration::class, EntitlementLegacyIntegrationTest.Fakes::class)
@SpringBootTest(properties = ["byeolmyeong.purchase.starts-at=2026-12-10T00:00:00+09:00"])
class EntitlementLegacyIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class Fakes {
		@Bean
		@Primary
		fun entitlementClock() = MutableClock(STARTS_AT.minusSeconds(3600))
	}

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var clock: MutableClock

	@Autowired
	private lateinit var entitlements: EntitlementService

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		jdbc.update("delete from audit_logs")
		clock.now = STARTS_AT.minusSeconds(3600)
	}

	private fun account(): UUID = UUID.randomUUID().also {
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", it, it.toString())
	}

	/** [at] 은 상태가 마지막으로 바뀐 때(`updated_at` — 완성이면 완성한 때). */
	private fun report(owner: UUID, kind: String, status: String, at: Instant = BEFORE_START) {
		jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, body, updated_at) values (?, ?, ?, 'hash', ?, ?::jsonb, ?)",
			UUID.randomUUID(), owner, kind, status, if (status == "DONE") "{}" else null, Timestamp.from(at),
		)
	}

	private fun audit(actorType: String, actorId: String, detail: String?, at: Instant = BEFORE_START) {
		jdbc.update(
			"insert into audit_logs (id, occurred_at, actor_type, actor_id, actor_label, action, outcome, detail) values (?, ?, ?, ?, 'ref', 'REPORT_REQUESTED', 'SUCCESS', ?::jsonb)",
			UUID.randomUUID(), Timestamp.from(at), actorType, actorId, detail,
		)
	}

	private fun rows(): List<String> =
		jdbc.queryForList("select account_id || ' ' || source from entitlements order by 1", String::class.java).filterNotNull()

	private fun legacy(vararg owners: UUID): List<String> = owners.map { "$it LEGACY" }.sorted()

	@Test
	fun `배포 때 채우기 - 완성된 풀이와 감사 기록의 나 탭 · 시기 받기 계정을 LEGACY 로 적고 다시 불러도 한 줄이다`() {
		val doneMe = account().also { report(it, "ME", "DONE") }
		val doneTiming = account().also { report(it, "TIMING", "DONE") }
		val requestedMe = account().also { audit("USER", it.toString(), """{"kind":"ME"}""") }
		val requestedTiming = account().also { audit("USER", it.toString(), """{"kind":"TIMING"}""") }
		// 받기로 치지 않는 것: 쓰는 중 · 실패만 있음, 옛 심층 리포트의 받기(kind 없음), 지워진 계정의 기록
		account().also { report(it, "ME", "WRITING") }
		account().also { report(it, "TIMING", "FAILED") }
		account().also { audit("USER", it.toString(), null) }
		audit("USER", UUID.randomUUID().toString(), """{"kind":"ME"}""")
		// 감사 기록과 완성된 풀이가 둘 다 있어도 한 줄
		audit("USER", doneMe.toString(), """{"kind":"ME"}""")

		entitlements.backfillLegacy()
		entitlements.backfillLegacy()

		assertEquals(legacy(doneMe, doneTiming, requestedMe, requestedTiming), rows())

		// 계정을 지우면 함께 지워진다
		jdbc.update("delete from accounts where id = ?", doneMe)
		assertEquals(legacy(doneTiming, requestedMe, requestedTiming), rows())
	}

	@Test
	fun `결제 시작 뒤에도 돌되 시작 전에 누른 받기 · 시작 전에 완성된 풀이의 계정만 채운다`() {
		clock.now = AFTER_START.plusSeconds(3600)
		val requestedBefore = account().also { audit("USER", it.toString(), """{"kind":"ME"}""", BEFORE_START) }
		val doneBefore = account().also { report(it, "TIMING", "DONE", BEFORE_START) }
		// 시작 전에 누르고 시작 뒤에 완성된 풀이 — 감사 기록(누른 때)으로 들어온다
		val requestedBeforeDoneAfter = account().also {
			audit("USER", it.toString(), """{"kind":"TIMING"}""", BEFORE_START)
			report(it, "TIMING", "DONE", AFTER_START)
		}
		account().also { audit("USER", it.toString(), """{"kind":"ME"}""", AFTER_START) }
		account().also { report(it, "ME", "DONE", AFTER_START) }

		entitlements.backfillLegacy()

		assertEquals(legacy(requestedBefore, doneBefore, requestedBeforeDoneAfter), rows())
	}
}
