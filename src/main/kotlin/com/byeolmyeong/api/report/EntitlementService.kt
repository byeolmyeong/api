package com.byeolmyeong.api.report

import org.slf4j.LoggerFactory
import com.byeolmyeong.api.web.RateLimiter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** RevenueCat 의 답을 계정마다 담아 두는 시간(결정 056 의 4). */
private val STORE_ANSWER_TTL: Duration = Duration.ofMinutes(1)

private const val MINUTE_MILLIS = 60_000L

/** 결제 직후 확인을 한 계정이 분당 한도보다 많이 불렀다(429 `RATE_LIMITED`, 결정 056 의 7). */
class ConfirmRateLimitedException : RuntimeException("RATE_LIMITED")

/** 상품을 가진 까닭(결정 056 의 2). 글자는 `entitlements.source` 와 같다. */
enum class EntitlementSource {
	/** 1.0.0 에서 받은 계정 — 결제 시작 전까지 서버가 채운다. */
	LEGACY,

	/** 스토어에서 산 계정 — RevenueCat 으로 확인했다. */
	STORE,
}

/**
 * 자세한 풀이 상품(결정 056, 앱 결정 101). 상품은 하나라 나 탭 · 시기를 가리지 않는다.
 * 1.0.0 수령자(`LEGACY`)는 결제 시작 시각 전에 받기를 누를 때([addLegacy] — `EntitlementPolicy` 가 부른다)와 [backfillLegacy](서버가 뜰 때 · 매시)로 적는다.
 */
@Service
class EntitlementService(
	private val repository: EntitlementRepository,
	private val start: PurchaseStart,
	private val revenueCat: RevenueCat,
	private val clock: Clock,
	@Value("\${byeolmyeong.rate-limit.entitlement-confirm-per-minute}") confirmPerMinute: Int,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	// 확인은 부를 때마다 RevenueCat 을 부르므로 계정마다 분당 수를 센다(서버 메모리).
	private val confirmLimiter = RateLimiter(confirmPerMinute, MINUTE_MILLIS, clock::millis)

	private class StoreAnswer(val owned: Boolean, val until: Instant)

	// RevenueCat 의 성공한 답만(서버 메모리 — 바꿀 때 비워진다).
	private val storeAnswers = ConcurrentHashMap<UUID, StoreAnswer>()

	/**
	 * 그 계정이 상품을 가졌나(결정 056 의 3 의 2 ~ 4): 결제 없이 여는 목록의 이메일 → `entitlements` 의 줄 → RevenueCat(계정마다 1분 담아 둔 답).
	 * RevenueCat 에 있으면 `STORE` 로 적는다(그 뒤로는 표만 본다). RevenueCat 을 묻지 못하면 `web.PurchaseCheckFailedException`.
	 */
	fun owns(ownerId: UUID): Boolean {
		if (repository.allowlisted(ownerId) || repository.has(ownerId)) return true
		if (!storeHas(ownerId)) return false
		recordStore(ownerId)
		return true
	}

	/**
	 * 결제 · 복원 직후의 확인(결정 056 의 7): RevenueCat 을 담아 둔 답 없이 묻고, 있으면 `STORE` 로 적는다. 스토어 구매만 본다 —
	 * 결제 없이 여는 계정 · `LEGACY` 라도 스토어 구매가 없으면 false. 묻지 못하면 `web.PurchaseCheckFailedException`.
	 * 계정마다 분당 `byeolmyeong.rate-limit.entitlement-confirm-per-minute` 번을 넘으면 묻지 않고 [ConfirmRateLimitedException].
	 */
	fun confirm(ownerId: UUID): Boolean {
		if (!confirmLimiter.tryAcquire(ownerId.toString())) {
			log.warn("event=entitlement_confirm_rate_limited")
			throw ConfirmRateLimitedException()
		}
		if (!askStore(ownerId)) return false
		recordStore(ownerId)
		return true
	}

	private fun storeHas(ownerId: UUID): Boolean =
		storeAnswers[ownerId]?.takeIf { it.until.isAfter(clock.instant()) }?.owned ?: askStore(ownerId)

	// RevenueCat 에 묻고 그 답을 1분 담아 둔다(지난 답은 이때 치운다). 묻지 못하면 던지고 아무것도 담지 않는다.
	private fun askStore(ownerId: UUID): Boolean {
		val owned = revenueCat.hasDetail(ownerId)
		val now = clock.instant()
		storeAnswers.values.removeIf { !it.until.isAfter(now) }
		storeAnswers[ownerId] = StoreAnswer(owned, now.plus(STORE_ANSWER_TTL))
		return owned
	}

	private fun recordStore(ownerId: UUID) {
		if (repository.add(ownerId, EntitlementSource.STORE)) log.info("event=entitlement_store_recorded")
	}

	/**
	 * 완성된 풀이 ∪ 감사 기록의 나 탭 · 시기 받기 가운데 결제 시작 시각 전의 것(완성한 때 · 누른 때)의 계정을 `LEGACY` 로 적는다(있으면 그대로).
	 * 시작 뒤에도 같은 조건으로 돈다. 마이그레이션이 아니라 여기서 하는 까닭: 첫 배포 때 새 jar 가 뜬 뒤에도 옛 jar 가 몇 분 받은 요청(감사 기록은 남는다)을
	 * 다음 번이 따라 적는다(결정 046 · 056 의 6). 두 서버가 함께 돌아도 결과가 같아 일정 잠금을 두지 않는다. 테스트는 일정을 "-" 로 끈다.
	 */
	@EventListener(ApplicationReadyEvent::class)
	@Scheduled(cron = "\${byeolmyeong.purchase.legacy-backfill-cron}", zone = "Asia/Seoul")
	fun backfillLegacy() {
		val added = repository.backfillLegacy(start.at)
		log.info("event=entitlement_legacy_backfilled added={}", added)
	}

	/** 결제 시작 전에 누른 받기의 계정을 `LEGACY` 로 적는다(있으면 그대로). 시작 전인지는 부르는 쪽(`EntitlementPolicy`)이 허용과 같은 판단으로 정한다. */
	fun addLegacy(ownerId: UUID) {
		repository.add(ownerId, EntitlementSource.LEGACY)
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

	/** 계정 이메일이 결제 없이 여는 목록에 있나(대소문자를 가리지 않는다 — 목록은 소문자). */
	fun allowlisted(accountId: UUID): Boolean = jdbc.queryForObject(
		"select exists(select 1 from accounts a join entitlement_allowlist l on l.email = lower(a.email) where a.id = ?)",
		Boolean::class.java, accountId,
	) == true

	/** 그 계정에 출처와 상관없이 줄이 있나. */
	fun has(accountId: UUID): Boolean = jdbc.queryForObject(
		"select exists(select 1 from entitlements where account_id = ?)", Boolean::class.java, accountId,
	) == true

	/**
	 * 완성된 풀이(`detail_reports` 의 DONE — 완성한 때 `updated_at`)와 감사 기록의 나 탭 · 시기 받기(`REPORT_REQUESTED`, kind ME · TIMING — 누른 때 `occurred_at`)
	 * 가운데 [before] 전의 것(null 이면 모두)의 계정을 `LEGACY` 로. 더한 줄 수.
	 */
	fun backfillLegacy(before: Instant?): Int {
		val until = Timestamp.from(before ?: Instant.parse("9999-12-31T00:00:00Z"))
		return jdbc.update(
			"""
			insert into entitlements (account_id, source)
			select id, 'LEGACY' from (
				select owner_id as id from detail_reports where status = 'DONE' and updated_at < ?
				union
				select a.id from audit_logs l join accounts a on a.id::text = l.actor_id
				where l.action = 'REPORT_REQUESTED' and l.actor_type = 'USER' and l.detail ->> 'kind' in ('ME', 'TIMING') and l.occurred_at < ?
			) receivers
			on conflict do nothing
			""".trimIndent(),
			until, until,
		)
	}
}
