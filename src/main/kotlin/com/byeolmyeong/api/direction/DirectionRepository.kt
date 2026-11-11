package com.byeolmyeong.api.direction

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Repository
class DirectionRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	fun find(ownerId: UUID, topic: String, target: String): Direction? = jdbc.query(
		"select body from directions where owner_id = ? and topic = ? and target = ?",
		{ rs, _ ->
			val draft = mapper.readValue(rs.getString("body"), DirectionDraft::class.java)
			Direction(topic, draft.title, draft.items)
		},
		ownerId, topic, target,
	).firstOrNull()

	/** 같은 소유자·주제·대상의 글이 이미 있으면 아무것도 하지 않는다(먼저 들어간 글이 남는다). */
	fun insertIfAbsent(ownerId: UUID, topic: String, target: String, draft: DirectionDraft) {
		jdbc.update(
			"""
			insert into directions (id, owner_id, topic, target, body) values (?, ?, ?, ?, ?::jsonb)
			on conflict (owner_id, topic, target) do nothing
			""".trimIndent(),
			UUID.randomUUID(), ownerId, topic, target, mapper.writeValueAsString(draft),
		)
	}
}
