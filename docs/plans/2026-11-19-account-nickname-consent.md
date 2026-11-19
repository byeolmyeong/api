---
type: plan
status: approved
tags: [닉네임, 마케팅 수신 동의, 계정]
last_reviewed: 2026-11-19
---
# 가입 닉네임 · 마케팅 수신 동의 · GET/PATCH /account — 서버

> 상태: 승인(2026-11-19, 소유자 "엉 진행해"(순서)·"엉 다 해줘") · 기준 2026-11-19 · 앱 결정 039 · 앱 계획 `../byeolmyeong-app/docs/plans/2026-11-19-social-email-nickname.md` 의 "2. server-request"
> 이메일(필수) 부분(S2 · S4 의 이메일 · V19 · 결정 024)은 다른 세션의 PR #19(`feat/require-email`)가 맡는다(소유자 선택). 이 계획은 그 위에 닉네임·마케팅 동의·계정 조회/변경만 더한다.

## 목표
- 무엇을: 가입 때 닉네임(필수)과 마케팅 수신 동의(선택)를 계정과 함께 저장하고, `GET/PATCH /account` 로 보고 바꾸게 한다.
- 누구를 위해: 닉네임으로 불리는 사용자, 소식 메일 동의를 관리해야 하는 소유자.
- 끝나면: 새 가입은 닉네임이 있는 계정이다. 동의는 일시로 남고 마이에서 철회할 수 있다. 탈퇴하면 함께 지워진다.

## 결정된 전제
- 앱 결정 039(소유자 2026-11-19): 닉네임·출생 정보는 가입 화면에서, 마케팅은 별도 선택 동의(정보통신망법 50조). 메일 발송·2년 동의 확인(S8)은 범위 밖.
- 서버 결정 024(PR #19, f239294): 이메일은 공급자가 확인한 값(구글·애플 email_verified, 카카오는 준 값), 새 사람 + 이메일 없음 400 `EMAIL_REQUIRED`, 받은 그대로 저장. 이 계획은 그 동작을 그대로 둔다.
- 처리방침·약관 개정 공개됨(랜딩 1dffda4): 닉네임(필수), 마케팅 동의 여부·동의/철회 일시(선택), 보유는 탈퇴까지(동의 기록은 철회 또는 탈퇴까지).

## 내 결정 (묻지 않고 고른 기본값)
- 닉네임은 앞뒤 공백을 뗀 뒤 **한글(가~힣) · 영문만 2~10자**, 숫자 · 공백 · 기호 · 이모지 안 됨, **중복 안 됨**(영문 대소문자는 같은 이름, `lower(nickname)` 유일 색인). 틀리면 400 `INVALID_NICKNAME`, 겹치면 409 `NICKNAME_TAKEN`. 가입 전 확인 `POST /auth/nickname-check`. 저장은 앞뒤 공백을 뗀 값. (소유자 2026-11-19 — 첫 안 "1~20자 · 중복 허용" 을 바꿈)
- 마케팅 동의는 `accounts.marketing_consented_at`(null = 동의 안 함). 같은 값으로 다시 보내면 일시를 바꾸지 않는다. 바뀔 때만 감사 기록(동의/철회, 값 없이).
- `PATCH /account` 는 보낸 칸만 바꾼다(빈 본문이면 그대로 돌려준다).
- 옛 계정(닉네임 없음)은 `GET /account` 에서 null. 로그인은 막지 않는다.

## 범위 밖
- 이메일(PR #19). 메일 발송·동의 확인 메일(S8). 관리 화면에 닉네임 보이기.

## 전역 제약
- 결정 025, 마이그레이션 `V20__account_nickname_consent.sql`(더하기만, null 허용).
- 닉네임·이메일은 로그·감사 기록·오류 메시지에 넣지 않는다.
- 계약 표는 경로를 바꾸는 커밋에서 같이 고친다. 확정되면 앱 세션에 한 줄로.

## 태스크
### 1. adr-025 · 상태: 완료
- 할 것: `docs/decisions/025-nickname-marketing-consent.md`. 결정 021 앞머리에 "이름은 받지 않는다"를 025 가 대체한다고 덧붙인다(024 표시는 그대로).
- 검증: 파일, 021 앞머리 diff.
- 결과 요약: `docs/decisions/025-nickname-marketing-consent.md`, 결정 021 앞머리에 024·025 대체 표시.

### 2. migration-v20 · 상태: 완료
- 할 것: `accounts.nickname text` · `accounts.marketing_consented_at timestamptz`.
- 검증: Flyway 적용(통합 테스트 시작), 기존 테스트 통과.
- 결과 요약: `V20__account_nickname_consent.sql`(nickname · marketing_consented_at, null 허용).

### 3. signup-nickname-consent · 상태: 완료
- 할 것: `POST /auth/signup` 이 `nickname`(필수) · `marketingConsent`(기본 false)를 받아 계정·출생 정보와 같은 트랜잭션에 저장. 닉네임 검사는 출생 정보 검사보다 먼저(틀리면 가입권을 쓰지 않는다). 계약 표 같은 커밋.
- 검증: 빨강 → 초록. 닉네임 없음·빈 값·공백만·21자·줄바꿈·제어 문자 400 `INVALID_NICKNAME`(가입권은 남는다), 이모지 20개 통과, 앞뒤 공백은 떼고 저장, 동의 true 면 일시 있음·false/없음이면 null.
- 결과 요약: `auth/Nickname.kt`(normalizeNickname), `POST /auth/signup` 에 nickname · marketingConsent. 계약 표.

### 4. account-get-patch · 상태: 완료
- 할 것: `GET /account` → `{ nickname|null, email|null, provider, marketingConsent, marketingConsentAt|null }`, `PATCH /account { nickname?, marketingConsent? }` → 같은 모양. 감사 기록(동의/철회). 계약 표 같은 커밋.
- 검증: 빨강 → 초록. 세션 없으면 401, 켜고 끄면 일시가 생기고 사라짐, 같은 값이면 일시 그대로, 닉네임 규칙 400, 빈 본문은 그대로, 감사 기록에 닉네임·이메일 없음.
- 결과 요약: `GET/PATCH /account`(AuthController·AuthService·AccountRepository), 감사 MARKETING_CONSENT_CHANGED, 앱 경로 CORS 에 PATCH. 계약 표.

### 5. delete-account · 상태: 완료
- 할 것: `DELETE /account` 뒤 그 계정의 닉네임·동의·이메일이 남지 않는지 테스트.
- 검증: 탈퇴 뒤 `accounts` 에 그 행 0건.
- 결과 요약: 탈퇴 뒤 닉네임·이메일·동의 0건 테스트.

### 6. notify-app · 상태: 완료
- 할 것: 확정된 계약을 앱 세션에 한 줄로. 스테이징 배포는 앱 가입 화면(닉네임)과 같은 날 — 서버가 닉네임을 필수로 받으면 옛 가입 화면은 400 이다.
- 결과 요약: 앱 세션에 계약 한 줄(2026-11-19).

## 검증 (전체)
- `./gradlew build` 통과, 새 테스트 모두 빨강을 거쳤다. 독립 리뷰.

## 리뷰 초점
1. 닉네임 길이를 UTF-16 길이로 세어 이모지가 2로 세지지 않는가.
2. 닉네임이 틀린 가입 요청이 가입권을 써 버리지 않는가(다시 보낼 수 있어야 한다).
3. 닉네임·이메일이 로그·감사 기록·오류 응답에 나오지 않는가.
4. `PATCH /account` 가 남의 계정을 바꿀 길이 없는가(소유자는 세션에서만).
5. 옛 계정(닉네임·이메일 없음)이 `GET /account` 에서 깨지지 않는가.

## 위험 · 되돌리기
- 앱 배포와 어긋나면 가입이 400 → 스테이징은 앱 가입 화면과 같은 날.
- 되돌리기: 열 추가만이라 코드를 되돌려도 DB 는 그대로 둘 수 있다.
- PR #19 가 바뀌면 이 브랜치를 그 위로 다시 올린다.

## 실행 기록
- 결정: 처음 계획(이메일 포함)의 결정 024·공급자 이메일 작업은 PR #19 와 겹쳐 버렸다(백업 브랜치 `backup/account-email-nickname-dup`) — 소유자가 "서버는 PR #19" 를 골랐다 — 틀리면 비용: 없음(PR #19 의 동작이 기준).
- 1~2: 결정 025(16b8034), V20(ff8bcc8). PR #19 최신(f239294) 위로 다시 올림(충돌 없음).
- 3: 빨강(닉네임 테스트 3건) → 초록. `SocialLoginIntegrationTest` → `tests 24 fail 0`, 애플 11 · 속도 제한 1 · 공개 경로 3 통과. 기존 가입 요청에 닉네임을 더함(테스트의 의도는 그대로).
- 4: 빨강(6건) → 초록. `AccountApiIntegrationTest tests 6 fail 0` · `CorsIntegrationTest tests 5 fail 0` · `AdminCorsIntegrationTest tests 3 fail 0`.
  - 결정: 앱 경로 CORS 에 PATCH 를 연다(계약은 PATCH 그대로) — 부분 변경이라 PATCH 가 맞고, 앱 세션이 PUT 대안을 물었다. 관리 화면 CORS 테스트의 "앱 경로에 PATCH 를 열지 않는다" 줄을 "앱 주소는 허용, 관리 화면 주소는 403" 으로 바꿨다 — 틀리면 비용: 없음(관리 화면 주소의 분리는 그대로 확인된다).
- 5: `AccountApiIntegrationTest tests 7 fail 0`. 기존 동작(계정 행 삭제)을 지키는 테스트라 빨강 없음.
- 6: 앱 세션에 계약 전달(2026-11-19).

### 2026-11-19 소유자 결정 반영(다른 세션이 이어받음)
- 결정: 닉네임 규칙을 한글 · 영문 2~10자 · 중복 금지로 바꾸고 가입 전 확인 경로를 더했다 — 소유자가 시안 검토 중 정함 — 틀리면 비용: 앱 닉네임 단계와 어긋남(앱 계획 `2026-11-19-onboarding-design.md` 태스크 7 과 같은 규칙).
- 결정: V20 이 아직 develop 에 없어서 새 마이그레이션 대신 V20 에 유일 색인을 더했다 — 틀리면 비용: 이 브랜치를 로컬 DB 에 이미 돌린 개발자는 Flyway 체크섬이 어긋난다(로컬 DB 를 지우면 된다).
- 빨강: `./gradlew test --tests …SocialLogin… …AccountApi… …SessionPublicEndpoints… …AuthRateLimit…` → 39 tests completed, 7 failed(새 규칙 · 409 · 확인 경로).
- 초록: `./gradlew test --tests 'com.byeolmyeong.api.auth.*' --tests '*ArchitectureTest*'` → BUILD SUCCESSFUL.
- 전체: `./gradlew build` → BUILD SUCCESSFUL in 2m 37s, 테스트 537개 · 실패 0.
- 독립 리뷰: 409 때 PostgreSQL 로그(DETAIL)에 닉네임이 남음 → 바꾸기 전에 먼저 확인하도록 고침(색인은 동시 경합만). 빠진 테스트 3개 더함. 동시 가입(같은 사람 · 가입권 두 장)이 닉네임 · 출생 정보를 덮어쓸 수 있는 것은 원래 있던 문제라 남김.
- 고친 뒤: `./gradlew test --tests 'com.byeolmyeong.api.auth.*' --tests '*ArchitectureTest*'` → BUILD SUCCESSFUL.
