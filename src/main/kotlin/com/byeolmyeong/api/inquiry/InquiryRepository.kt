package com.byeolmyeong.api.inquiry

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class InquiryRepository(private val jdbc: JdbcTemplate) {

	fun insert(ownerId: UUID, inquiry: Inquiry): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into inquiries (id, owner_id, category, body, reply_email) values (?, ?, ?, ?, ?)",
			id, ownerId, inquiry.category, inquiry.body, inquiry.replyEmail,
		)
		return id
	}
}
