package com.byeolmyeong.api.inquiry

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Repository
class InquiryRepository(private val jdbc: JdbcTemplate) {

	private val select = "select id, category, body, reply_email, created_at, answer_body, answered_at from inquiries"

	private val row = RowMapper { rs, _ ->
		val answer = rs.getString("answer_body")
		MyInquiry(
			id = rs.getObject("id", UUID::class.java),
			category = rs.getString("category"),
			body = rs.getString("body"),
			status = if (answer == null) "OPEN" else "ANSWERED",
			createdAt = rs.getObject("created_at", OffsetDateTime::class.java).utcSeconds(),
			answer = answer?.let { InquiryAnswer(it, rs.getObject("answered_at", OffsetDateTime::class.java).utcSeconds()) },
			replyEmail = rs.getString("reply_email"),
		)
	}

	fun insert(ownerId: UUID, inquiry: Inquiry, app: InquiryApp): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into inquiries (id, owner_id, category, body, app_platform, app_version) values (?, ?, ?, ?, ?, ?)",
			id, ownerId, inquiry.category, inquiry.body, app.platform, app.version,
		)
		return id
	}

	/** 그 소유자의 문의 전부, 새것부터. */
	fun findByOwner(ownerId: UUID): List<MyInquiry> =
		jdbc.query("$select where owner_id = ? order by created_at desc, id desc", row, ownerId)

	/** 그 소유자의 것이 아니면 없는 것(null)이다. */
	fun findByOwner(ownerId: UUID, id: UUID): MyInquiry? =
		jdbc.query("$select where owner_id = ? and id = ?", row, ownerId, id).firstOrNull()

	/** 응답의 시각: UTC, 초까지. */
	private fun OffsetDateTime.utcSeconds(): String = toInstant().truncatedTo(ChronoUnit.SECONDS).toString()
}
