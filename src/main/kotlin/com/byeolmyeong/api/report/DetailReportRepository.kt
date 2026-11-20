package com.byeolmyeong.api.report

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** 자세한 풀이의 종류(결정 029). 글자는 `detail_reports.kind` 와 같다. [event] 는 그 종류의 로그 이벤트 이름 앞쪽이다(`me_report_started` 등). */
enum class DetailReportKind(val event: String) { ME("me_report"), TIMING("timing_report") }

/** 저장된 자세한 풀이 한 행. [stale] 은 `WRITING` 인데 살아 있다는 표시(`active_at`)가 정해진 시간 넘게 끊겼다는 뜻이다. */
data class StoredDetailReport<T>(val status: ReportStatus, val body: T?, val stale: Boolean)

/**
 * 한 번의 생성. [startedAt] 은 그 생성을 시작하며 행에 적은 시각이고, 끝낼 때 "아직 내 생성이 맞는지" 를 이것으로 가린다
 * (멈춘 줄 알고 다시 시작한 뒤에 옛 생성이 뒤늦게 끝나도 덮어쓰지 못한다).
 */
data class DetailReportRun(val ownerId: UUID, val kind: DetailReportKind, val chartHash: String, val startedAt: OffsetDateTime)

/** `detail_reports`(V22, 결정 029) — 나 탭 풀이 · 시기 풀이를 kind 로 나눠 둔다. 상태 · 자리 잡기 · 살아 있다는 표시는 옛 `reports` 와 같다. */
@Repository
class DetailReportRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	fun <T : Any> find(ownerId: UUID, kind: DetailReportKind, chartHash: String, staleSeconds: Int, bodyType: Class<T>): StoredDetailReport<T>? = jdbc.query(
		"""
		select status, body, active_at < now() - make_interval(secs => ?) as stale
		from detail_reports where owner_id = ? and kind = ? and chart_hash = ?
		""".trimIndent(),
		{ rs, _ ->
			StoredDetailReport(
				status = ReportStatus.valueOf(rs.getString("status")),
				body = rs.getString("body")?.let { mapper.readValue(it, bodyType) },
				stale = rs.getBoolean("stale"),
			)
		},
		staleSeconds, ownerId, kind.name, chartHash,
	).firstOrNull()

	/** 본문을 읽지 않고 상태만: `WRITING` 인데 살아 있다는 표시가 [staleSeconds] 초 넘게 끊겼으면 `FAILED`. 행이 없으면 null. */
	fun status(ownerId: UUID, kind: DetailReportKind, chartHash: String, staleSeconds: Int): ReportStatus? = jdbc.query(
		"""
		select case when status = 'WRITING' and active_at < now() - make_interval(secs => ?) then 'FAILED' else status end as status
		from detail_reports where owner_id = ? and kind = ? and chart_hash = ?
		""".trimIndent(),
		{ rs, _ -> ReportStatus.valueOf(rs.getString("status")) },
		staleSeconds, ownerId, kind.name, chartHash,
	).firstOrNull()

	/**
	 * 생성을 시작할 자리를 잡는다. 잡았으면 그 생성을 돌려주고, 못 잡았으면 null 이다.
	 * 잡는 경우: 행이 없다, `FAILED` 다, `WRITING` 인데 살아 있다는 표시가 [staleSeconds] 초 넘게 끊겼다.
	 * 한 문장이라 동시에 불러도 한 요청만 잡는다(나머지는 행 잠금을 기다린 뒤 조건에 걸린다).
	 */
	fun claim(ownerId: UUID, kind: DetailReportKind, chartHash: String, staleSeconds: Int): DetailReportRun? = jdbc.query(
		"""
		insert into detail_reports (id, owner_id, kind, chart_hash, status) values (?, ?, ?, ?, 'WRITING')
		on conflict (owner_id, kind, chart_hash) do update
		set status = 'WRITING', body = null, started_at = now(), active_at = now(), updated_at = now()
		where detail_reports.status = 'FAILED'
			or (detail_reports.status = 'WRITING' and detail_reports.active_at < now() - make_interval(secs => ?))
		returning started_at
		""".trimIndent(),
		{ rs, _ -> DetailReportRun(ownerId, kind, chartHash, rs.getObject("started_at", OffsetDateTime::class.java)) },
		UUID.randomUUID(), ownerId, kind.name, chartHash, staleSeconds,
	).firstOrNull()

	/** 그 생성이 살아 있다고 적는다. 아직 그 생성의 자리일 때만. */
	fun beat(run: DetailReportRun) {
		jdbc.update(
			"""
			update detail_reports set active_at = now()
			where owner_id = ? and kind = ? and chart_hash = ? and status = 'WRITING' and started_at = ?
			""".trimIndent(),
			run.ownerId, run.kind.name, run.chartHash, run.startedAt,
		)
	}

	/** 아직 그 생성의 자리인지. 행이 지워졌거나, 실패로 끝났거나, 다른 생성이 자리를 잡았으면 false. */
	fun owns(run: DetailReportRun): Boolean = jdbc.queryForObject(
		"select count(*) from detail_reports where owner_id = ? and kind = ? and chart_hash = ? and status = 'WRITING' and started_at = ?",
		Int::class.java, run.ownerId, run.kind.name, run.chartHash, run.startedAt,
	) == 1

	/** 글을 저장하고 `DONE` 으로 바꾼다. 그 사이 행이 지워졌거나 다른 생성이 자리를 잡았으면 아무것도 하지 않고 false. */
	fun complete(run: DetailReportRun, body: Any): Boolean = jdbc.update(
		"""
		update detail_reports set status = 'DONE', body = ?::jsonb, updated_at = now()
		where owner_id = ? and kind = ? and chart_hash = ? and status = 'WRITING' and started_at = ?
		""".trimIndent(),
		mapper.writeValueAsString(body), run.ownerId, run.kind.name, run.chartHash, run.startedAt,
	) == 1

	/** 그 소유자의 풀이 가운데 [chartHash] 가 아닌 차트의 것을 지운다(종류 모두, 출생 정보 바꾸기 — 결정 041). */
	fun deleteOtherCharts(ownerId: UUID, chartHash: String) {
		jdbc.update("delete from detail_reports where owner_id = ? and chart_hash <> ?", ownerId, chartHash)
	}

	/** `FAILED` 로 바꾼다. 아직 그 생성의 자리일 때만(아니면 false). */
	fun fail(run: DetailReportRun): Boolean = jdbc.update(
		"""
		update detail_reports set status = 'FAILED', updated_at = now()
		where owner_id = ? and kind = ? and chart_hash = ? and status = 'WRITING' and started_at = ?
		""".trimIndent(),
		run.ownerId, run.kind.name, run.chartHash, run.startedAt,
	) == 1
}
