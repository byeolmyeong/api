---
type: decision
tags: [1.1.0, 공지사항, 알림함, 푸시, 밤 무음, 안드로이드 알림 채널, 관리자 API, 서버 계약]
status: active
last_reviewed: 2026-11-28
superseded_by:
---
# 055. 공지사항은 `notices` 표 하나에 두고 공개 조회는 세션 없이, 쓰기 · 공개는 관리자 API 로 하며, "알림 보내기"를 켠 공지는 공개할 때 한 번만 알림함 · 푸시로 보낸다. 서비스 알림의 밤(21:00 ~ 08:00)은 소리 없이 보낸다

> 상태: 활성 · 결정일 2026-11-28(앱 계획 `docs/plans/2026-11-27-1.1.0.md` 의 S④ 줄 — 이름 · 숫자는 서버 줄이 "제안"으로 골랐고 PR 리뷰에서 소유자가 확인한다) · 앱 결정 107(공지사항) · 104 의 9(밤 9시 ~ 아침 8시 소리 없이) · 052(알림함) · 서버 결정 014 의 9(FAQ 틀) · 015(감사 기록) · 035(알림함) · 042(푸시) · 043 의 6(광고 푸시) · 044 의 3(정지) · 046(두 서버가 함께 뜬다) — 035 의 1(종류)에 `NOTICE` 를 더하고, 042 의 4(누구에게 무엇을) 표에 `NOTICE` 줄을 더한다 · 서버 결정 054 는 나중 줄(S③ 내 궁합 링크)의 몫으로 비워 둔다 · 계약은 docs/architecture.md "API 계약" · "관리자 API"

## 맥락
- 1.1.0 은 유료 전환 · 약관 개정 · 새 기능을 앱 안에서 알릴 곳으로 공지사항을 둔다(앱 결정 107). 글은 관리자가 어드민에서 쓰고 앱은 서버에서 받는다 — 자주 묻는 질문(서버 결정 014 의 9 · 앱 결정 028)과 같은 틀이다.
- 지금의 푸시 대상 조회 둘 다 공지에 맞지 않는다: `PushService.broadcast` 는 광고 대상(푸시 + 마케팅 동의)만 고르고 `push_events.kst_date` 유일 제약(V33)으로 하루 한 번이다(043 의 6). 공지 알림은 서비스 알림이라 푸시 동의만 보고, 하루 한 번 제약 밖이다.
- `notifications.kind` 는 DB check 로 다섯 종류만 받는다(V32).
- `push.ExpoPush` 는 모든 메시지에 `sound: "default"` 를 싣고 안드로이드 채널을 고르지 않는다. 밤에 소리 없이 보내려면(104 의 9) iOS 는 `sound` 를 빼고 안드로이드는 조용한 채널로 보내야 한다(안드로이드 8 이상은 소리를 채널이 정한다).
- Expo 문서(Sending notifications › Message request format, 2026-11-28 확인): `sound` 는 iOS 만 — 빼면 소리가 없다. `channelId` 는 안드로이드만 — **기기에 그 채널이 없으면(앱이 아직 만들지 않았으면) 알림이 아예 보이지 않는다.** 빼면 "Default" 채널을 쓰고 없으면 Expo 가 만든다. 조용한 채널 `quiet` 는 1.1.0 앱이 만든다(앱 계획 A⑥).

## 결정
1. **표 `notices`**(V38 — 합칠 때 develop 의 다음 번호로 바뀔 수 있다): `id` · `category`(`NOTICE` 공지 · `UPDATE` 업데이트, check) · `title` · `body` · `status`(`DRAFT` 임시 저장 · `PUBLISHED` 공개, check) · `published_at`(공개 중일 때만 있다 — `status` 와 함께 check) · `notify`(알림 보내기 스위치, 기본 false) · `notified_at`(알림을 보낸 때, null = 아직) · `created_at` · `created_by` · `updated_at` · `updated_by`(관리자). `UPDATE` 이면서 `notify` 인 행은 DB check 로도 막는다.
   - 길이는 서버가 본다: 제목 1 ~ 60자 · 본문 1 ~ 3000자(앞뒤 공백을 뗀 코드 포인트, NUL 금지 — FAQ 와 같은 규칙). 본문은 꾸밈 없는 글자다(줄바꿈만 있을 수 있다. HTML · 마크다운이 아니다 — 앱 결정 107 의 3).
2. **공개 조회 `GET /notices`**(새 기능 패키지 `notice` — `notices` 를 읽기만 한다): 세션 없이, 공개한 것만 공개 시각이 새것부터(같으면 id 역순) `[{ id, category, title, body, publishedAt }]`. 하나도 없으면 `[]`. `Cache-Control: public, max-age=300`(앱 결정 107 의 7 · 028 처럼 5분). 로그인 전 경로 목록에는 **GET 만** 더하고 주소별 분당 120번(`byeolmyeong.rate-limit.notices-per-minute`)을 넘으면 429 — FAQ 와 같다. 점검 503 · 강제 업데이트 426 은 다른 경로와 같이 걸린다.
3. **관리자 경로 `/admin/notices`**(`admin` 패키지 — FAQ 처럼 `notice` 와 코드로 서로를 모르고 같은 표만 본다):
   - `GET /admin/notices`: 임시 저장까지 모두, 새것부터(공개 중이면 공개 시각, 아니면 만든 때). `GET /admin/notices/{id}`: 한 건(상세 화면 — 앱 결정 107 의 6).
   - `POST /admin/notices { category, title, body, notify? }`: 임시 저장으로 만든다(`notify` 없으면 false). 201.
   - `PUT /admin/notices/{id} { category, title, body, notify? }`: 고친다. `notify` 를 빼면 그대로 둔다. 공개 중인 글을 고치면 공개한 채로 바뀐다(앱에는 캐시 때문에 5분쯤 뒤).
   - `POST /admin/notices/{id}/publish { notify }`: 공개한다. 임시 저장이면 `published_at` = 지금, 이미 공개 중이면 공개 시각은 그대로다. `notify`(필수 불리언)를 스위치에 적는다.
   - `POST /admin/notices/{id}/unpublish`: 임시 저장으로 내린다(`published_at` 은 비운다). 다시 공개하면 그때가 공개 시각이다.
   - `DELETE /admin/notices/{id}`: 지운다. 204.
   - 오류: `category` 가 둘이 아니면 400 `INVALID_CATEGORY`, 제목 · 본문의 길이가 틀리면 400 `INVALID_TITLE` · `INVALID_BODY`, 타입이 틀리면 400 `INVALID_REQUEST`, 없는 id · UUID 가 아닌 id 는 404 `NOT_FOUND`.
   - **"업데이트" 갈래는 알림을 보낼 수 없다**(앱 결정 107 의 결과): 결과가 `category = UPDATE` 이면서 알림 보내기가 켜지는 요청(만들기 · 고치기 · 공개)은 400 `NOTIFY_NOT_ALLOWED`. 이미 알림을 보낸 글의 스위치는 켜진 채로 남으므로(아래 4) 그 글을 업데이트 갈래로 바꾸는 것도 같은 400 이다.
   - 감사 기록(결정 015): `NOTICE_CREATED` `{ category, notify }` · `NOTICE_UPDATED` · `NOTICE_PUBLISHED` `{ notify, notified }`(`notified` = 이번에 알림을 보냈는지) · `NOTICE_UNPUBLISHED` · `NOTICE_DELETED`, 대상 `NOTICE` id. 제목 · 본문은 넣지 않는다.
4. **알림은 한 글에 한 번**: 공개 요청의 `notify` 가 참이고 `notified_at` 이 비었을 때만 보낸다. `notified_at` 은 조건부 update(`where notified_at is null`)로 먼저 적은 요청만 보내므로 두 관리자가 동시에 눌러도, 내렸다가 다시 공개해도, 공개를 두 번 눌러도 다시 가지 않는다. 보낸 뒤에는 스위치가 켜진 채로 남는다(`PUT` · 공개의 `notify: false` 로 끌 수 없다 — 이미 간 푸시는 되돌릴 수 없다).
   - 이미 공개 중이고 아직 알림을 보내지 않은 글에 `publish { notify: true }` 를 하면 그때 한 번 보낸다(공개 시각은 그대로).
   - 보내면 `admin` 이 커밋 뒤 이벤트 `admin.NoticePublished(noticeId)` 를 낸다(글은 싣지 않는다). 두 기능이 듣는다:
     - `notification`: **모든 계정**에 알림함 줄 `NOTICE { noticeId }` 를 한 문장(insert … select)으로 넣는다 — 알림함 줄은 푸시 동의와 상관없이 쌓인다(042 의 4). 계정마다 한 줄(사용자가 아주 적어서 — 몇만 명이 넘으면 공지 줄을 따로 두는 모양을 다시 본다). 30일 지우기(035 의 5)는 그대로.
     - `push`: 푸시 동의 + 정지 아님인 계정의 기기 모두에 보낸다(`push` 일꾼, 100건씩). 제목 = 공지 제목, 본문 = "공지사항에서 자세히 볼 수 있어요.", `data` = `{ type: "NOTICE", noticeId }`. 제목은 보낼 때 `notices` 에서 읽는다 — 그 사이 지워졌거나 내려갔으면 보내지 않는다. 하루 한 번 제약(V33)은 쓰지 않는다. 웹은 기기가 없어 알림함만 받는다.
   - 공지를 지우면 그 알림함 줄도 cascade 로 지워진다(`notifications.notice_id` → `notices` `on delete cascade` — 누르면 없는 글로 가는 줄을 남기지 않는다, 앱 결정 052 · 서버 035 의 6). 내리기(임시 저장)는 줄을 남긴다 — 앱은 목록에 없는 글이면 "지워진 공지예요"를 띄우고(앱 결정 107 의 5), 다시 공개하면 그 줄이 다시 그 글로 간다.
5. **알림함 종류 `NOTICE`**: `notifications.kind` check 에 `NOTICE` 를 더하고 칸 `notice_id`(null 허용, 공지 알림만)를 둔다. `GET /notifications` 의 줄에 `noticeId?` 가 생긴다(그 종류일 때만). 1.0.x 앱은 모르는 종류의 줄을 숨기고 종의 점도 보이는 줄로만 센다(앱 `notification-rows.ts`) — 깨지지 않는다.
6. **밤 무음**(앱 결정 104 의 9 · 107 의 5): 서비스 알림 가운데 **공지(지금)와 궁합 알림 두 종류(S③ 이 쓴다)** 는 보낼 때 한국 시각이 **21:00 이상이거나 08:00 미만**이면 소리 없이 보낸다.
   - 메시지(`push.PushMessage`)가 소리(있음 · 없음)와 안드로이드 채널(`channelId`, 없으면 null)을 싣는다. 밤에는 `sound` 를 빼고, **`push_devices` 의 플랫폼이 `ANDROID` 이고 앱 버전이 1.1.0 이상인 기기에만** `channelId: "quiet"` 를 싣는다. 그 밖의 기기(iOS · 1.1.0 미만 · 버전을 모르는 안드로이드)에는 채널을 싣지 않는다 — 채널이 없는 기기에 실으면 알림이 아예 안 보인다(Expo 문서). 1.1.0 미만 안드로이드는 밤에도 기본 채널의 소리가 날 수 있다(업데이트하면 조용해진다).
   - 낮(08:00 ~ 21:00 미만)에는 지금과 같은 모양(`sound: "default"`, 채널 없음)이다.
   - 경계: 20:59 소리 있음 · 21:00 소리 없음 · 07:59 소리 없음 · 08:00 소리 있음. 판정은 보내는 순간의 서버 시계(`ClockConfig`)로 한다.
   - 광고 푸시(043 의 6 — 밤에는 보내지 않는다) · 오늘의 하늘 아침 알림(042 의 6 — 사용자가 고른 시각) · 풀이 완성 · 문의 답변(042 의 4)은 지금 규칙 그대로다.
   - 광고 푸시의 밤(`admin.AdminPushService` 의 21:00 ~ 08:00)과 시각은 같지만 함께 쓰는 함수로 묶지 않는다 — 광고는 법(정보통신망법 제50조)이 정한 "보내지 않는 밤"이고 이것은 제품 결정(104 의 9)인 "소리 없는 밤"이라 따로 바뀔 수 있다. 판정은 `push.QuietHours` 한 곳에 둔다.

## 근거
- FAQ 와 같은 틀(공개 조회 패키지 + `admin` 의 쓰기, 같은 표만 본다)이라 의존 규칙 · 세션 · 캐시 · 속도 제한을 새로 정하지 않는다.
- 알림을 "한 번"으로 지키는 칸(`notified_at`)을 글에 두면 다시 공개 · 동시 클릭 · 서버 둘(결정 046)에도 조건부 update 하나로 끝난다. 이벤트를 커밋 뒤에 내므로 되돌린 공개에는 보내지 않는다.
- 알림함 줄을 계정마다 넣으면 지금의 알림함 읽기 · 읽음 · 30일 지우기 · cascade 를 그대로 쓴다. 사용자가 적은 지금 표가 커지는 값은 작다.
- 밤 채널을 버전으로 가리는 것은 Expo 문서의 "없는 채널이면 보이지 않는다" 때문이다. 소리가 나는 쪽이 알림이 사라지는 쪽보다 낫다.

## 대안
- 공지 알림을 광고 푸시 길(`broadcast` · `push_events`)로 보내기: 마케팅 동의자만 받고 하루 한 번에 묶인다 — 서비스 알림의 뜻과 다르다.
- 알림함에 공지 줄을 한 번만 두고 계정마다 읽음만 따로 두기: 읽기 · 지우기 · 읽음 쿼리를 모두 바꿔야 한다. 사용자가 몇만 명이 넘으면 다시 본다.
- 밤 알림을 아침 8시에 몰아 보내기: 104 의 9 가 "바로 보내되 소리 없이"로 정했다.
- 모든 안드로이드에 `quiet` 싣기: 1.1.0 미만 기기에서 알림이 사라진다.
- 예약 공개 · 맨 위 고정: 범위 밖(앱 결정 107 · 계획).

## 결과 · 재검토 조건
- 1.0.x 앱은 공지 푸시(`data.type = NOTICE`)를 눌러도 앱만 열린다(모르는 종류). 알림함에서는 그 줄이 숨는다.
- **되돌리기 주의**: `NOTICE` 줄이 생긴 뒤 이 변경 전의 jar 로 되돌리면 옛 jar 의 `GET /notifications` 가 모르는 종류를 읽다가 실패한다(블루그린으로 두 서버가 겹치는 몇 분도 같다). 되돌리기 전에 `delete from notifications where kind = 'NOTICE'` 로 그 줄을 지운다.
- 공지 알림 한 번의 기기 수 · 보낸 수는 `event=push_sent type=NOTICE` 로그에 남는다. 공지 알림이 어드민의 발송 내역에는 남지 않는다(감사 기록 `NOTICE_PUBLISHED` 의 `notified` 로 본다).
- 사용자가 몇만 명을 넘어 알림함 넣기 · 푸시가 느려지면 공지 줄 하나 + 계정별 읽음, 또는 나눠 보내기를 새 결정으로 다시 본다.
