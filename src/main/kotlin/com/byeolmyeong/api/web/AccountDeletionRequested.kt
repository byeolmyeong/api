package com.byeolmyeong.api.web

import java.util.UUID

/**
 * 관리자가 사용자의 요청으로 계정을 지워 달라는 Spring 이벤트(결정 043 의 1 — 탈퇴 대행). `admin` 이 내고 `auth` 가 같은 스레드에서 듣는다
 * (`admin` 은 `auth` 를 모르고, 공용 `auth` 는 기능 패키지의 클래스를 알 수 없어 이 공용 패키지에 둔다). 지우는 일은 앱의 `DELETE /account` 와 같다.
 */
data class AccountDeletionRequested(val accountId: UUID)
