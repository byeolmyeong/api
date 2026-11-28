package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** 공지의 갈래(결정 055 의 1). */
internal const val NOTICE_CATEGORY_NOTICE = "NOTICE"
internal const val NOTICE_CATEGORY_UPDATE = "UPDATE"

/**
 * 관리자가 보는 공지. [status] 는 `DRAFT`(임시 저장) · `PUBLISHED`(공개), [publishedAt] 은 공개 중일 때만 있다.
 * [notify] 는 알림 보내기 스위치, [notifyStartedAt] 은 알림 보내기를 시작한 때(한 번만).
 * [push] · [inbox] 는 보낸 결과 — 그 일이 끝나기 전(또는 알림을 보내지 않았으면) null.
 */
data class AdminNotice(
	val id: UUID,
	val category: String,
	val title: String,
	val body: String,
	val status: String,
	val publishedAt: String?,
	val notify: Boolean,
	val notifyStartedAt: String?,
	val push: NoticePushResult?,
	val inbox: NoticeInboxResult?,
	val createdAt: String,
	val createdBy: AdminRef?,
	val updatedAt: String,
	val updatedBy: AdminRef?,
)

/** 공지 알림의 푸시 결과(기기 수로 센다): 대상 · Expo 가 받음 · 실패 · 보내지 않음. */
data class NoticePushResult(val devices: Int, val sent: Int, val failed: Int, val skipped: Int)

/** 공지 알림의 알림함 결과: 넣은 줄 수 · 넣기가 실패했는지. */
data class NoticeInboxResult(val added: Int, val failed: Boolean)

/** `GET /admin/notices/audience`: 푸시를 받을 사람 수. */
data class NoticeAudience(val count: Int)

/** 고치기 · 공개를 판정할 때 잠그고 읽는 칸. */
data class NoticeState(val category: String, val notify: Boolean, val notifyStarted: Boolean)

/**
 * 공지사항 편집(결정 055 의 3 · 4). 바꾼 관리자와 그때를 적고, 한 일은 감사 기록에 남긴다(글은 넣지 않는다).
 * "업데이트" 갈래는 알림 보내기를 켤 수 없다(400 `NOTIFY_NOT_ALLOWED`). 이미 알림을 보낸 글의 스위치는 켜진 채로 남는다.
 */
@Service
class AdminNoticeService(
	private val repository: AdminNoticeRepository,
	private val events: ApplicationEventPublisher,
	private val audit: AdminAudit,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun list(): List<AdminNotice> = repository.findAll()

	fun get(id: UUID): AdminNotice = repository.findById(id) ?: throw NotFoundException("NOT_FOUND")

	/** 푸시를 받을 사람 수: 푸시 동의 + 정지 아님 + 기기 하나 이상(결정 055 의 3). 알림함 줄은 모든 계정에 간다. */
	fun audience(): Int = repository.pushAudience()

	fun create(adminId: UUID, fields: NoticeFields, notify: Boolean): AdminNotice {
		requireNotifiable(fields.category, notify)
		val id = repository.insert(adminId, fields, notify)
		log.info("event=admin_notice_created category={} notify={}", fields.category, notify)
		audit.record(AuditAction.NOTICE_CREATED, "NOTICE", id, mapOf("category" to fields.category, "notify" to notify))
		return repository.findById(id)!!
	}

	@Transactional
	fun update(id: UUID, adminId: UUID, fields: NoticeFields): AdminNotice {
		val state = repository.lock(id) ?: throw NotFoundException("NOT_FOUND")
		val notify = state.notifyStarted || (fields.notify ?: state.notify)
		requireNotifiable(fields.category, notify)
		repository.update(id, adminId, fields, notify)
		log.info("event=admin_notice_updated")
		audit.record(AuditAction.NOTICE_UPDATED, "NOTICE", id)
		return repository.findById(id)!!
	}

	/**
	 * 공개한다(이미 공개 중이면 공개 시각은 그대로). [notify] 가 참이고 아직 보내지 않았으면 `notify_started_at` 을 먼저 적은 이 요청만
	 * `NoticePublished` 를 낸다 — 동시에 눌러도 · 내렸다 다시 공개해도 한 번이다. 이벤트는 커밋된 뒤에 듣는다.
	 */
	@Transactional
	fun publish(id: UUID, adminId: UUID, notify: Boolean): AdminNotice {
		val state = repository.lock(id) ?: throw NotFoundException("NOT_FOUND")
		requireNotifiable(state.category, notify)
		repository.publish(id, adminId, state.notifyStarted || notify)
		val started = notify && repository.markNotifyStarted(id)
		if (started) events.publishEvent(NoticePublished(id))
		log.info("event=admin_notice_published notify={} notify_started={}", notify, started)
		audit.record(AuditAction.NOTICE_PUBLISHED, "NOTICE", id, mapOf("notify" to notify, "notifyStarted" to started))
		return repository.findById(id)!!
	}

	/** 임시 저장으로 내린다(앱 목록에서 빠진다). 알림함 줄은 남는다. */
	fun unpublish(id: UUID, adminId: UUID): AdminNotice {
		if (!repository.unpublish(id, adminId)) throw NotFoundException("NOT_FOUND")
		log.info("event=admin_notice_unpublished")
		audit.record(AuditAction.NOTICE_UNPUBLISHED, "NOTICE", id)
		return repository.findById(id) ?: throw NotFoundException("NOT_FOUND")
	}

	/** 지운다. 그 공지의 알림함 줄도 함께 지워진다(`notifications.notice_id` cascade). */
	fun delete(id: UUID) {
		if (!repository.delete(id)) throw NotFoundException("NOT_FOUND")
		log.info("event=admin_notice_deleted")
		audit.record(AuditAction.NOTICE_DELETED, "NOTICE", id)
	}

	/**
	 * `push` 가 다 보냈다 — 그 공지에 푸시 결과를 적는다(결정 055 의 4). 일꾼 스레드에서도, 커밋 뒤 콜백에서도 오므로 자기 트랜잭션에서 쓴다.
	 * 그 사이 공지가 지워졌으면 적을 행이 없다.
	 */
	@EventListener
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun onPushSent(event: NoticePushSent) {
		repository.recordPush(event.noticeId, event.devices, event.sent, event.failed, event.skipped)
	}

	/** `notification` 이 알림함에 넣었다(또는 실패했다) — 그 공지에 적는다. */
	@EventListener
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun onInboxAdded(event: NoticeInboxAdded) {
		repository.recordInbox(event.noticeId, event.added, event.failed)
	}

	private fun requireNotifiable(category: String, notify: Boolean) {
		if (notify && category == NOTICE_CATEGORY_UPDATE) throw BadRequestException("NOTIFY_NOT_ALLOWED")
	}
}

@Repository
class AdminNoticeRepository(private val jdbc: JdbcTemplate) {

	private val select = """
		select n.id, n.category, n.title, n.body, n.status, n.published_at, n.notify, n.notify_started_at,
			n.push_devices, n.push_sent, n.push_failed, n.push_skipped, n.inbox_added, n.inbox_failed,
			n.created_at, n.created_by, c.name as creator_name, n.updated_at, n.updated_by, u.name as updater_name
		from notices n left join admin_accounts c on c.id = n.created_by left join admin_accounts u on u.id = n.updated_by
	"""

	private val row = RowMapper { rs, _ ->
		AdminNotice(
			id = rs.getObject("id", UUID::class.java),
			category = rs.getString("category"),
			title = rs.getString("title"),
			body = rs.getString("body"),
			status = rs.getString("status"),
			publishedAt = rs.getObject("published_at", OffsetDateTime::class.java)?.utcSeconds(),
			notify = rs.getBoolean("notify"),
			notifyStartedAt = rs.getObject("notify_started_at", OffsetDateTime::class.java)?.utcSeconds(),
			push = (rs.getObject("push_devices") as Number?)?.let {
				NoticePushResult(it.toInt(), rs.getInt("push_sent"), rs.getInt("push_failed"), rs.getInt("push_skipped"))
			},
			inbox = (rs.getObject("inbox_added") as Number?)?.let { NoticeInboxResult(it.toInt(), rs.getBoolean("inbox_failed")) },
			createdAt = rs.getObject("created_at", OffsetDateTime::class.java).utcSeconds(),
			createdBy = rs.getObject("created_by", UUID::class.java)?.let { AdminRef(it, rs.getString("creator_name")) },
			updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).utcSeconds(),
			updatedBy = rs.getObject("updated_by", UUID::class.java)?.let { AdminRef(it, rs.getString("updater_name")) },
		)
	}

	/** 새것부터: 공개 중이면 공개 시각, 아니면 만든 때. */
	fun findAll(): List<AdminNotice> = jdbc.query("$select order by coalesce(n.published_at, n.created_at) desc, n.id desc", row)

	fun findById(id: UUID): AdminNotice? = jdbc.query("$select where n.id = ?", row, id).firstOrNull()

	/** 다른 기능의 표(`accounts` · `push_devices`)를 자기 SQL 로 센다(결정 014). push 의 서비스 알림 대상과 같은 조건이다. */
	fun pushAudience(): Int = jdbc.queryForObject(
		"""
		select count(*) from accounts a
		where a.push_consented_at is not null and a.suspended_at is null
		  and exists (select 1 from push_devices d where d.account_id = a.id)
		""",
		Int::class.java,
	)!!

	fun insert(adminId: UUID, fields: NoticeFields, notify: Boolean): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into notices (id, category, title, body, notify, created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?)",
			id, fields.category, fields.title, fields.body, notify, adminId, adminId,
		)
		return id
	}

	/** 그 공지를 이 트랜잭션이 끝날 때까지 잠그고 판정에 쓸 칸을 읽는다. 없으면 null. */
	fun lock(id: UUID): NoticeState? =
		jdbc.query("select category, notify, notify_started_at from notices where id = ? for update", { rs, _ ->
			NoticeState(rs.getString("category"), rs.getBoolean("notify"), rs.getObject("notify_started_at") != null)
		}, id).firstOrNull()

	fun update(id: UUID, adminId: UUID, fields: NoticeFields, notify: Boolean) {
		jdbc.update(
			"update notices set category = ?, title = ?, body = ?, notify = ?, updated_at = now(), updated_by = ? where id = ?",
			fields.category, fields.title, fields.body, notify, adminId, id,
		)
	}

	fun publish(id: UUID, adminId: UUID, notify: Boolean) {
		jdbc.update(
			"""
			update notices set status = 'PUBLISHED', published_at = coalesce(published_at, now()), notify = ?, updated_at = now(), updated_by = ?
			where id = ?
			""",
			notify, adminId, id,
		)
	}

	/** 아직 시작하지 않았으면 보내기를 시작한 때를 적고 true. 이미 적혀 있으면 false(다시 보내지 않는다). */
	fun markNotifyStarted(id: UUID): Boolean =
		jdbc.update("update notices set notify_started_at = now() where id = ? and notify_started_at is null", id) == 1

	fun recordPush(id: UUID, devices: Int, sent: Int, failed: Int, skipped: Int) {
		jdbc.update("update notices set push_devices = ?, push_sent = ?, push_failed = ?, push_skipped = ? where id = ?", devices, sent, failed, skipped, id)
	}

	fun recordInbox(id: UUID, added: Int, failed: Boolean) {
		jdbc.update("update notices set inbox_added = ?, inbox_failed = ? where id = ?", added, failed, id)
	}

	fun unpublish(id: UUID, adminId: UUID): Boolean =
		jdbc.update("update notices set status = 'DRAFT', published_at = null, updated_at = now(), updated_by = ? where id = ?", adminId, id) == 1

	fun delete(id: UUID): Boolean = jdbc.update("delete from notices where id = ?", id) == 1
}
