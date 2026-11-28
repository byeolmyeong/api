package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.birth.BirthProfile
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.generation.scoreLabel
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Date
import java.sql.Time
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 저장된 궁합 한 행. [ownerChartHash] 는 그 글을 쓴 때의 내 차트다(지금 차트와 다르면 글을 다시 써야 한다 — 결정 053 의 5).
 * [partner] 는 직접 넣은 상대의 출생 정보이고 옛 행 · 링크 행에는 없다. 응답에는 싣지 않는다.
 */
data class StoredSynastry(val synastry: Synastry, val ownerChartHash: String, val partner: BirthProfile?)

/** 새로 넣는 궁합. 글은 아직 없고 [ownerChartHash] 로 쓰기 시작한다. 상대의 출생 정보([partner])는 이 궁합에만 쓴다(결정 053 의 2). */
data class NewSynastry(
	val ownerId: UUID,
	val name: String,
	val relation: Relation,
	val score: Int,
	val ownerChartHash: String,
	val partner: BirthProfile,
)

/**
 * 궁합 글 한 번의 생성. [startedAt] 은 그 생성을 시작하며 행에 적은 시각이고, 끝낼 때 "아직 내 생성이 맞는지" 를 이것으로 가린다
 * (멈춘 줄 알고 다시 시작한 뒤에 옛 생성이 뒤늦게 끝나도 덮어쓰지 못한다).
 */
data class SynastryRun(val id: UUID, val startedAt: OffsetDateTime)

// WRITING 인데 살아 있다는 표시(active_at)가 정해진 시간 넘게 끊겼으면 멈춘 것(FAILED)으로 본다.
private const val SELECT = """
	select s.id, s.name, s.relation, s.score, s.headline, s.keywords, s.owner_chart_hash, s.created_at, s.source,
		case when s.status = 'WRITING' and s.active_at < now() - make_interval(secs => ?) then 'FAILED' else s.status end as status,
		s.partner_birth_date, s.partner_birth_time, s.partner_place_id
	from synastries s
"""

// 생성을 다시 시작할 수 있는 행: 실패했다, 쓰는 중인데 멈췄다, 완성됐지만 그 글을 쓴 때의 내 차트가 지금과 다르다(결정 053 의 5).
private const val CLAIMABLE = """
	(status = 'FAILED'
		or (status = 'WRITING' and active_at < now() - make_interval(secs => ?))
		or (status = 'DONE' and owner_chart_hash <> ?))
"""

/** 내 출생 정보가 바뀌면 점수를 다시 계산할 궁합: 상대의 출생 정보가 저장돼 있고 사이가 있는 것. */
data class Rescorable(val id: UUID, val relation: Relation, val partner: BirthProfile)

@Repository
class SynastryRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	private val row = RowMapper { rs, _ ->
		val score = rs.getObject("score") as Int?
		StoredSynastry(
			synastry = Synastry(
				id = rs.getObject("id", UUID::class.java),
				name = rs.getString("name"),
				relation = rs.getString("relation")?.let(Relation::valueOf),
				status = SynastryStatus.valueOf(rs.getString("status")),
				source = SynastrySource.valueOf(rs.getString("source")),
				score = score,
				scoreLabel = score?.let(::scoreLabel),
				headline = rs.getString("headline"),
				keywords = rs.getString("keywords")?.let { mapper.readerForListOf(String::class.java).readValue(it) },
				createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
					.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_INSTANT),
			),
			ownerChartHash = rs.getString("owner_chart_hash"),
			partner = rs.getDate("partner_birth_date")?.let { date ->
				BirthProfile(date.toLocalDate(), rs.getTime("partner_birth_time")?.toLocalTime(), rs.getObject("partner_place_id") as Int?)
			},
		)
	}

	private val run = RowMapper { rs, _ -> SynastryRun(rs.getObject("id", UUID::class.java), rs.getObject("started_at", OffsetDateTime::class.java)) }

	/** 쓰는 중(`WRITING`)인 새 궁합을 넣고 그 생성을 준다. */
	fun insert(new: NewSynastry): SynastryRun = jdbc.queryForObject(
		"""
		insert into synastries (id, owner_id, name, relation, score, owner_chart_hash, source, status, started_at, active_at,
			partner_birth_date, partner_birth_time, partner_place_id)
		values (?, ?, ?, ?, ?, ?, 'DIRECT', 'WRITING', now(), now(), ?, ?, ?)
		returning id, started_at
		""".trimIndent(),
		run,
		UUID.randomUUID(), new.ownerId, new.name, new.relation.name, new.score, new.ownerChartHash,
		Date.valueOf(new.partner.date), new.partner.time?.let(Time::valueOf), new.partner.placeId,
	)!!

	/** 소유자가 그날(한국 날짜 — DB 의 한국 시각 자정부터) 만든 궁합 수. 지운 것은 세지 못한다(결정 053 의 6). */
	fun createdToday(ownerId: UUID): Int = jdbc.queryForObject(
		"select count(*) from synastries where owner_id = ? and created_at >= (date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul')",
		Int::class.java, ownerId,
	)!!

	/** 소유자의 궁합 하나. 남의 것이면 없는 것이다. */
	fun find(ownerId: UUID, id: UUID, staleSeconds: Int): StoredSynastry? =
		jdbc.query("$SELECT where s.owner_id = ? and s.id = ?", row, staleSeconds, ownerId, id).firstOrNull()

	/** 소유자의 궁합 전부, 새것부터. */
	fun list(ownerId: UUID, staleSeconds: Int): List<StoredSynastry> =
		jdbc.query("$SELECT where s.owner_id = ? order by s.created_at desc, s.id", row, staleSeconds, ownerId)

	/** 소유자의 그 관계 유형의 완성된 궁합 전부, 점수가 높은 것부터. 점수가 같으면 먼저 만든 것이 위다. */
	fun ranking(ownerId: UUID, relation: Relation, staleSeconds: Int): List<StoredSynastry> = jdbc.query(
		"$SELECT where s.owner_id = ? and s.relation = ? and s.status = 'DONE' order by s.score desc, s.created_at, s.id",
		row, staleSeconds, ownerId, relation.name,
	)

	// 아래 함수들은 궁합 id 만 받는 것이 있다. 그 궁합이 소유자의 것인지는 서비스가 [find] 로 먼저 본다.

	/** 완성된 네 영역의 글. 없으면(옛 행에서 리포트를 만들지 않았다) null. */
	fun findReport(id: UUID): SynastryReport? = jdbc.queryForList(
		"select body::text from synastry_reports where synastry_id = ? and status = 'DONE'", String::class.java, id,
	).firstOrNull()?.let { mapper.readValue(it, SynastryReport::class.java) }

	/**
	 * 같은 궁합의 생성을 처음부터 다시 시작할 자리를 잡는다([CLAIMABLE] 인 행만). 점수는 [score](지금의 내 차트로 다시 계산한 것)로 바꾸고,
	 * 완성 · 받아 감 · 알림 표시를 비운다(결정 053 의 8). 한 문장이라 동시에 불러도 한 요청만 잡는다. 못 잡았으면 null.
	 */
	fun claim(ownerId: UUID, id: UUID, score: Int, chartHash: String, staleSeconds: Int): SynastryRun? = jdbc.query(
		"""
		update synastries set status = 'WRITING', score = ?, started_at = now(), active_at = now(), done_at = null, seen_at = null, notified_at = null
		where owner_id = ? and id = ? and $CLAIMABLE
		returning id, started_at
		""".trimIndent(),
		run, score, ownerId, id, staleSeconds, chartHash,
	).firstOrNull()

	/** 소유자의 궁합 가운데 점수를 다시 계산할 수 있는 것(상대 출생 정보가 있는 것). */
	fun rescorable(ownerId: UUID): List<Rescorable> = jdbc.query(
		"""
		select id, relation, partner_birth_date, partner_birth_time, partner_place_id from synastries
		where owner_id = ? and partner_birth_date is not null and relation is not null
		""".trimIndent(),
		{ rs, _ ->
			Rescorable(
				rs.getObject("id", UUID::class.java),
				Relation.valueOf(rs.getString("relation")),
				BirthProfile(rs.getDate("partner_birth_date").toLocalDate(), rs.getTime("partner_birth_time")?.toLocalTime(), rs.getObject("partner_place_id") as Int?),
			)
		},
		ownerId,
	)

	/** 점수만 바꾼다(내 출생 정보가 바뀌었을 때 — 글을 쓴 차트 `owner_chart_hash` 는 그대로 두어 "다시 써야 함" 이 남는다). */
	fun rescore(id: UUID, score: Int) {
		jdbc.update("update synastries set score = ? where id = ?", score, id)
	}

	/** 그 생성이 살아 있다고 적는다. 아직 그 생성의 자리일 때만. */
	fun beat(run: SynastryRun) {
		jdbc.update("update synastries set active_at = now() where id = ? and status = 'WRITING' and started_at = ?", run.id, run.startedAt)
	}

	/** 아직 그 생성의 자리인지. 행이 지워졌거나, 실패로 끝났거나, 다른 생성이 자리를 잡았으면 false. */
	fun owns(run: SynastryRun): Boolean = jdbc.queryForObject(
		"select count(*) from synastries where id = ? and status = 'WRITING' and started_at = ?",
		Int::class.java, run.id, run.startedAt,
	) == 1

	/**
	 * 요약과 네 영역을 저장하고 `DONE` 으로 바꾼다. [chartHash] 는 이 글을 쓴 내 차트다. 한 문장이라 둘이 함께 들어가거나 함께 빠진다.
	 * 그 사이 행이 지워졌거나 다른 생성이 자리를 잡았으면 아무것도 하지 않고 false.
	 */
	fun complete(run: SynastryRun, summary: SynastrySummary, report: SynastryReport, chartHash: String): Boolean = jdbc.queryForList(
		"""
		with done as (
			update synastries set status = 'DONE', headline = ?, keywords = ?::jsonb, owner_chart_hash = ?, done_at = now()
			where id = ? and status = 'WRITING' and started_at = ?
			returning id
		)
		insert into synastry_reports (synastry_id, status, body) select id, 'DONE', ?::jsonb from done
		on conflict (synastry_id) do update set status = 'DONE', body = excluded.body, updated_at = now()
		returning synastry_id
		""".trimIndent(),
		UUID::class.java,
		summary.headline, mapper.writeValueAsString(summary.keywords), chartHash, run.id, run.startedAt, mapper.writeValueAsString(report),
	).size == 1

	/** `FAILED` 로 바꾼다. 아직 그 생성의 자리일 때만(아니면 false). */
	fun fail(run: SynastryRun): Boolean =
		jdbc.update("update synastries set status = 'FAILED' where id = ? and status = 'WRITING' and started_at = ?", run.id, run.startedAt) == 1

	/** 그 궁합의 소유자. 지워졌으면 null. */
	fun ownerOf(id: UUID): UUID? = jdbc.queryForList("select owner_id from synastries where id = ?", UUID::class.java, id).firstOrNull()

	/** 소유자의 궁합 가운데 [ids] 를 지우고 지운 수를 준다. 남의 것 · 없는 것은 지우지 않는다(수가 모자란다). */
	fun deleteAll(ownerId: UUID, ids: Set<UUID>): Int = jdbc.update("delete from synastries where owner_id = ? and id = any(?)") { ps ->
		ps.setObject(1, ownerId)
		ps.setArray(2, ps.connection.createArrayOf("uuid", ids.toTypedArray()))
	}

	/** 소유자의 궁합 하나를 지운다(리포트는 `on delete cascade`). 남의 것이면 지우지 않고 false. */
	fun delete(ownerId: UUID, id: UUID): Boolean = jdbc.update("delete from synastries where owner_id = ? and id = ?", ownerId, id) == 1
}
