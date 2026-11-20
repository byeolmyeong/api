package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.NotFoundException
import com.byeolmyeong.api.web.ownerRef
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** 문의를 처리한 관리자. */
data class AdminRef(val id: UUID, val name: String)

/**
 * 문의를 보낸 사람(결정 039 의 7). [userId] 는 계정 id(결정 043 의 사용자 관리와 같은 id), [platform] · [appVersion] 은 보낼 때의 앱 헤더,
 * [joinedAt] 은 가입한 때. 이메일 · 출생 정보는 없다.
 */
data class InquiryOwner(
	val userId: UUID,
	val nickname: String?,
	val provider: String,
	val platform: String?,
	val appVersion: String?,
	val joinedAt: String,
)

/**
 * 관리자가 보는 문의. [ownerRef] 는 계정 id 의 해시 앞부분이다 — 감사 기록과 같은 사람을 묶어 본다.
 * [answeredAt] · [answeredBy] 는 처음 답한 때와 관리자, [answerDraft] 는 앱에 보이지 않는 임시 저장. 출생 정보는 없다.
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
	val owner: InquiryOwner?,
	val answer: String?,
	val answeredAt: String?,
	val answeredBy: AdminRef?,
	val answerDraft: String?,
)

data class AdminInquiryPage(val items: List<AdminInquiry>, val page: Int, val size: Int, val total: Int)

/**
 * 검증을 통과한 바꿀 값. [status] 가 null 이면 상태는 그대로, [changeMemo] · [changeDraft] 가 false 면 메모 · 임시 저장은 그대로다.
 * [answer] 가 있으면 상태를 ANSWERED 로 정하고 임시 저장을 비운다(컨트롤러가 `status` · `answerDraft` 와 함께 오지 않게 막았다).
 */
class InquiryChange(
	val status: String?,
	val changeMemo: Boolean,
	val memo: String?,
	val answer: String?,
	val changeDraft: Boolean,
	val draft: String?,
)

/** 문의 관리. 문의 글 · 메모 · 답 · 이메일을 로그에 남기지 않는다(상태 코드만). */
@Service
class AdminInquiryService(
	private val repository: AdminInquiryRepository,
	private val audit: AdminAudit,
	private val events: ApplicationEventPublisher,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun list(status: String?, category: String?, query: String?, page: Int, size: Int): AdminInquiryPage =
		AdminInquiryPage(repository.find(status, category, query, page.toLong() * size, size), page, size, repository.count(status, category, query))

	/** 한 건 보기. 문의 글과 답장 이메일을 본 것이므로 감사 기록에 남긴다(목록은 남기지 않는다). */
	fun get(id: UUID): AdminInquiry {
		val inquiry = find(id)
		audit.record(AuditAction.INQUIRY_VIEWED, "INQUIRY", id)
		return inquiry
	}

	/**
	 * 바꾼 관리자([adminId])와 그때를 적는다. 답이 처음이면(두 관리자가 동시에 보내도 한 번만) `admin.InquiryAnswered` 를 낸다 —
	 * 알림함 · 푸시가 커밋된 뒤에 듣는다(결정 039 의 5). 고친 답 · 임시 저장은 알리지 않는다.
	 */
	@Transactional
	fun update(id: UUID, adminId: UUID, change: InquiryChange): AdminInquiry {
		val first = change.answer != null && repository.markFirstAnswer(id, adminId)
		val ownerId = repository.update(id, adminId, change) ?: throw NotFoundException("NOT_FOUND")
		val updated = find(id)
		log.info("event=admin_inquiry_updated status={}", updated.status)
		if (change.answer != null) {
			log.info("event=admin_inquiry_answered first={}", first)
			if (first) events.publishEvent(InquiryAnswered(ownerId, id))
			audit.record(AuditAction.INQUIRY_ANSWERED, "INQUIRY", id, mapOf("first" to first))
		}
		// 메모 · 임시 저장은 바꿨는지만 남긴다(글은 남기지 않는다). 답만 보낸 요청은 위의 한 줄뿐이다.
		if (change.status != null || change.changeMemo || change.changeDraft) {
			audit.record(AuditAction.INQUIRY_UPDATED, "INQUIRY", id, buildMap {
				if (change.status != null) put("status", change.status)
				put("memoChanged", change.changeMemo)
				if (change.changeDraft) put("draftChanged", true)
			})
		}
		return updated
	}

	private fun find(id: UUID): AdminInquiry = repository.findById(id) ?: throw NotFoundException("NOT_FOUND")
}

/** `inquiries` 를 관리자 쪽에서 읽고 처리 칸 · 답 칸만 쓴다. `inquiry` 패키지의 코드는 쓰지 않는다(기능 패키지는 서로 모른다). */
@Repository
class AdminInquiryRepository(private val jdbc: JdbcTemplate) {

	private val from = """
		from inquiries i
			left join admin_accounts h on h.id = i.handled_by
			left join admin_accounts w on w.id = i.answered_by
			left join accounts ac on ac.id = i.owner_id
	"""

	private val select = """
		select i.id, i.owner_id, i.category, i.body, i.reply_email, i.created_at, i.status, i.admin_memo, i.handled_by, i.handled_at,
			i.answer_body, i.answered_at, i.answered_by, i.answer_draft, i.app_platform, i.app_version,
			h.name as handler_name, w.name as answerer_name, ac.id as account_id, ac.nickname, ac.provider, ac.created_at as joined_at
		$from
	"""

	/** 조건이 null 이면 거르지 않는다. 찾기는 글 또는 닉네임의 일부(대소문자 무시, `%` · `_` 도 글자 그대로). */
	private val filter = """
		where (?::text is null or i.status = ?) and (?::text is null or i.category = ?)
			and (?::text is null or position(lower(?::text) in lower(i.body)) > 0 or position(lower(?::text) in lower(coalesce(ac.nickname, ''))) > 0)
	"""

	private fun filterArgs(status: String?, category: String?, query: String?): Array<Any?> = arrayOf(status, status, category, category, query, query, query)

	private val row = RowMapper { rs, _ ->
		val handler = rs.getObject("handled_by", UUID::class.java)
		val answerer = rs.getObject("answered_by", UUID::class.java)
		val account = rs.getObject("account_id", UUID::class.java)
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
			owner = account?.let {
				InquiryOwner(
					userId = it,
					nickname = rs.getString("nickname"),
					provider = rs.getString("provider"),
					platform = rs.getString("app_platform"),
					appVersion = rs.getString("app_version"),
					joinedAt = rs.getObject("joined_at", OffsetDateTime::class.java).utcSeconds(),
				)
			},
			answer = rs.getString("answer_body"),
			answeredAt = rs.getObject("answered_at", OffsetDateTime::class.java)?.utcSeconds(),
			answeredBy = answerer?.let { AdminRef(it, rs.getString("answerer_name")) },
			answerDraft = rs.getString("answer_draft"),
		)
	}

	fun find(status: String?, category: String?, query: String?, offset: Long, limit: Int): List<AdminInquiry> =
		jdbc.query("$select $filter order by i.created_at desc, i.id desc offset ? limit ?", row, *filterArgs(status, category, query), offset, limit)

	fun count(status: String?, category: String?, query: String?): Int =
		jdbc.queryForObject("select count(*) $from $filter", Int::class.java, *filterArgs(status, category, query))!!

	fun findById(id: UUID): AdminInquiry? = jdbc.query("$select where i.id = ?", row, id).firstOrNull()

	/**
	 * 아직 답이 없는 문의에만 처음 답한 때와 관리자를 적는다. 적었으면 true(처음 답). 행이 잠기므로 동시에 와도 하나만 true 다.
	 * 같은 트랜잭션에서 [update] 가 뒤따른다.
	 */
	fun markFirstAnswer(id: UUID, adminId: UUID): Boolean =
		jdbc.update("update inquiries set answered_at = now(), answered_by = ? where id = ? and answered_at is null", adminId, id) == 1

	/** 바꾼 문의의 소유자(이벤트에 싣는다). 없는 문의면 null. */
	fun update(id: UUID, adminId: UUID, change: InquiryChange): UUID? =
		jdbc.query(
			"""
			update inquiries set
				status = case when ? then 'ANSWERED' else coalesce(?, status) end,
				admin_memo = case when ? then ? else admin_memo end,
				answer_body = coalesce(?, answer_body),
				answer_draft = case when ? then null when ? then ? else answer_draft end,
				handled_by = ?, handled_at = now()
			where id = ?
			returning owner_id
			""",
			{ rs, _ -> rs.getObject("owner_id", UUID::class.java) },
			change.answer != null, change.status,
			change.changeMemo, change.memo,
			change.answer,
			change.answer != null, change.changeDraft, change.draft,
			adminId, id,
		).firstOrNull()
}
