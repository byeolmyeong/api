package com.byeolmyeong.api.report

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** 저장된 리포트 한 행. [stale] 은 `WRITING` 인 채 정해진 시간이 지났다는 뜻이다. */
data class StoredReport(val status: ReportStatus, val report: Report?, val stale: Boolean)

/**
 * 한 번의 생성. [startedAt] 은 그 생성을 시작하며 행에 적은 시각이고, 끝낼 때 "아직 내 생성이 맞는지" 를 이것으로 가린다
 * (멈춘 줄 알고 다시 시작한 뒤에 옛 생성이 뒤늦게 끝나도 덮어쓰지 못한다).
 */
data class ReportRun(val ownerId: UUID, val chartHash: String, val startedAt: OffsetDateTime)

@Repository
class ReportRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	fun find(ownerId: UUID, chartHash: String, staleMinutes: Int): StoredReport? = jdbc.query(
		"""
		select status, body, started_at < now() - make_interval(mins => ?) as stale
		from reports where owner_id = ? and chart_hash = ?
		""".trimIndent(),
		{ rs, _ ->
			StoredReport(
				status = ReportStatus.valueOf(rs.getString("status")),
				report = rs.getString("body")?.let { mapper.readValue(it, Report::class.java) },
				stale = rs.getBoolean("stale"),
			)
		},
		staleMinutes, ownerId, chartHash,
	).firstOrNull()

	/**
	 * 생성을 시작할 자리를 잡는다. 잡았으면 그 생성을 돌려주고, 못 잡았으면 null 이다.
	 * 잡는 경우: 행이 없다, `FAILED` 다, `WRITING` 인 채 [staleMinutes] 분이 지났다.
	 * 한 문장이라 동시에 불러도 한 요청만 잡는다(나머지는 행 잠금을 기다린 뒤 조건에 걸린다).
	 */
	fun claim(ownerId: UUID, chartHash: String, staleMinutes: Int): ReportRun? = jdbc.query(
		"""
		insert into reports (id, owner_id, chart_hash, status) values (?, ?, ?, 'WRITING')
		on conflict (owner_id, chart_hash) do update
		set status = 'WRITING', body = null, started_at = now(), updated_at = now()
		where reports.status = 'FAILED'
			or (reports.status = 'WRITING' and reports.started_at < now() - make_interval(mins => ?))
		returning started_at
		""".trimIndent(),
		{ rs, _ -> ReportRun(ownerId, chartHash, rs.getObject("started_at", OffsetDateTime::class.java)) },
		UUID.randomUUID(), ownerId, chartHash, staleMinutes,
	).firstOrNull()

	/** 글을 저장하고 `DONE` 으로 바꾼다. 그 사이 행이 지워졌거나 다른 생성이 자리를 잡았으면 아무것도 하지 않고 false. */
	fun complete(run: ReportRun, report: Report): Boolean = jdbc.update(
		"""
		update reports set status = 'DONE', body = ?::jsonb, updated_at = now()
		where owner_id = ? and chart_hash = ? and status = 'WRITING' and started_at = ?
		""".trimIndent(),
		mapper.writeValueAsString(report), run.ownerId, run.chartHash, run.startedAt,
	) == 1

	/** `FAILED` 로 바꾼다. 아직 그 생성의 자리일 때만. */
	fun fail(run: ReportRun) {
		jdbc.update(
			"""
			update reports set status = 'FAILED', updated_at = now()
			where owner_id = ? and chart_hash = ? and status = 'WRITING' and started_at = ?
			""".trimIndent(),
			run.ownerId, run.chartHash, run.startedAt,
		)
	}
}
