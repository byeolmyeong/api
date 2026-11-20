package com.byeolmyeong.api.sky

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 그날의 하늘 저장본(`day_skies`, V30). 소유자 · 차트 해시 · 날짜마다 한 행. */
@Repository
class DaySkyRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	fun find(ownerId: UUID, chartHash: String, date: LocalDate): DaySky? = jdbc.query(
		"select body, created_at from day_skies where owner_id = ? and chart_hash = ? and date = ?",
		{ rs, _ ->
			val createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().truncatedTo(ChronoUnit.SECONDS).toString()
			mapper.readValue(rs.getString("body"), DaySkyBody::class.java).toDaySky(date, createdAt)
		},
		ownerId, chartHash, date,
	).firstOrNull()

	/** 같은 소유자 · 차트 · 날짜의 글이 이미 있으면 아무것도 하지 않는다(먼저 들어간 글이 남는다). */
	fun insertIfAbsent(ownerId: UUID, chartHash: String, date: LocalDate, body: DaySkyBody) {
		jdbc.update(
			"""
			insert into day_skies (id, owner_id, chart_hash, date, body) values (?, ?, ?, ?, ?::jsonb)
			on conflict (owner_id, chart_hash, date) do nothing
			""".trimIndent(),
			UUID.randomUUID(), ownerId, chartHash, date, mapper.writeValueAsString(body),
		)
	}
}
