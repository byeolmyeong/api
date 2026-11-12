package com.byeolmyeong.api.direction

import com.byeolmyeong.api.chart.Relation
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

	/** 대상이 [targetPrefix] 로 시작하는 글 전부(대상, 글). 미래 주제가 그 차트의 글 가운데 오늘의 것을 고를 때 쓴다. */
	fun findByTargetPrefix(ownerId: UUID, topic: String, targetPrefix: String): List<Pair<String, Direction>> = jdbc.query(
		"select target, body from directions where owner_id = ? and topic = ? and starts_with(target, ?)",
		{ rs, _ ->
			val draft = mapper.readValue(rs.getString("body"), DirectionDraft::class.java)
			rs.getString("target") to Direction(topic, draft.title, draft.items)
		},
		ownerId, topic, targetPrefix,
	)

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

	/**
	 * 소유자의 궁합 한 건에서 글에 필요한 것만 읽는다. 남의 것이면 없는 것이다.
	 * `synastry` 의 표를 읽기만 한다 — 기능 패키지는 서로의 클래스를 쓸 수 없고, 이 값들은 앱에서 받으면 안 된다(결정 011).
	 */
	fun findSynastry(ownerId: UUID, synastryId: UUID): SynastryRef? = jdbc.query(
		"select relation, score, owner_chart_hash from synastries where owner_id = ? and id = ?",
		{ rs, _ -> SynastryRef(Relation.valueOf(rs.getString("relation")), rs.getInt("score"), rs.getString("owner_chart_hash")) },
		ownerId, synastryId,
	).firstOrNull()
}
