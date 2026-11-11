package com.byeolmyeong.api.reading

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Repository
class ReadingRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	fun find(ownerId: UUID, kind: String, chartHash: String): Reading? = jdbc.query(
		"select body, share_id from readings where owner_id = ? and kind = ? and chart_hash = ?",
		{ rs, _ ->
			val draft = mapper.readValue(rs.getString("body"), ReadingDraft::class.java)
			Reading(draft.headline, draft.sections, draft.strengths, draft.cautions, rs.getString("share_id"))
		},
		ownerId, kind, chartHash,
	).firstOrNull()

	/** 같은 소유자·종류·차트의 글이 이미 있으면 아무것도 하지 않는다(먼저 들어간 글이 남는다). */
	fun insertIfAbsent(ownerId: UUID, kind: String, chartHash: String, draft: ReadingDraft, shareId: String) {
		jdbc.update(
			"""
			insert into readings (id, owner_id, kind, chart_hash, body, share_id) values (?, ?, ?, ?, ?::jsonb, ?)
			on conflict (owner_id, kind, chart_hash) do nothing
			""".trimIndent(),
			UUID.randomUUID(), ownerId, kind, chartHash, mapper.writeValueAsString(draft), shareId,
		)
	}
}
