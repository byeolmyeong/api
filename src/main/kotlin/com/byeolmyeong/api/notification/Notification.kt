package com.byeolmyeong.api.notification

import com.byeolmyeong.api.admin.InquiryAnswered
import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.reading.ReadingCreated
import com.byeolmyeong.api.report.MeReportDone
import com.byeolmyeong.api.report.TimingReportDone
import com.byeolmyeong.api.synastry.SynastryReportDone
import com.byeolmyeong.api.web.NotFoundException
import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 보이는 기간(결정 035 의 3 · 5). */
private const val KEEP_DAYS = 30

/** 한 번에 주는 최대 수. */
private const val LIST_MAX = 100

/** 알림 종류(결정 035 의 1 · 앱 계획의 INQUIRY_ANSWERED). 문구는 앱이 갖는다 — 서버는 종류와 대상만 준다. */
enum class NotificationKind { READING_DONE, ME_REPORT_DONE, TIMING_REPORT_DONE, SYNASTRY_REPORT_DONE, INQUIRY_ANSWERED }

/** 알림 한 줄. [synastryId] · [inquiryId] 는 그 종류일 때만 있고, 없으면 응답에서 뺀다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NotificationItem(
	val id: UUID,
	val kind: NotificationKind,
	val createdAt: String,
	val read: Boolean,
	val synastryId: UUID? = null,
	val inquiryId: UUID? = null,
)

/** `GET /notifications`. [unread] 는 30일 안의 안 읽은 수(홈의 종 점). */
data class NotificationList(val unread: Int, val items: List<NotificationItem>)

/** 알림함. 누구의 것인지는 세션에서만 얻는다. */
@RestController
class NotificationController(private val notifications: NotificationService, private val owner: CurrentOwner) {

	@GetMapping("/notifications")
	fun list(): NotificationList = notifications.list(owner.id())

	/** 남의 것 · 없는 것 · UUID 가 아닌 값은 404 `NOT_FOUND`. 이미 읽었어도 204. */
	@PostMapping("/notifications/{id}/read")
	fun read(@PathVariable id: String): ResponseEntity<Void> {
		val notificationId = runCatching { UUID.fromString(id) }.getOrNull()?.takeIf { it.toString() == id.lowercase() } ?: throw NotFoundException("NOT_FOUND")
		notifications.read(owner.id(), notificationId)
		return ResponseEntity.noContent().build()
	}
}

/**
 * 완성 이벤트를 듣고(커밋 뒤) 한 줄을 넣는다(결정 035 의 4). 넣기가 실패해도 이벤트를 낸 일은 그대로 끝난다 — 실패는 구조화 로그만.
 * 이벤트에는 글 · 출생 정보가 없다.
 */
@Service
class NotificationService(private val repository: NotificationRepository, transactionManager: PlatformTransactionManager) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 커밋 뒤에 듣는 넣기는 자기 트랜잭션에서 한다(결정 035 의 4 — 앞 트랜잭션은 이미 끝났다). */
	private val ownTransaction = TransactionTemplate(transactionManager).apply {
		propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
	}

	fun list(ownerId: UUID): NotificationList =
		NotificationList(repository.unread(ownerId, KEEP_DAYS), repository.recent(ownerId, KEEP_DAYS, LIST_MAX))

	fun read(ownerId: UUID, id: UUID) {
		if (!repository.markRead(ownerId, id)) throw NotFoundException("NOT_FOUND")
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun on(event: ReadingCreated) = add(event.ownerId, NotificationKind.READING_DONE)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun on(event: MeReportDone) = add(event.ownerId, NotificationKind.ME_REPORT_DONE)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun on(event: TimingReportDone) = add(event.ownerId, NotificationKind.TIMING_REPORT_DONE)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun on(event: SynastryReportDone) = add(event.ownerId, NotificationKind.SYNASTRY_REPORT_DONE, synastryId = event.synastryId)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun on(event: InquiryAnswered) = add(event.ownerId, NotificationKind.INQUIRY_ANSWERED, inquiryId = event.inquiryId)

	/**
	 * 날마다 한국 시간 04:00 에 30일 지난 줄을 지운다(결정 035 의 5). 읽음과 상관없다. 같은 지우기를 서버 두 대가 함께 해도
	 * 결과가 같아(지울 것이 없으면 0) 잠그지 않는다.
	 */
	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	fun deleteExpired() {
		val count = repository.deleteOlderThan(KEEP_DAYS)
		log.info("event=notifications_expired_deleted count={}", count)
	}

	private fun add(ownerId: UUID, kind: NotificationKind, synastryId: UUID? = null, inquiryId: UUID? = null) {
		try {
			ownTransaction.executeWithoutResult { repository.insert(ownerId, kind, synastryId, inquiryId) }
		} catch (e: RuntimeException) {
			// 그 사이 계정 · 궁합 · 문의가 지워졌으면 외래 키에 걸린다. 알림이 없어도 되는 경우다.
			log.warn("event=notification_insert_failed kind={} reason={}", kind, e.javaClass.simpleName)
		}
	}
}

@Repository
class NotificationRepository(private val jdbc: JdbcTemplate) {

	fun insert(ownerId: UUID, kind: NotificationKind, synastryId: UUID?, inquiryId: UUID?) {
		jdbc.update(
			"insert into notifications (id, owner_id, kind, synastry_id, inquiry_id) values (?, ?, ?, ?, ?)",
			UUID.randomUUID(), ownerId, kind.name, synastryId, inquiryId,
		)
	}

	fun recent(ownerId: UUID, days: Int, limit: Int): List<NotificationItem> = jdbc.query(
		"""
		select id, kind, created_at, read_at, synastry_id, inquiry_id from notifications
		where owner_id = ? and created_at > now() - make_interval(days => ?)
		order by created_at desc, id desc limit ?
		""".trimIndent(),
		{ rs, _ ->
			NotificationItem(
				id = rs.getObject("id", UUID::class.java),
				kind = NotificationKind.valueOf(rs.getString("kind")),
				createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
				read = rs.getObject("read_at") != null,
				synastryId = rs.getObject("synastry_id", UUID::class.java),
				inquiryId = rs.getObject("inquiry_id", UUID::class.java),
			)
		},
		ownerId, days, limit,
	)

	fun unread(ownerId: UUID, days: Int): Int = jdbc.queryForObject(
		"select count(*) from notifications where owner_id = ? and read_at is null and created_at > now() - make_interval(days => ?)",
		Int::class.java, ownerId, days,
	)!!

	/** 내 것이면 true(이미 읽었어도). 읽은 때는 처음 누른 때 그대로다. */
	fun markRead(ownerId: UUID, id: UUID): Boolean =
		jdbc.update("update notifications set read_at = coalesce(read_at, now()) where id = ? and owner_id = ?", id, ownerId) == 1

	fun deleteOlderThan(days: Int): Int = jdbc.update("delete from notifications where created_at <= now() - make_interval(days => ?)", days)
}
