package com.byeolmyeong.api.audit

import com.byeolmyeong.api.web.AuditEvent
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** `detail` 의 글자 값이 가질 수 있는 모양: 코드뿐이다. 한글 글·이메일·긴 글은 이 모양이 아니다. */
private val CODE = Regex("[A-Za-z0-9._-]{1,40}")

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
}

@Repository
class AuditRepository(private val jdbc: JdbcTemplate) {

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
