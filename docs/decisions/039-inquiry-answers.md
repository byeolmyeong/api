---
type: decision
tags: [문의하기, 문의 답변, 내 문의 목록, 알림, INQUIRY_ANSWERED, 임시 저장, 관리자, 옛 앱 호환, 감사 기록]
status: active
last_reviewed: 2026-11-20
superseded_by:
---
# 039. 문의에 앱 안 답을 단다 — 내 문의 목록 · 답 칸 · 새 종류 넷 · 처음 답할 때 이벤트 하나

> 상태: 활성 · 결정일 2026-11-20(소유자 "이거 앱 안에서 열 수 있도록 해야지" — 앱 결정 058, "어드민 보드 그대로 지금 다" — 관리자 문의 화면의 찾기 · 작성자 · 임시 저장) · 결정 013 의 1(종류 · 답장 이메일 · "앱 버전은 저장하지 않는다") · 3(사용자가 읽는 경로 없음) · 5(글 10~2000자 · 이메일 검사)를 바꾼다 — 013 의 나머지(소유자는 세션에서 · 계정을 지우면 함께 지움 · 계정별 시간당 5건 · 글을 로그에 넣지 않음)는 그대로 · 결정 014 의 문의 관리에 찾기 · 작성자 · 답 · 임시 저장을 더한다 · 결정 043 의 8 의 `answerDraft`(계획 S11-6)를 이 결정으로 당겨 온다 · 계획: 앱 저장소 `docs/plans/2026-11-20-sian-all-repos.md` S⑧ · 계약은 docs/architecture.md "API 계약" 의 "문의하기" 와 "관리자 API" 의 "문의"

## 맥락
- 013 은 답을 소유자가 이메일로 직접 보내게 했다. 앱 결정 058 이 답을 앱 안에서 주고받게 바꿨다: 내 문의 목록, 답 보기, 이메일 칸 없음, 종류 넷(오류 · 제안 · 계정 · 기타), 내용 10~1000자, 답이 오면 알림함에 `INQUIRY_ANSWERED`.
- 이미 깔린 테스트앱은 옛 종류 다섯(ACCOUNT · READING · SYNASTRY · BUG · OTHER)과 `replyEmail` 키(값이 없으면 `null`)를 언제나 보내고, 글은 2000자까지 받는다. 서버가 먼저 바뀌어도 그 앱의 문의는 깨지지 않아야 한다(무중단 배포 준비 — 앞으로만 호환).
- 어드민 v2 시안(D2 · D3)은 문의 목록의 찾기, 작성자 칸(닉네임 · 로그인 수단 · 기기 · 앱 버전 · 가입일), 답변 · 임시 저장을 그린다.
- 알림함(계획 S⑤, 결정 035)과 푸시(S⑩)는 아직 develop 에 없다.

## 결정
1. **저장**(마이그레이션 — 계획은 V26 으로 적었으나 Flyway 는 번호가 거꾸로 들어오면 멈추므로 PR 을 열 때 develop 의 다음 번호 V23): `inquiries` 에 `answer_body` · `answered_at` · `answered_by`(→ `admin_accounts`) · `answer_draft` · `app_platform` · `app_version` 을 더한다(모두 null 허용). 상태의 check 를 넓혀 `ANSWERED` 를 더한다(NEW · IN_PROGRESS · ANSWERED · DONE). 더하기만 한다 — 열 · 표를 지우거나 이름을 바꾸지 않고, 있던 행은 그대로다.
2. **`POST /inquiries`**(새 앱): `{ category: BUG | IDEA | ACCOUNT | ETC, body }`. 글은 앞뒤 공백을 뗀 10~1000자(코드 포인트). 이메일은 받지 않는다.
   - **옛 앱**: 본문에 `replyEmail` 키가 있으면(값 · 타입과 상관없이) 옛 앱의 요청으로 보고 옛 규칙으로 받는다 — 종류는 새 넷 + 옛 셋(READING · SYNASTRY · OTHER), 글은 10~2000자. `replyEmail` 은 읽지도 저장하지도 않는다(소유자 "와도 무시"). 옛 종류는 받은 그대로 저장한다. 계획 S⑦(옛 경로 정리)에서 이 갈래를 닫는다.
   - 앱이 보낸 `X-App-Platform`(`ios` · `android`, 대소문자 무시 → 소문자)과 `X-App-Version`(결정 016 의 버전 규칙)을 그 문의에 적어 둔다 — 관리자가 어느 앱에서 온 문의인지 본다. 없거나 읽을 수 없으면 null(웹은 보내지 않는다). 기기 이름 · 주소(IP)는 여전히 받지 않는다.
   - 오류 코드는 그대로다(400 `INVALID_REQUEST` · `INVALID_CATEGORY` · `INVALID_BODY` · 401 · 429). `INVALID_EMAIL` 은 더 나오지 않는다.
3. **`GET /inquiries`** · **`GET /inquiries/{id}`**(세션): 내 문의 전부(새것부터, 같으면 id 가 큰 것부터 — 배열) · 한 건. 남의 것 · 없는 것 · UUID 가 아닌 값은 404 `NOT_FOUND`.
   - 한 건의 모양 `{ id, category, body, status: OPEN | ANSWERED, createdAt, answer?: { body, answeredAt }, replyEmail? }`. `status` 는 답이 있으면 `ANSWERED`, 없으면 `OPEN` — 관리자의 처리 단계(NEW · IN_PROGRESS · DONE)는 보이지 않는다. `category` 는 저장된 그대로(옛 값 포함 — 앱은 모르는 종류를 "기타" 로 보인다). `replyEmail` 은 그 칸이 있는 옛 문의에만 온다(앱이 "이메일로 보냈어요" 를 고른다, 앱 결정 058). 메모 · 임시 저장 · 답한 관리자 · 앱 버전은 없다.
   - 읽기에는 계정별 제한을 걸지 않는다(013 의 6 — 저장만 센다).
4. **관리자 답** `PATCH /admin/inquiries/{id}` 의 `answer`(앞뒤 공백을 뗀 1~2000자, 코드 포인트):
   - 저장하면 상태 `ANSWERED`, 임시 저장은 비운다. **처음 답일 때만** `answered_at` · `answered_by` 를 적고 이벤트를 하나 낸다(5). 고치면 글만 바뀐다 — `answered_at` · `answered_by` 는 처음 그대로이고 이벤트는 없다. 마지막으로 손댄 관리자는 지금처럼 `handledBy` · `handledAt` 이다.
   - 처음 답의 판정은 조건부 갱신(`answered_at is null` 인 행만)이라 두 관리자가 동시에 처음 답해도 이벤트는 하나다.
   - 답은 지울 수 없다(사용자가 이미 받았다): `null` · 빈 글 · 공백뿐 · 2000자 넘음 · NUL 은 400 `INVALID_ANSWER`.
   - `status` 로 `ANSWERED` 를 고를 수 없다(400 `INVALID_STATUS` — 답을 저장해야 된다). `answer` 와 `status`, `answer` 와 `answerDraft` 를 함께 보내면 400 `INVALID_REQUEST`(어느 쪽이 이기는지 모호하다). `memo` 는 함께 보내도 된다.
   - 답한 뒤 `status` 를 NEW · IN_PROGRESS · DONE 으로 바꿔도 답은 남고 앱의 상태는 `ANSWERED` 그대로다.
5. **알림**: 처음 답할 때 Spring 이벤트 `admin.InquiryAnswered(ownerId, inquiryId)` 를 낸다(기능 사이에 허용된 이벤트 목록에 더한다, 글은 싣지 않는다). 알림함의 `INQUIRY_ANSWERED { inquiryId }` 한 줄은 S⑤ 의 `notification` 이 이 이벤트를 듣고 만들고, 푸시는 S⑩ 이 같은 이벤트에서 보낸다 — 지금은 듣는 곳이 없다. 듣는 쪽은 커밋된 뒤에 듣는다(`@TransactionalEventListener`, 결정 035 의 4).
6. **임시 저장** `answerDraft`(같은 PATCH): 앞뒤 공백을 뗀 2000자 이하. `null` · 빈 글 · 공백뿐이면 지운다. 상태 · 답 · 알림은 그대로다(043 의 8). 틀리면 400 `INVALID_ANSWER`(`answer` 와 함께 올 수 없어 어느 칸인지 헷갈리지 않는다). 앱에는 보이지 않는다.
7. **관리자 문의 목록 · 한 건**:
   - `Inquiry` 에 `answer` · `answeredAt` · `answeredBy { id, name }` · `answerDraft` · `owner` 를 더한다(없으면 `null`). `owner = { userId, nickname, provider, platform, appVersion, joinedAt }` — `userId` 는 계정 id(결정 043 의 사용자 관리와 같은 id), `platform` · `appVersion` 은 보낼 때 적어 둔 2 의 값, `joinedAt` 은 가입한 때. 계정이 없으면 `null`(지금은 계정을 지우면 문의도 지워지므로 나오지 않는다). 이메일 · 출생 정보는 여전히 없다. `ownerRef` 는 그대로 둔다(감사 기록과 묶는 값 — 결정 015).
   - 거르기 `status` 에 `ANSWERED`, `category` 에 새 종류(옛 값도 그대로 받는다 — 모르는 값은 지금처럼 빈 목록).
   - 찾기 `q`: 앞뒤 공백을 뗀 값이 문의 글의 일부 **또는** 작성자 닉네임의 일부와 맞는 것(대소문자 무시, 다른 조건과 함께). 빈 값은 없는 것.
   - 현황 `inquiries.byStatus` 에 `ANSWERED`.
8. **감사 기록**: 새 행동 `INQUIRY_ANSWERED`(관리자, 대상 `INQUIRY`, detail `{ first }` — 처음 답이었는지). 답을 저장할 때마다 남는다. 같은 요청에 `memo` 가 있으면 `INQUIRY_UPDATED` 도 한 줄 더(FAQ 의 두 줄과 같다). 임시 저장은 `INQUIRY_UPDATED` 의 detail 에 `draftChanged: true`. 답 · 임시 저장의 글은 남기지 않는다.
9. 답 · 임시 저장의 글은 로그 · 오류 응답에 넣지 않는다(013 의 7 과 같다).

## 근거
- 앱 안 답은 이메일을 적지 않은 사람에게도 닿고, 알림함에서 바로 연다(앱 결정 058).
- 옛 앱의 모양(`replyEmail` 키)으로 갈래를 나누면 새 앱에는 새 규칙(1000자 · 새 종류)을 그대로 걸고, 깔린 테스트앱은 업데이트 전까지 지금처럼 보낸다. 갈래가 한 곳이라 S⑦ 에서 한 번에 닫는다.
- 사용자에게는 "확인 중 / 답변 왔어요" 둘만 필요하다. 관리자의 처리 단계를 그대로 보이면 내부 사정이 드러나고 단계를 바꿀 때마다 앱이 바뀐다.
- 오타를 고칠 때마다 알림이 가면 사용자는 같은 답을 여러 번 연다(계획 S8-2 "처음 답할 때 한 번").
- 알림 행을 여기서 직접 넣으면 `admin` 이 `notification` 을 알게 된다(기능은 서로 모른다 — 결정 004). 이벤트 하나면 알림함 · 푸시가 각자 듣는다.

## 대안
- 옛 종류를 바로 거절 — 깔린 테스트앱의 다섯 종류 중 셋이 400 이 된다.
- 옛 종류를 새 값(ETC)으로 바꿔 저장 — 사람이 고른 값이 사라진다. 앱이 어차피 "기타" 로 보인다.
- 글 길이를 모두 2000자로 두기 — 새 앱의 규칙(1000자)을 서버가 지키지 못한다.
- 관리자 상태를 앱에 그대로 — 위 근거 셋째.
- 답을 고칠 수 없게 — 오타를 고칠 길이 없다. 고치되 다시 알리지 않는다.
- 문의 때 앱 버전을 받지 않기(013 그대로) — 어드민 시안이 작성자의 앱 버전을 보인다(소유자 "보드 그대로"). 버전 · 플랫폼은 개인정보가 아니고 오류 문의를 볼 때 쓴다.

## 결과·재검토 조건
- 계획 S⑦ 에서 옛 갈래(`replyEmail` 키 · 옛 종류 받기 · 2000자)를 닫는다 — 새 테스트앱이 깔린 뒤.
- S⑤ 는 알림 종류 `INQUIRY_ANSWERED` 와 그 문의 id 칸을 더하고 `admin.InquiryAnswered` 를 듣는다. S⑩ 은 같은 이벤트에서 푸시를 보낸다. S⑪ 은 정지된 계정에도 문의 셋을 연다(결정 044).
- 한 문의에 답 하나다. 여러 번 주고받기가 필요해지면(1.1.0 이후) 답을 따로 테이블로 뺀다.
