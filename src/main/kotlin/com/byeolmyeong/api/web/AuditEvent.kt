package com.byeolmyeong.api.web

import java.util.UUID

/**
 * 감사 기록에 남기는 행동(결정 015). 이름이 곧 저장되는 코드이므로 바꾸지 않는다(더하기만 한다).
 * 목록과 `detail` 의 뜻은 docs/architecture.md "감사 기록" 과 같이 고친다.
 */
enum class AuditAction {
	// 관리자
	ADMIN_LOGIN, ADMIN_LOGOUT, ADMIN_PASSWORD_CHANGED, ADMIN_PASSWORD_RESET, ADMIN_CREATED, ADMIN_DEACTIVATED, ADMIN_REACTIVATED, ADMIN_BOOTSTRAPPED,
	// 다른 관리자에게 임시 비밀번호를 다시 만들어 줌(결정 043 의 5). 비밀번호는 없다
	ADMIN_PASSWORD_REISSUED,
	// 사용자 정지 · 해제 · 탈퇴 대행(결정 043 의 1 · 044). 대상 USER id, 사유는 남기지 않는다(정지 사유는 account_suspensions)
	USER_SUSPENDED, USER_UNSUSPENDED, USER_DELETED_BY_ADMIN,
	// 실패한 자세한 풀이를 다시 만들게 함(결정 043 의 1). 대상 USER id, detail 은 kind 만
	GENERATION_RETRIED,
	INQUIRY_VIEWED, INQUIRY_UPDATED,
	// 문의에 답을 저장함(결정 039). detail 은 first=true|false 만
	INQUIRY_ANSWERED,
	FAQ_CREATED, FAQ_UPDATED, FAQ_PUBLISHED, FAQ_UNPUBLISHED, FAQ_DELETED, FAQ_REORDERED,
	APP_CONFIG_UPDATED, MAINTENANCE_UPDATED,

	// 사용자
	USER_LOGIN, PROFILE_SAVED, READING_GENERATED, REPORT_REQUESTED, SYNASTRY_CREATED, SYNASTRY_REPORT_REQUESTED, SYNASTRY_DELETED,
	DIRECTION_GENERATED, SHARE_CREATED, SHARE_DELETED, INQUIRY_SUBMITTED, ACCOUNT_DELETED,
	// 마케팅 수신 동의를 켜거나 끔(결정 025). detail 은 consent=true|false 만
	MARKETING_CONSENT_CHANGED,
	// 가입으로 계정이 생기며 약관 · 처리방침에 동의함(결정 026). detail 은 termsVersion · privacyVersion 만
	TERMS_AGREED,
	// 푸시 수신 동의를 켜거나 끔(결정 027). detail 은 consent=true|false 만
	PUSH_CONSENT_CHANGED,
}

enum class AuditActorType { ADMIN, USER, SYSTEM }

/** 누가 했는지. [label] 은 관리자면 그때의 로그인 아이디, 사용자면 [ownerRef] 다. 이름·이메일을 넣지 않는다. */
class AuditActor private constructor(val type: AuditActorType, val id: String?, val label: String?) {
	companion object {
		/** 로그인하지 않은 채 일어난 일(서버가 한 일, 실패한 관리자 로그인). */
		val SYSTEM = AuditActor(AuditActorType.SYSTEM, null, null)

		fun admin(id: UUID, loginId: String) = AuditActor(AuditActorType.ADMIN, id.toString(), loginId)

		fun user(accountId: UUID) = AuditActor(AuditActorType.USER, accountId.toString(), ownerRef(accountId))
	}
}

/**
 * 감사 기록 한 줄을 남겨 달라는 Spring 이벤트. 기능 패키지와 `auth` 가 내고 `audit` 패키지가 듣는다(서로를 모른다).
 * 일이 끝난 뒤에 낸다. 트랜잭션 안에서 내면 커밋된 뒤에만 기록된다.
 *
 * 개인적인 것을 넣지 않는다: [targetId] 는 id 만, [detail] 은 코드 값만(글자는 영문·숫자·`.`·`_`·`-` 40자 이하, 그 밖에는 참·거짓과 정수).
 * 출생 정보·이름·이메일·사용자가 쓴 글·비밀번호·토큰은 어디에도 넣지 않는다. 모양이 틀린 [detail] 은 기록할 때 버려진다.
 */
class AuditEvent(
	val action: AuditAction,
	val actor: AuditActor,
	val targetType: String? = null,
	val targetId: String? = null,
	val success: Boolean = true,
	val detail: Map<String, Any> = emptyMap(),
)
