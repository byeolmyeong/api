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

/** 저장하는 것 전부. 상대의 출생 정보·행성 위치·각도는 여기에 없다(결정 010). */
data class NewSynastry(
	val ownerId: UUID,
	val name: String,
	val relation: Relation,
	val score: Int,
	val summary: SynastrySummary,
	val ownerChartHash: String,
)

/** 저장된 궁합 리포트. [status] 는 멈춘 것을 이미 `FAILED` 로 바꾼 값이다. */
data class StoredSynastryReport(val status: SynastryReportStatus, val report: SynastryReport?)

/**
 * 리포트 한 번의 생성. [startedAt] 은 그 생성을 시작하며 행에 적은 시각이고, 끝낼 때 "아직 내 생성이 맞는지" 를 이것으로 가린다
 * (멈춘 줄 알고 다시 시작한 뒤에 옛 생성이 뒤늦게 끝나도 덮어쓰지 못한다).
 */
data class SynastryReportRun(val synastryId: UUID, val startedAt: OffsetDateTime)

// 리포트 상태는 synastry_reports 에서 읽는다. WRITING 인데 살아 있다는 표시(active_at)가 정해진 시간 넘게 끊겼으면 멈춘 것(FAILED)으로 본다.
private const val SELECT = """
	select s.id, s.name, s.relation, s.score, s.headline, s.keywords, s.owner_chart_hash, s.created_at,
		case
			when r.status is null then 'NONE'
			when r.status = 'WRITING' and r.active_at < now() - make_interval(secs => ?) then 'FAILED'
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
	fun find(ownerId: UUID, id: UUID, staleSeconds: Int): StoredSynastry? =
		jdbc.query("$SELECT where s.owner_id = ? and s.id = ?", row, staleSeconds, ownerId, id).firstOrNull()

	/** 소유자의 궁합 전부, 새것부터. */
	fun list(ownerId: UUID, staleSeconds: Int): List<StoredSynastry> =
		jdbc.query("$SELECT where s.owner_id = ? order by s.created_at desc, s.id", row, staleSeconds, ownerId)

	/** 소유자의 그 관계 유형 궁합 전부, 점수가 높은 것부터. 점수가 같으면 먼저 만든 것이 위다. */
	fun ranking(ownerId: UUID, relation: Relation, staleSeconds: Int): List<StoredSynastry> = jdbc.query(
		"$SELECT where s.owner_id = ? and s.relation = ? order by s.score desc, s.created_at, s.id",
		row, staleSeconds, ownerId, relation.name,
	)

	// 아래 리포트 함수들은 궁합 id 만 받는다. 그 궁합이 소유자의 것인지는 서비스가 [find] 로 먼저 본다.

	fun findReport(synastryId: UUID, staleSeconds: Int): StoredSynastryReport? = jdbc.query(
		"""
		select case when status = 'WRITING' and active_at < now() - make_interval(secs => ?) then 'FAILED' else status end as status, body
		from synastry_reports where synastry_id = ?
		""".trimIndent(),
		{ rs, _ ->
			StoredSynastryReport(
				status = SynastryReportStatus.valueOf(rs.getString("status")),
				report = rs.getString("body")?.let { mapper.readValue(it, SynastryReport::class.java) },
			)
		},
		staleSeconds, synastryId,
	).firstOrNull()

	/**
	 * 리포트 생성을 시작할 자리를 잡는다. 잡았으면 그 생성을 돌려주고, 못 잡았으면 null 이다.
	 * 잡는 경우: 행이 없다, `FAILED` 다, `WRITING` 인데 살아 있다는 표시가 [staleSeconds] 초 넘게 끊겼다.
	 * 한 문장이라 동시에 불러도 한 요청만 잡는다.
	 */
	fun claimReport(synastryId: UUID, staleSeconds: Int): SynastryReportRun? = jdbc.query(
		"""
		insert into synastry_reports (synastry_id, status) values (?, 'WRITING')
		on conflict (synastry_id) do update
		set status = 'WRITING', body = null, started_at = now(), active_at = now(), updated_at = now()
		where synastry_reports.status = 'FAILED'
			or (synastry_reports.status = 'WRITING' and synastry_reports.active_at < now() - make_interval(secs => ?))
		returning started_at
		""".trimIndent(),
		{ rs, _ -> SynastryReportRun(synastryId, rs.getObject("started_at", OffsetDateTime::class.java)) },
		synastryId, staleSeconds,
	).firstOrNull()

	/** 그 생성이 살아 있다고 적는다. 아직 그 생성의 자리일 때만. */
	fun beatReport(run: SynastryReportRun) {
		jdbc.update(
			"update synastry_reports set active_at = now() where synastry_id = ? and status = 'WRITING' and started_at = ?",
			run.synastryId, run.startedAt,
		)
	}

	/** 아직 그 생성의 자리인지. 행이 지워졌거나, 실패로 끝났거나, 다른 생성이 자리를 잡았으면 false. */
	fun ownsReport(run: SynastryReportRun): Boolean = jdbc.queryForObject(
		"select count(*) from synastry_reports where synastry_id = ? and status = 'WRITING' and started_at = ?",
		Int::class.java, run.synastryId, run.startedAt,
	) == 1

	/** 글을 저장하고 `DONE` 으로 바꾼다. 그 사이 행이 지워졌거나 다른 생성이 자리를 잡았으면 아무것도 하지 않고 false. */
	fun completeReport(run: SynastryReportRun, report: SynastryReport): Boolean = jdbc.update(
		"""
		update synastry_reports set status = 'DONE', body = ?::jsonb, updated_at = now()
		where synastry_id = ? and status = 'WRITING' and started_at = ?
		""".trimIndent(),
		mapper.writeValueAsString(report), run.synastryId, run.startedAt,
	) == 1

	/** `FAILED` 로 바꾼다. 아직 그 생성의 자리일 때만(아니면 false). */
	fun failReport(run: SynastryReportRun): Boolean =
		jdbc.update(
			"update synastry_reports set status = 'FAILED', updated_at = now() where synastry_id = ? and status = 'WRITING' and started_at = ?",
			run.synastryId, run.startedAt,
		) == 1
}
