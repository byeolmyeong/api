package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.NotFoundException
import com.byeolmyeong.api.web.ownerRef
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/** 문의를 처리한 관리자. */
data class AdminRef(val id: UUID, val name: String)

/**
 * 관리자가 보는 문의. [ownerRef] 는 계정 id 가 아니라 그 해시의 앞부분이다 — 같은 사람의 문의를 묶어 볼 수만 있다.
 * 출생 정보는 없다.
 */
data class AdminInquiry(
	val id: UUID,
	val category: String,
	val body: String,
	val replyEmail: String?,
	val createdAt: String,
	val status: String,
	val memo: String?,
	val handledBy: AdminRef?,
	val handledAt: String?,
	val ownerRef: String,
)

data class AdminInquiryPage(val items: List<AdminInquiry>, val page: Int, val size: Int, val total: Int)

/** 문의 관리. 문의 글·메모·이메일을 로그에 남기지 않는다(상태 코드만). */
@Service
class AdminInquiryService(private val repository: AdminInquiryRepository, private val audit: AdminAudit) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun list(status: String?, category: String?, page: Int, size: Int): AdminInquiryPage =
		AdminInquiryPage(repository.find(status, category, page.toLong() * size, size), page, size, repository.count(status, category))

	/** 한 건 보기. 문의 글과 답장 이메일을 본 것이므로 감사 기록에 남긴다(목록은 남기지 않는다). */
	fun get(id: UUID): AdminInquiry {
		val inquiry = find(id)
		audit.record(AuditAction.INQUIRY_VIEWED, "INQUIRY", id)
		return inquiry
	}

	/** [status] 가 null 이면 상태는 그대로, [changeMemo] 가 false 면 메모는 그대로다. 바꾼 관리자([adminId])와 그때를 적는다. */
	fun update(id: UUID, adminId: UUID, status: String?, changeMemo: Boolean, memo: String?): AdminInquiry {
		if (!repository.update(id, adminId, status, changeMemo, memo)) throw NotFoundException("NOT_FOUND")
		val updated = find(id)
		log.info("event=admin_inquiry_updated status={}", updated.status)
		// 메모는 바꿨는지만 남긴다(글은 남기지 않는다).
		audit.record(AuditAction.INQUIRY_UPDATED, "INQUIRY", id, buildMap {
			if (status != null) put("status", status)
			put("memoChanged", changeMemo)
		})
		return updated
	}

	private fun find(id: UUID): AdminInquiry = repository.findById(id) ?: throw NotFoundException("NOT_FOUND")
}

/** `inquiries` 를 관리자 쪽에서 읽고 처리 칸만 쓴다. `inquiry` 패키지의 코드는 쓰지 않는다(기능 패키지는 서로 모른다). */
@Repository
class AdminInquiryRepository(private val jdbc: JdbcTemplate) {

	private val select = """
		select i.id, i.owner_id, i.category, i.body, i.reply_email, i.created_at, i.status, i.admin_memo, i.handled_by, i.handled_at,
			a.name as handler_name
		from inquiries i left join admin_accounts a on a.id = i.handled_by
	"""

	/** 조건이 null 이면 거르지 않는다. */
	private val filter = "where (?::text is null or i.status = ?) and (?::text is null or i.category = ?)"

	private val row = RowMapper { rs, _ ->
		val handler = rs.getObject("handled_by", UUID::class.java)
		AdminInquiry(
			id = rs.getObject("id", UUID::class.java),
			category = rs.getString("category"),
			body = rs.getString("body"),
			replyEmail = rs.getString("reply_email"),
			createdAt = rs.getObject("created_at", OffsetDateTime::class.java).utcSeconds(),
			status = rs.getString("status"),
			memo = rs.getString("admin_memo"),
			handledBy = handler?.let { AdminRef(it, rs.getString("handler_name")) },
			handledAt = rs.getObject("handled_at", OffsetDateTime::class.java)?.utcSeconds(),
			ownerRef = ownerRef(rs.getObject("owner_id", UUID::class.java)),
		)
	}

	fun find(status: String?, category: String?, offset: Long, limit: Int): List<AdminInquiry> =
		jdbc.query("$select $filter order by i.created_at desc, i.id desc offset ? limit ?", row, status, status, category, category, offset, limit)

	fun count(status: String?, category: String?): Int =
		jdbc.queryForObject("select count(*) from inquiries i $filter", Int::class.java, status, status, category, category)!!

	fun findById(id: UUID): AdminInquiry? = jdbc.query("$select where i.id = ?", row, id).firstOrNull()

	fun update(id: UUID, adminId: UUID, status: String?, changeMemo: Boolean, memo: String?): Boolean =
		jdbc.update(
			"""
			update inquiries set status = coalesce(?, status), admin_memo = case when ? then ? else admin_memo end,
				handled_by = ?, handled_at = now()
			where id = ?
			""",
			status, changeMemo, memo, adminId, id,
		) == 1
}
