package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 관리자의 행동을 감사 기록에 남겨 달라고 알린다(결정 015). 누가 했는지는 이 요청의 관리자 세션에서 얻는다.
 * 일이 끝난 뒤에 부른다. 글(메모·문의·FAQ)·이름·비밀번호를 [detail] 에 넣지 않는다 — 코드 값만.
 */
@Component
class AdminAudit(private val events: ApplicationEventPublisher, private val current: CurrentAdmin) {

	/** [targetId] 는 id 다(UUID, 또는 앱 설정의 플랫폼 코드). 글을 넣지 않는다. */
	fun record(action: AuditAction, targetType: String? = null, targetId: Any? = null, detail: Map<String, Any> = emptyMap()) {
		val admin = current.get()
		events.publishEvent(AuditEvent(action, AuditActor.admin(admin.id, admin.loginId), targetType, targetId?.toString(), detail = detail))
	}
}
