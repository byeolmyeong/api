package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** 관리자가 보는 FAQ. [sortOrder] 는 작은 것부터 보인다는 뜻뿐이다(값이 이어지지 않을 수 있다). */
data class AdminFaq(
	val id: UUID,
	val question: String,
	val answer: String,
	val sortOrder: Int,
	val published: Boolean,
	val updatedAt: String,
	val updatedBy: AdminRef?,
)

/** FAQ 편집. 글을 고치거나 공개 여부를 바꾼 관리자와 그때를 적는다(순서만 바꾼 것은 적지 않는다). */
@Service
class AdminFaqService(private val repository: AdminFaqRepository, private val audit: AdminAudit) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun list(): List<AdminFaq> = repository.findAll()

	fun create(adminId: UUID, question: String, answer: String, published: Boolean): AdminFaq {
		val id = repository.insert(adminId, question, answer, published)
		log.info("event=admin_faq_created published={}", published)
		audit.record(AuditAction.FAQ_CREATED, "FAQ", id, mapOf("published" to published))
		return repository.findById(id)!!
	}

	/** null 인 것은 그대로 둔다. */
	fun update(id: UUID, adminId: UUID, question: String?, answer: String?, published: Boolean?): AdminFaq {
		if (!repository.update(id, adminId, question, answer, published)) throw NotFoundException("NOT_FOUND")
		log.info("event=admin_faq_updated")
		val updated = repository.findById(id) ?: throw NotFoundException("NOT_FOUND")
		// 글을 고친 것과 공개 여부를 정한 것을 따로 남긴다(한 요청에 둘 다 있으면 두 줄). 공개는 보낸 값 그대로다(이미 그 상태였어도 남는다).
		if (question != null || answer != null) audit.record(AuditAction.FAQ_UPDATED, "FAQ", id)
		if (published != null) audit.record(if (published) AuditAction.FAQ_PUBLISHED else AuditAction.FAQ_UNPUBLISHED, "FAQ", id)
		return updated
	}

	fun delete(id: UUID) {
		if (!repository.delete(id)) throw NotFoundException("NOT_FOUND")
		log.info("event=admin_faq_deleted")
		audit.record(AuditAction.FAQ_DELETED, "FAQ", id)
	}

	/** [ids] 가 지금 있는 FAQ 전부를 한 번씩 담고 있을 때만 그 순서로 바꾼다. 아니면 400 `INVALID_ORDER`. */
	@Transactional
	fun reorder(ids: List<UUID>): List<AdminFaq> {
		val existing = repository.lockIds()
		if (ids.size != existing.size || ids.toSet() != existing.toSet()) throw BadRequestException("INVALID_ORDER")
		ids.forEachIndexed { index, id -> repository.updateOrder(id, index + 1) }
		log.info("event=admin_faq_reordered")
		audit.record(AuditAction.FAQ_REORDERED, detail = mapOf("count" to ids.size))
		return repository.findAll()
	}
}

@Repository
class AdminFaqRepository(private val jdbc: JdbcTemplate) {

	private val select = """
		select f.id, f.question, f.answer, f.sort_order, f.published, f.updated_at, f.updated_by, a.name as updater_name
		from faqs f left join admin_accounts a on a.id = f.updated_by
	"""

	private val row = RowMapper { rs, _ ->
		AdminFaq(
			id = rs.getObject("id", UUID::class.java),
			question = rs.getString("question"),
			answer = rs.getString("answer"),
			sortOrder = rs.getInt("sort_order"),
			published = rs.getBoolean("published"),
			updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).utcSeconds(),
			updatedBy = rs.getObject("updated_by", UUID::class.java)?.let { AdminRef(it, rs.getString("updater_name")) },
		)
	}

	fun findAll(): List<AdminFaq> = jdbc.query("$select order by f.sort_order, f.id", row)

	fun findById(id: UUID): AdminFaq? = jdbc.query("$select where f.id = ?", row, id).firstOrNull()

	/** 지금의 마지막 순서 뒤에 넣는다. */
	fun insert(adminId: UUID, question: String, answer: String, published: Boolean): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"""
			insert into faqs (id, question, answer, published, updated_by, sort_order)
			select ?, ?, ?, ?, ?, coalesce(max(sort_order), 0) + 1 from faqs
			""",
			id, question, answer, published, adminId,
		)
		return id
	}

	fun update(id: UUID, adminId: UUID, question: String?, answer: String?, published: Boolean?): Boolean =
		jdbc.update(
			"""
			update faqs set question = coalesce(?, question), answer = coalesce(?, answer), published = coalesce(?::boolean, published),
				updated_at = now(), updated_by = ?
			where id = ?
			""",
			question, answer, published, adminId, id,
		) == 1

	fun delete(id: UUID): Boolean = jdbc.update("delete from faqs where id = ?", id) == 1

	fun lockIds(): List<UUID> = jdbc.queryForList("select id from faqs order by id for update", UUID::class.java).filterNotNull()

	fun updateOrder(id: UUID, sortOrder: Int) {
		jdbc.update("update faqs set sort_order = ? where id = ?", sortOrder, id)
	}
}
