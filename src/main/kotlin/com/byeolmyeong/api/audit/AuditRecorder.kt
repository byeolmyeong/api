package com.byeolmyeong.api.audit

import com.byeolmyeong.api.web.AuditEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 다른 기능이 낸 [AuditEvent] 를 듣고 감사 기록에 넣는다(결정 015).
 * 트랜잭션 안에서 낸 이벤트는 커밋된 뒤에만 듣는다 — 되돌려진 일은 기록되지 않는다. 트랜잭션 없이 낸 이벤트는 바로 듣는다.
 * 기록의 실패는 여기서 삼킨다: 이벤트를 낸 쪽의 일(사용자의 요청)은 그대로 끝난다. 로그에는 행동 코드와 예외 이름만 남긴다.
 */
@Component
class AuditRecorder(private val audit: AuditService) {
	private val log = LoggerFactory.getLogger(javaClass)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun on(event: AuditEvent) {
		try {
			audit.record(event)
		} catch (e: Exception) {
			log.warn("event=audit_record_failed action={} error={}", event.action, e.javaClass.simpleName)
		}
	}
}
