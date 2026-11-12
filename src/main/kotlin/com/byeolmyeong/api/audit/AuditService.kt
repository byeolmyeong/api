package com.byeolmyeong.api.audit

import com.byeolmyeong.api.web.AuditEvent
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** `detail` 의 글자 값이 가질 수 있는 모양: 코드뿐이다. 한글 글·이메일·긴 글은 이 모양이 아니다. */
private val CODE = Regex("[A-Za-z0-9._-]{1,40}")

/** 누가 했는지. [ref] 는 `actorRef` 조건에 그대로 넣는 값, [label] 은 화면에 보이는 글이다(관리자는 그때의 로그인 아이디, 사용자는 `ownerRef`). */
data class AuditActorRef(val ref: String, val label: String)

/** 관리자가 보는 감사 기록 한 줄. 계정 id 는 없다. [actor] 는 `SYSTEM` 이면 null 이다. */
data class AuditLog(
	val id: UUID,
	val occurredAt: String,
	val actorType: String,
	val actor: AuditActorRef?,
	val action: String,
	val targetType: String?,
	val targetId: String?,
	val outcome: String,
	val detail: JsonNode?,
)

data class AuditLogPage(val items: List<AuditLog>, val page: Int, val size: Int, val total: Int)

/**
 * 감사 기록(결정 015). 넣기만 한다 — 고치거나 지우는 길이 없다.
 * 개인적인 것이 들어오지 않게 `detail` 을 한 번 더 거른다. 로그에는 행동 코드만 남긴다.
 */
@Service
class AuditService(private val repository: AuditRepository, private val mapper: JsonMapper) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * 자기 트랜잭션에서 한 줄을 넣는다. 이벤트를 낸 쪽의 트랜잭션은 이미 끝났고(커밋 뒤), 여기서 실패해도 그쪽은 되돌려지지 않는다.
	 * [AuditEvent.detail] 의 값이 코드 모양이 아니면 `detail` 을 통째로 버리고 행은 남긴다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun record(event: AuditEvent) {
		val clean = event.detail.all { (key, value) -> CODE.matches(key) && (value is Boolean || value is Int || (value is String && CODE.matches(value))) }
		if (!clean) log.warn("event=audit_detail_dropped action={}", event.action)
		val detail = if (clean && event.detail.isNotEmpty()) mapper.writeValueAsString(event.detail) else null
		repository.insert(event, detail)
	}

	fun list(filter: AuditFilter, page: Int, size: Int): AuditLogPage =
		AuditLogPage(repository.find(filter, page.toLong() * size, size), page, size, repository.count(filter))
}

@Repository
class AuditRepository(private val jdbc: JdbcTemplate, private val mapper: JsonMapper) {

	/** 조건이 null 이면 거르지 않는다. */
	private val filter = """
		where (?::text is null or actor_type = ?) and (?::text is null or action = ?)
			and (?::timestamptz is null or occurred_at >= ?) and (?::timestamptz is null or occurred_at < ?)
			and (?::text is null or actor_label = ?)
	"""

	private fun args(f: AuditFilter): Array<Any?> {
		val since = f.since?.let(Timestamp::from)
		val until = f.until?.let(Timestamp::from)
		return arrayOf(f.actorType, f.actorType, f.action, f.action, since, since, until, until, f.actorRef, f.actorRef)
	}

	/** 새것부터(같은 때면 id 가 큰 것부터). 행위자 id(`actor_id`)는 읽지 않는다 — 응답에 내보내지 않는다. */
	fun find(f: AuditFilter, offset: Long, limit: Int): List<AuditLog> = jdbc.query(
		"""
		select id, occurred_at, actor_type, actor_label, action, target_type, target_id, outcome, detail::text as detail
		from audit_logs $filter order by occurred_at desc, id desc offset ? limit ?
		""",
		{ rs, _ ->
			AuditLog(
				id = rs.getObject("id", UUID::class.java),
				occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
				actorType = rs.getString("actor_type"),
				actor = rs.getString("actor_label")?.let { AuditActorRef(it, it) },
				action = rs.getString("action"),
				targetType = rs.getString("target_type"),
				targetId = rs.getString("target_id"),
				outcome = rs.getString("outcome"),
				detail = rs.getString("detail")?.let(mapper::readTree),
			)
		},
		*args(f), offset, limit,
	)

	fun count(f: AuditFilter): Int = jdbc.queryForObject("select count(*) from audit_logs $filter", Int::class.java, *args(f))!!

	/** 일어난 때는 DB 시계의 지금이다. */
	fun insert(event: AuditEvent, detailJson: String?) {
		jdbc.update(
			"""
			insert into audit_logs (id, actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
			""".trimIndent(),
			UUID.randomUUID(), event.actor.type.name, event.actor.id, event.actor.label, event.action.name,
			event.targetType, event.targetId, if (event.success) "SUCCESS" else "FAILURE", detailJson,
		)
	}
}
