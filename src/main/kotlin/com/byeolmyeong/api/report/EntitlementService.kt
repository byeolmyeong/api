package com.byeolmyeong.api.report

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.util.UUID

/** 상품을 가진 까닭(결정 056 의 2). 글자는 `entitlements.source` 와 같다. */
enum class EntitlementSource {
	/** 1.0.0 에서 받은 계정 — 결제 시작 전까지 서버가 채운다. */
	LEGACY,

	/** 스토어에서 산 계정 — RevenueCat 으로 확인했다. */
	STORE,
}

/**
 * 자세한 풀이 상품(결정 056, 앱 결정 101). 상품은 하나라 나 탭 · 시기를 가리지 않는다.
 * 1.0.0 수령자는 결제 시작 시각 전까지 [backfillLegacy](서버가 뜰 때 · 매시)와 풀이 완성 이벤트로 `LEGACY` 에 적는다.
 */
@Service
class EntitlementService(private val repository: EntitlementRepository, private val start: PurchaseStart) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * 완성된 풀이 ∪ 감사 기록의 나 탭 · 시기 받기 계정을 `LEGACY` 로 적는다(있으면 그대로). 결제 시작 시각부터는 하지 않는다.
	 * 마이그레이션이 아니라 여기서 하는 까닭: 첫 배포 때 새 jar 가 뜬 뒤에도 옛 jar 가 몇 분 받은 요청을 다음 번이 따라 적는다(결정 046 · 056 의 6).
	 * 두 서버가 함께 돌아도 결과가 같아 일정 잠금을 두지 않는다.
	 */
	@EventListener(ApplicationReadyEvent::class)
	@Scheduled(cron = "0 10 * * * *", zone = "Asia/Seoul")
	fun backfillLegacy() {
		if (start.started()) return
		val added = repository.backfillLegacy()
		log.info("event=entitlement_legacy_backfilled added={}", added)
	}

	@EventListener
	fun on(event: MeReportDone) = addLegacy(event.ownerId)

	@EventListener
	fun on(event: TimingReportDone) = addLegacy(event.ownerId)

	// 완성 이벤트를 내는 일꾼 스레드에서 돈다. 여기서 던지면 뒤의 리스너(알림함 · 푸시)가 불리지 않으므로 삼키고 로그만 남긴다.
	private fun addLegacy(ownerId: UUID) {
		if (start.started()) return
		try {
			repository.add(ownerId, EntitlementSource.LEGACY)
		} catch (e: Exception) {
			log.warn("event=entitlement_legacy_failed error={}", e.javaClass.simpleName)
		}
	}
}

/** `entitlements`(V36, 결정 056 의 2). */
@Repository
class EntitlementRepository(private val jdbc: JdbcTemplate) {

	/** 그 계정에 [source] 줄을 더한다. 이미 있거나 계정이 없으면 아무것도 하지 않는다. 더했으면 true. */
	fun add(accountId: UUID, source: EntitlementSource): Boolean = jdbc.update(
		"insert into entitlements (account_id, source) select id, ? from accounts where id = ? on conflict do nothing",
		source.name, accountId,
	) == 1

	/** 완성된 풀이(`detail_reports` 의 DONE)와 감사 기록의 나 탭 · 시기 받기(`REPORT_REQUESTED`, kind ME · TIMING)의 계정을 `LEGACY` 로. 더한 줄 수. */
	fun backfillLegacy(): Int = jdbc.update(
		"""
		insert into entitlements (account_id, source)
		select id, 'LEGACY' from (
			select owner_id as id from detail_reports where status = 'DONE'
			union
			select a.id from audit_logs l join accounts a on a.id::text = l.actor_id
			where l.action = 'REPORT_REQUESTED' and l.actor_type = 'USER' and l.detail ->> 'kind' in ('ME', 'TIMING')
		) receivers
		on conflict do nothing
		""".trimIndent(),
	)
}
