---
type: plan
status: approved
tags: [약관 동의, 푸시 수신 동의, 가입, 계정]
last_reviewed: 2026-11-19
---
# 가입 때 약관 동의 기록 · 푸시 수신 동의 — 서버

> 상태: 승인(소유자 2026-11-19 "바로 시작해") · 기준 2026-11-19 develop `872745a` · 앱 계획 `../byeolmyeong-app/docs/plans/2026-11-19-onboarding-design.md`(앱 저장소 `docs/onboarding-design` 브랜치)의 서버 쪽 · 시안: 피그마 https://www.figma.com/design/PNrWFsXHhF8crQkLA3SYBn 보드 "6 약관 동의"

## 목표
- 무엇을: 가입할 때 필수 약관 두 개(이용약관 · 개인정보 수집 · 이용)에 동의한 **버전과 시각**을 계정에 남기고, 선택 항목 "푸시 알림 받기" 의 동의 여부와 시각을 남긴다. 푸시 동의는 마이에서 바꿀 수 있다.
- 누구를 위해: 약관 동의를 증명해야 하는 소유자, 알림을 끄고 켜는 사용자.
- 끝나면: 새 계정마다 `terms_version` · `terms_agreed_at` · `privacy_version` · `privacy_agreed_at` 이 있고, 푸시 동의는 일시로 남는다. 약관에 동의하지 않은 가입은 서버가 막는다.

## 결정된 전제
- 결정 021(가입권 2단계 · 쿠키), 024(이메일 필수 — PR #19), **025(닉네임 · 마케팅 동의 — 서버 세션 `feat/account-nickname-consent`, V20)**. 이 계획은 025 위에 얹는다.
- 마케팅 수신 동의(`marketingConsent` · `marketing_consented_at`)는 025 가 이미 맡는다. 여기서 다시 만들지 않는다.
- 소유자(2026-11-19): 약관 단계는 닉네임보다 먼저, 필수 2 · 선택 2(푸시 알림 받기 · 마케팅 정보 수신). 야간(21~08시) 광고는 보내지 않으므로 야간 동의는 받지 않는다.

## 번호
- PR #22(결정 025 · V20, 닉네임 · 마케팅 동의 · `GET/PATCH /account`)는 2026-11-19 develop 에 합쳐졌다(`872745a`). 이 계획은 **결정 026 · `V21`** 로 따로 한다.

## PR #22 에 이미 들어간 것 (다시 만들지 않는다)
- 닉네임: 한글(가~힣) · 영문만 2~10자, 중복 금지(`lower(nickname)` 유일 색인, 409 `NICKNAME_TAKEN`), `POST /auth/nickname-check { signupToken, nickname }` → `{ available }`. 바꾸기 전에 먼저 확인한다(색인 위반은 DB 로그에 값을 남긴다).

## 이 계획에 더하는 것: 프로필 (소유자 2026-11-19)
- 브랜드 그림 8종 중 하나(앱 `assets/brand/avatar-1~8.svg`). `accounts.avatar smallint`(1~8, null = 옛 계정). 가입 때 `avatar` 필수, `GET/PATCH /account` 에 `avatar`. 범위 밖이면 400 `INVALID_AVATAR`. → 태스크 2 · 3 · 4 에 함께 넣는다.

## 내 결정 (묻지 않고 고른 기본값)
- 약관 버전은 서버 설정 한 곳: `byeolmyeong.legal.terms-version` · `byeolmyeong.legal.privacy-version`(문자열, 예: `2026-11-19`). 앱은 보여 준 버전을 보낸다. 서버의 지금 버전과 **같아야** 받는다. 다르거나 없으면 400 `TERMS_REQUIRED`(가입권은 쓰지 않는다 — 앱이 약관 단계로 돌아가 다시 보낸다).
- 동의 시각은 서버 시각(`now`)이다. 앱이 보낸 시각은 받지 않는다.
- 푸시 동의는 `accounts.push_consented_at timestamptz`(null = 동의 안 함). 운영체제 알림 권한 · 기기 토큰은 이 계획에 없다(푸시 기능 계획에서). 마케팅 푸시는 025 의 마케팅 동의가 함께 있어야 보낸다(발송 계획에서 지킨다).
- 감사 기록: 가입 때 `TERMS_AGREED`(약관 · 처리방침 버전만), 푸시 동의가 바뀔 때 `PUSH_CONSENT_CHANGED`(켬/끔만). 이메일 · 닉네임은 넣지 않는다.
- 옛 계정(약관 칸이 빈 계정)은 로그인을 막지 않는다. 다시 동의받기는 약관 개정 계획에서.

## 범위 밖
- 약관 개정 때 다시 동의받기 · 재동의 화면. 푸시 발송 · 기기 토큰 · 운영체제 권한. 마케팅 동의(025). 관리 화면에 동의 기록 보이기.

## 전역 제약
- Spring Boot 4.1 · Kotlin · JVM 21 · Flyway. 마이그레이션은 더하기만, 모두 null 허용(옛 계정 때문에).
- 계약을 바꾸면 `docs/architecture.md` 의 계약 표를 같은 커밋에서 고친다(CLAUDE.md 규칙).
- 오류 이름: `TERMS_REQUIRED`(400). 기존 `INVALID_NICKNAME`(025) · `UNDER_AGE`(403) · `SIGNUP_EXPIRED`(401)와 판정 순서: 가입권 → 약관 → 닉네임 → 프로필 → 출생 정보 → 만 14세.

## 태스크

### 1. adr-terms-push · 상태: 완료
- 읽을 것: 결정 021 · 024 · 025(서버 세션 브랜치), 이 계획 "내 결정"
- 할 것: `docs/decisions/026-terms-push-avatar.md`(번호는 develop 과 원격 브랜치를 모두 훑어 빈 것 — 지금 develop 은 025 까지). 약관 버전 · 푸시 동의 · 프로필.
- 주는 것: 결정 번호
- 하지 말 것: 합쳐진 결정의 본문을 고쳐 쓰지 않는다. 이유: 대체 표시만 한다(골든 룰).
- 검증: `ls docs/decisions | cut -c1-3 | sort | uniq -d` → 비어 있음

### 2. migration · 상태: 완료
- 할 것: `accounts.terms_version text` · `accounts.terms_agreed_at timestamptz` · `accounts.privacy_version text` · `accounts.privacy_agreed_at timestamptz` · `accounts.push_consented_at timestamptz` · `accounts.avatar smallint`(1~8 check). `V21__terms_push_avatar.sql`.
- 하지 말 것: 기존 행을 채우지 않는다(backfill 없음). 이유: 옛 계정은 동의한 버전을 모른다.
- 검증: 마이그레이션이 깨끗한 DB 와 V19/V20 DB 양쪽에서 돈다(Testcontainers 통합 테스트가 Flyway 를 탄다)

### 3. signup-terms · 상태: 완료
- 읽을 것: `auth/AuthController.kt`(`SignupRequest`), `auth/LoginService.kt`(`signup`), 계약 표 `POST /auth/signup`
- 할 것: `SignupRequest` 에 `termsVersion: String?` · `privacyVersion: String?` · `pushConsent: Boolean?`(기본 false) · `avatar: Int?`(필수 1~8, 아니면 400 `INVALID_AVATAR`). 지금 버전과 다르거나 없으면 400 `TERMS_REQUIRED`, 가입권은 그대로. 맞으면 계정 · 출생 정보 · 닉네임(025)과 같은 트랜잭션에 버전 · 시각 · 푸시 동의 시각을 저장하고 `TERMS_AGREED` 감사 기록. 계약 표 같은 커밋.
- 주는 것: 계약 `POST /auth/signup { …, termsVersion, privacyVersion, pushConsent? }`
- 하지 말 것: 앱이 보낸 시각을 저장하지 않는다. 이유: 동의 시각은 서버가 증명한다.
- 검증: 빨강 → 초록. 버전 없음 · 옛 버전 → 400 `TERMS_REQUIRED` 이고 가입권 남음(같은 가입권으로 다시 보내 201), 맞으면 네 칸이 채워짐, `pushConsent` true 면 일시 · false/없음이면 null, 감사 기록에 버전만

### 4. account-push · 상태: 완료
- 읽을 것: 025 의 `GET/PATCH /account`
- 할 것: 응답에 `pushConsent: boolean` · `pushConsentAt: string | null` · `avatar: number | null`, `PATCH /account { pushConsent?, avatar? }`. 같은 값이면 일시 그대로, 바뀔 때만 `PUSH_CONSENT_CHANGED`. 계약 표 같은 커밋.
- 검증: 빨강 → 초록. 켜고 끄면 일시가 생기고 사라짐, 같은 값이면 그대로, 세션 없으면 401

### 5. delete-account · 상태: 완료
- 할 것: `DELETE /account` 뒤 그 계정의 약관 · 푸시 칸이 남지 않는지 테스트(계정 행과 함께 지워진다). 감사 기록은 결정 015 대로 남는다.
- 검증: 탈퇴 뒤 해당 칸 0건

### 6. notify-app · 상태: 대기
- 할 것: 확정된 계약(`termsVersion` · `privacyVersion` · `pushConsent` · `TERMS_REQUIRED` · 계정 응답의 `pushConsent*`)과 지금 약관 버전 값을 앱 쪽에 한 줄로 알린다. 스테이징 배포는 앱 온보딩과 같은 날(서버가 약관 버전을 필수로 받으면 옛 가입 화면은 400).

## 검증
- `./gradlew build` 통과(서버 `/verify`). 새 테스트가 위 태스크 검증을 모두 덮는다.

## 리뷰 초점
1. 약관이 틀린 가입 요청이 가입권을 써 버리지 않는가 — 태스크 3 테스트.
2. 남은 가입권으로 다시 와서 계정이 이미 있을 때 약관 시각을 덮어쓰지 않는가 — 태스크 3 에 테스트 추가.
3. 설정의 약관 버전이 비었을 때 모든 가입이 막히는지(서버가 시작할 때 빈 값이면 실패하게) — 태스크 3.
4. 감사 기록 · 로그에 이메일 · 닉네임이 들어가지 않는가 — 태스크 3 · 4.
5. 푸시 동의를 켠 채 마케팅 동의 없이 마케팅 푸시가 나갈 길이 있는가(지금은 발송이 없으니 결정 문서에 규칙만) — 태스크 1.

## 자동 반복
- 없음(태스크마다 사람 확인 없이 테스트로 끝난다. 상한 3회 미달이면 멈춘다).

## 위험 · 되돌리기
- 약관 버전을 바꾸는 날 옛 앱의 가입이 400 이 된다. 약관 개정 계획에서 앱 업데이트 안내(`GET /app-config`)와 함께 바꾼다.
- 되돌리기: 칸은 더하기만이라 코드만 되돌리면 된다.

## 실행 기록
- 1: 결정 026 `docs/decisions/026-terms-push-avatar.md`(약관 버전 · 푸시 동의 · 프로필 · 광고성 푸시는 푸시 + 마케팅 동의 둘 다 · 야간 없음). develop · 원격 브랜치 모두 026 · V21 없음 확인. `ls docs/decisions | cut -c1-3 | sort | uniq -d` → 빈 출력(26개 번호 중복 0).
- 2: `V21__terms_push_avatar.sql`(여섯 칸, null 허용, `avatar` 1~8 check, backfill 없음) · 테스트 `TermsPushAvatarMigrationIntegrationTest`(깨끗한 DB + 같은 DB 의 다른 스키마에서 V19 · V20 까지 돌린 뒤 올리기). 빨강 `tests 2 fail 2`(칸 없음) → 초록 `tests 2 fail 0`. 아키텍처 "데이터" 의 `accounts` 칸 갱신.
- 3: `POST /auth/signup` 에 `avatar`(필수 1~8) · `termsVersion` · `privacyVersion`(필수, 설정과 같아야) · `pushConsent`(선택). 판정 순서 가입권 → 약관 → 닉네임 → 프로필 → 출생 정보 → 만 14세. 새 계정에만 저장, `TERMS_AGREED`(버전만). 설정이 비면 시작 실패(`LegalVersions`). 빨강 `SocialLoginIntegrationTest tests 34 fail 7` · `LegalVersionsStartupTest tests 1 fail 1` → 초록 가입 34 · 애플 11 · 시작 1 · 속도 제한 1 · 공개 경로 3 모두 fail 0. 기존 가입 요청(소셜 · 애플)에 약관 버전 · 프로필을 더함.
- 4: `GET /account` 에 `pushConsent` · `pushConsentAt` · `avatar`, `PATCH /account { pushConsent?, avatar? }`(같은 값이면 일시 그대로, 바뀔 때만 `PUSH_CONSENT_CHANGED`, 프로필 범위 밖이면 400 `INVALID_AVATAR` 이고 같이 보낸 칸도 그대로). 빨강 `AccountApiIntegrationTest tests 11 fail 3` → 초록 `tests 11 fail 0`.
- 5: 테스트만(계정 행 삭제로 지워지는 기존 동작). 탈퇴 뒤 약관 · 푸시 · 프로필 칸 0건, `TERMS_AGREED` 1건 그대로(결정 015). 빨강은 삭제를 잠깐 막아(커밋 안 함) `tests 1 fail 1`("그 계정의 칸이 남지 않는다")로 확인 → 되돌려 `SocialLoginIntegrationTest tests 35 fail 0`.
- 전체: `./gradlew build` → BUILD SUCCESSFUL in 2m 47s, 테스트 551개(82 클래스) · 실패 0 · 오류 0. 6(앱에 알리기)은 이 세션에서 하지 않고 소유자가 전한다.

### 2026-11-19 독립 리뷰 반영
- 리뷰 1(동시 가입): 같은 사람의 가입권 두 장이 동시에 오면 둘 다 "새 계정"으로 보고 약관 · 푸시 · 프로필 · 닉네임 · 출생 정보를 덮어쓰고 `TERMS_AGREED` 가 두 번 남았다 → 새 계정인지를 `insert … on conflict do nothing returning id` 결과로 판정(`AccountRepository.create`). 테스트: 기존 동시 가입 테스트를 latch 로 10회 + 계정마다 `TERMS_AGREED` · `PROFILE_SAVED` 1건, 첫 요청이 커밋하기 전에 뒤 요청을 끼워 넣는 결정적 테스트(처음 값 그대로 · 출생 정보 0 · 감사 기록 0). 빨강 `SocialLoginIntegrationTest tests 36 fail 2`(round 0 에서 `TERMS_AGREED` 2건, 닉네임 덮어씀) → 초록 가입 36 · 애플 11 fail 0. 결정 026 3번 · 아키텍처 가입 설명에 동시 요청 한 줄.
