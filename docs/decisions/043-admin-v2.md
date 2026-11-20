---
type: decision
tags: [관리자, 관리자 API, 사용자 관리, 현황, 접속 차트, 임시 비밀번호, 비밀번호 재발급, 푸시 발송, 감사 기록]
status: active
last_reviewed: 2026-11-20
superseded_by:
---
# 043. 관리자 API v2 — 사용자 관리 · 현황과 접속 차트 · 임시 비밀번호 · 푸시 발송 · 감사 기록 보관

> 상태: 활성 · 결정일 2026-11-20(소유자 "어드민은 어떤거부터 있어야하는지 1.0.0 기반으로 다시 해야하지 않나", "유저관리랑 이런거 다 있어야하지 않나", "오늘 이번달 이번주 이런거 접속 차트같은 거 있으면 좋을 것 같은데", "비밀번호 재발급 이렇게 하든가", "아니 잠기는거 없애", "열어놔야지") · 결정 014(관리자 API)를 넓힌다 — 014 · 018 은 활성으로 두고 이 결정이 더한 것만 적는다 · 이용 정지는 결정 044 · 문의 답변은 결정 039 · 최신 버전은 040 · 기기 토큰과 보내기는 042 · 시안: 앱 저장소 캔버스 "어드민" D1 ~ D29 · 어드민 결정 003(화면 · 동작 규칙)
> 2026-11-20: 8번의 문의 `answerDraft` 는 같은 `PATCH /admin/inquiries/{id}` 라 계획 S⑧ 이 결정 039 의 6 으로 먼저 만들었다(모양은 이 결정 그대로 — 상태 · 알림을 바꾸지 않는다).

## 맥락
- 지금 관리자 API 는 문의 · 현황 수치(개수) · FAQ · 앱 설정 · 관리자 관리 · 감사 기록만 있다. 1.0.0 을 운영하려면 사용자를 찾고 조치(정지 · 탈퇴 대행 · 생성 다시 하기)할 수 있어야 하고, 접속 흐름을 봐야 하며, 광고 푸시를 보낼 수 있어야 한다.
- 관리자 비밀번호는 만든 사람이 정해 주는 방식이라 만든 사람이 비밀번호를 안다.

## 결정
1. **사용자**(새 경로, 모두 감사 기록 대상 조치만 기록)
   - `GET /admin/users?q=&status=&provider=&joinedFrom=&joinedTo=&page=&size=` → `{ items: [UserRow], page, size, total }` 가입이 늦은 것부터. `q` 는 닉네임 일부 또는 이메일 앞부분(대소문자 무시). `status` ACTIVE · SUSPENDED. `provider` KAKAO · APPLE · GOOGLE. `joinedFrom` · `joinedTo` 는 한국 날짜(`YYYY-MM-DD`, 끝 날짜 포함, 최대 366일). `size` 10 · 20 · 50 · 100(기본 20).
   - `UserRow` = `{ id, nickname, profileNo, emailMasked, provider, joinedAt, lastSeenAt, reports: { me, timing } , status }`. 이메일은 서버가 가려서 준다(앞 4글자 + `****@도메인`) — 원문은 어떤 관리자 응답에도 없다. `reports` 는 각각 NONE · GENERATING · DONE · FAILED.
   - `GET /admin/users/{id}` → `UserDetail` = `UserRow` + `{ consents: { termsVersion, privacyVersion, pushAt, marketingAt }, todaySky: { on, time }, birth: { registered, hasTime, hasPlace }, generations: [{ kind, status, at }], devices: [{ platform, appVersion, lastSeenAt }], inquiries: [{ id, category, status, createdAt }], recent: [{ at, action }], suspension: { at, by, reason } | null }`. **출생 정보 값(날짜 · 시각 · 장소)은 주지 않는다** — 있는지만.
   - `POST /admin/users/{id}/suspend { reason }`(1~200자) · `POST /admin/users/{id}/unsuspend { reason }`(1~200자) — 결정 044. 이미 그 상태면 409 `ALREADY_SUSPENDED` · `NOT_SUSPENDED`.
   - `DELETE /admin/users/{id}` 본문 `{ reason, confirmNickname }` — 사용자가 요청했을 때만(사유에 요청 경로). `confirmNickname` 이 그 사람의 닉네임과 다르면 400 `CONFIRM_MISMATCH`. 지우는 일은 앱의 `DELETE /account` 와 같다(카카오 연결 끊기 · 애플 토큰 철회 · 출생 정보 · 생성 내역 · 문의 · 기기 토큰). 이벤트로 `auth` 에 맡긴다(`admin` 은 `auth` 를 모른다 — 결정 014).
   - `POST /admin/users/{id}/regenerate { kind }` — `kind` NICKNAME · REPORT_ME · REPORT_TIMING. 실패(FAILED)한 것만 다시 만든다(아니면 409 `NOT_FAILED`). 결과는 앱 알림 · 푸시가 늘 그렇듯 간다.
2. **마지막 접속**: 계정에 `last_seen_at`. 사용자 세션으로 온 요청이 고칠 때 10분에 한 번만 쓴다. 날마다 접속한 계정은 `daily_active(kst_date, account_id)` 한 줄(그날 처음일 때만, 시간대별은 `hour_kst` 첫 접속 시각). 감사 기록과 따로 둔다.
3. **현황 v2**: `GET /admin/stats` 의 `Stats` 에 더한다 — `users: { total, joinedToday, joined7d }`, `nicknames: { total }`, `reports: { me, timing, generating }`, `todaySky: { today, onDemand, prebuilt }`, `failuresToday: { nickname, report }`, `pushToday: { sent, failed }`, `inquiries: { waiting, oldestWaitingAt }`, `consents: { pushRate, marketingRate, adReachable }`. 모두 개수 · 비율(소수 셋째 자리까지)이다.
4. **접속 차트**: `GET /admin/stats/active?range=today|week|month` → `{ range, points: [{ label, value }], total, average, previousTotal }`. today 는 한국 시각 0~23시 시간대별(그 시간에 그날 처음 접속한 사람), week 는 최근 7일 일별, month 는 이번 달 1일부터 오늘까지 일별. `total` 은 기간 안 고유 접속자, `previousTotal` 은 바로 앞 같은 길이 기간. 사용자 목록 위의 수치 넷(오늘 · 이번 주 · 이번 달 접속자 · 이번 주 가입)도 이 값과 `Stats` 로 그린다.
5. **관리자 계정**
   - 추가: `POST /admin/admins { loginId }` — 비밀번호는 서버가 만든다(영문 대소문자 · 숫자 12자, `xxxx-xxxx-xxxx` 꼴). 응답 201 `{ admin: AdminAccount, temporaryPassword }` — **이 응답에서 한 번만** 보인다. `name` 은 받지 않고 `loginId` 를 그대로 쓴다(014 의 `name` 칸은 남긴다).
   - 재발급: `POST /admin/admins/{id}/password-reset` → 200 `{ temporaryPassword }`. 그 관리자의 세션을 모두 끝낸다. 자기 자신에게는 409 `SELF_NOT_ALLOWED`(자기 비밀번호는 `PUT /admin/me/password`). 018 의 `PUT /admin/admins/{id}/password`(비밀번호를 정해 주기)는 화면에서 쓰지 않고 S⑦ 정리 때 지운다.
   - 첫 로그인: 임시 비밀번호(추가 · 재발급)로 로그인하면 응답 `admin.mustChangePassword: true`. 그동안 `PUT /admin/me/password` · `POST /admin/auth/logout` · `GET /admin/me` 말고는 403 `PASSWORD_CHANGE_REQUIRED`. 바꾸면 풀린다. 새 비밀번호가 임시 비밀번호와 같으면 400 `SAME_AS_TEMPORARY`.
   - **로그인 잠금은 두지 않는다**(소유자 "잠기는거 없애"). 틀리면 401 `INVALID_CREDENTIALS` 하나 — 아이디와 비밀번호 중 무엇이 틀렸는지 가리지 않는다. 주소별 속도 제한(429)은 지금처럼 둔다(화면에 드러나지 않는 남용 막기).
6. **광고 푸시 발송**(보내는 길은 결정 042)
   - `GET /admin/push/audience` → `{ count }` — 푸시 · 마케팅 둘 다 동의 + 기기 등록 + 정지 아님.
   - `POST /admin/push/events { title, body, target }` — `title` 1~40자, `body` 1~120자, `target` 은 정해 둔 화면 키(HOME · TODAY_SKY · ME · TIMING_MONTH · TIMING_YEAR · MY). 서버가 제목 앞에 "(광고) ", 본문 끝에 줄바꿈 + "수신 거부: 마이 › 알림" 을 붙인다(화면이 붙이지 않는다). 한국 시각 21:00 ~ 08:00 은 409 `QUIET_HOURS`, 그날 이미 보냈으면 409 `DAILY_LIMIT`(관리자 누구든 하루 한 번). 202 `{ id, recipients }` 로 답하고 뒤에서 보낸다.
   - `GET /admin/push/events?page=&size=` → `{ items: [{ id, sentAt, title, recipients, succeeded, failed, sentBy }], … }` 새것부터. `GET /admin/push/status` → `{ canSend, reason: null | QUIET_HOURS | DAILY_LIMIT, sentTodayAt, sentTodayBy, nextAvailableAt }` — 화면이 버튼을 흐리게 하고 이유를 보일 때 쓴다.
7. **앱 설정 · 점검**: `Maintenance` 에 `startedAt` · `startedBy` 를 더한다(어드민 모든 화면 위 띠에 보인다 — 어드민 결정 002).
8. **감사 기록**: 행동을 더한다 — 관리자: `USER_SUSPENDED` · `USER_UNSUSPENDED` · `USER_DELETED_BY_ADMIN` · `GENERATION_RETRIED` · `PUSH_EVENT_SENT` · `ADMIN_PASSWORD_REISSUED` · `ADMIN_PASSWORD_CHANGED`. 조회에 `q`(닉네임 · 관리자 아이디)를 더한다. **보관은 1년** — 날마다 한 번 1년 지난 행을 지운다(015 의 "보관 기한 없음" 을 바꾼다). 출생 정보 · 이메일 원문 · 토큰은 넣지 않는다(그대로).
9. **시각 · 표기**: 응답 시각은 지금처럼 UTC 글자다. 화면이 한국 시각 `YYYY.MM.DD HH:mm:ss` 로 바꿔 보인다(어드민 결정 003).

## 근거
- 운영자가 사람을 찾고 조치하는 일이 문의 다음으로 많다. 이메일 원문 · 출생 정보 값은 조치에 필요 없고, 새면 가장 아프다.
- 접속은 감사 기록으로 세면 느리고 감사 기록이 커진다. 하루 한 줄이면 365일 · 수만 명도 가볍다.
- 임시 비밀번호를 서버가 만들고 첫 로그인에 바꾸게 하면 만든 사람도 새 비밀번호를 모른다.
- 광고 표기 · 야간 금지 · 수신 거부 안내는 정보통신망법. 하루 한 번은 피로를 막는 우리 규칙(앱 결정 066).

## 대안
- 사용자 목록에 이메일 원문 — 문의 답에는 앱 안 답변이 있어(결정 039) 원문이 필요 없다.
- 접속 수를 감사 기록에서 집계 — 행이 너무 많아진다.
- 로그인 5회 실패 시 잠금 — 소유자가 뺐다. 관리자 몇 명이 쓰는 화면이고, 잠기면 다른 관리자가 풀어야 해 오히려 일이 된다.

## 결과·재검토 조건
- 계약 표(`docs/architecture.md` "관리자 API")는 계획 S⑪ 이 이 결정대로 고친다.
- 사용자 수가 수십만이 되면 목록 검색(`q`)에 색인이 필요하다 — 그때 다시 본다.
