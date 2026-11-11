package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.chart.Relation
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 저장된 궁합 한 행과 그 리포트의 상태. [ownerChartHash] 는 계산할 때의 내 차트다. */
data class StoredSynastry(val synastry: Synastry, val ownerChartHash: String)

/** 저장하는 것 전부. 상대의 출생 정보·행성 위치·각도는 여기에 없다(결정 008). */
data class NewSynastry(
	val ownerId: UUID,
	val name: String,
	val relation: Relation,
	val score: Int,
	val summary: SynastrySummary,
	val ownerChartHash: String,
)

// 리포트 상태는 synastry_reports 에서 읽는다. WRITING 인 채 정해진 시간이 지났으면 멈춘 것(FAILED)으로 본다.
private const val SELECT = """
	select s.id, s.name, s.relation, s.score, s.headline, s.keywords, s.owner_chart_hash, s.created_at,
		case
			when r.status is null then 'NONE'
			when r.status = 'WRITING' and r.started_at < now() - make_interval(mins => ?) then 'FAILED'
			else r.status
		end as report_status
	from synastries s left join synastry_reports r on r.synastry_id = s.id
"""

@Repository
class SynastryRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	private val row = RowMapper { rs, _ ->
		val score = rs.getInt("score")
		StoredSynastry(
			synastry = Synastry(
				id = rs.getObject("id", UUID::class.java),
				name = rs.getString("name"),
				relation = Relation.valueOf(rs.getString("relation")),
				score = score,
				scoreLabel = scoreLabel(score),
				headline = rs.getString("headline"),
				keywords = mapper.readerForListOf(String::class.java).readValue(rs.getString("keywords")),
				reportStatus = SynastryReportStatus.valueOf(rs.getString("report_status")),
				createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
					.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_INSTANT),
			),
			ownerChartHash = rs.getString("owner_chart_hash"),
		)
	}

	fun insert(new: NewSynastry): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"""
			insert into synastries (id, owner_id, name, relation, score, headline, keywords, owner_chart_hash)
			values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
			""".trimIndent(),
			id, new.ownerId, new.name, new.relation.name, new.score, new.summary.headline,
			mapper.writeValueAsString(new.summary.keywords), new.ownerChartHash,
		)
		return id
	}

	/** 소유자의 궁합 하나. 남의 것이면 없는 것이다. */
	fun find(ownerId: UUID, id: UUID, staleMinutes: Int): StoredSynastry? =
		jdbc.query("$SELECT where s.owner_id = ? and s.id = ?", row, staleMinutes, ownerId, id).firstOrNull()

	/** 소유자의 궁합 전부, 새것부터. */
	fun list(ownerId: UUID, staleMinutes: Int): List<StoredSynastry> =
		jdbc.query("$SELECT where s.owner_id = ? order by s.created_at desc, s.id", row, staleMinutes, ownerId)

	/** 소유자의 그 관계 유형 궁합 전부, 점수가 높은 것부터. 점수가 같으면 먼저 만든 것이 위다. */
	fun ranking(ownerId: UUID, relation: Relation, staleMinutes: Int): List<StoredSynastry> = jdbc.query(
		"$SELECT where s.owner_id = ? and s.relation = ? order by s.score desc, s.created_at, s.id",
		row, staleMinutes, ownerId, relation.name,
	)
}
