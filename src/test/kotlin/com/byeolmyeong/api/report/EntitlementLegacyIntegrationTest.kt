package com.byeolmyeong.api.report

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** 결제 시작 시각(이 테스트의 설정). 한국 2026-12-10 00:00. */
private val STARTS_AT: Instant = Instant.parse("2026-12-09T15:00:00Z")

/**
 * 1.0.0 수령자(결정 056 의 6): 결제 시작 시각 전에는 완성된 풀이 ∪ 감사 기록의 나 탭 · 시기 받기 계정을 `LEGACY` 로 채우고,
 * 풀이가 완성될 때마다 더한다. 시작 시각부터는 둘 다 멈춘다. 시계는 가짜로 바꿔 끼운다.
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

	@Autowired
	private lateinit var events: ApplicationEventPublisher

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		jdbc.update("delete from audit_logs")
		clock.now = STARTS_AT.minusSeconds(3600)
	}

	private fun account(): UUID = UUID.randomUUID().also {
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", it, it.toString())
	}

	private fun report(owner: UUID, kind: String, status: String) {
		jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, ?, 'hash', ?, ?::jsonb)",
			UUID.randomUUID(), owner, kind, status, if (status == "DONE") "{}" else null,
		)
	}

	private fun audit(actorType: String, actorId: String, detail: String?) {
		jdbc.update(
			"insert into audit_logs (id, actor_type, actor_id, actor_label, action, outcome, detail) values (?, ?, ?, 'ref', 'REPORT_REQUESTED', 'SUCCESS', ?::jsonb)",
			UUID.randomUUID(), actorType, actorId, detail,
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
	fun `결제 시작 시각부터는 채우지 않는다`() {
		clock.now = STARTS_AT
		account().also { report(it, "ME", "DONE") }
		account().also { audit("USER", it.toString(), """{"kind":"TIMING"}""") }

		entitlements.backfillLegacy()

		assertEquals(emptyList(), rows())
	}

	@Test
	fun `시작 시각 전에 풀이가 완성되면 LEGACY 를 더하고 시작 뒤에 완성된 것은 더하지 않는다`() {
		val me = account()
		val timing = account()
		val late = account()

		events.publishEvent(MeReportDone(me))
		events.publishEvent(TimingReportDone(timing))
		events.publishEvent(MeReportDone(me))
		clock.now = STARTS_AT.plusSeconds(1)
		events.publishEvent(TimingReportDone(late))

		assertEquals(legacy(me, timing), rows())
	}

	@Test
	fun `완성된 사이 지워진 계정은 적지 않고 넘어간다`() {
		events.publishEvent(MeReportDone(UUID.randomUUID()))

		assertEquals(emptyList(), rows())
	}
}
