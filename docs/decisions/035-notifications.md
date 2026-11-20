---
type: decision
tags: [알림함, 알림, 읽음, 30일 보관, 이벤트]
status: active
last_reviewed: 2026-11-20
superseded_by:
---
# 035. 알림함은 서버에 종류 넷(별명 · 나 탭 풀이 · 시기 풀이 · 궁합 리포트 완성)을 30일 두고, 읽음은 누를 때 표시한다

> 상태: 활성 · 결정일 2026-11-20(소유자 질문 · 답 7번 "그래 그러자 문서에 적어 놓자" · 구현 승인) · 원본: 앱 저장소 `docs/raw/2026-11-20-session-owner-qna-home-me-timing.md` 7번 · 시안: 보드 11(홈의 종 · 안 읽은 점) · 11-3(알림) · 앱 결정 052(알림함) · 결정 029(완성 이벤트) · 계획 docs/plans/2026-11-20-home-me-timing.md

## 맥락
- 시안 알림(11-3): 오늘 / 최근 7일 / 최근 30일로 묶고, 줄마다 안 읽음 점, 누르면 그 화면으로 간다. 홈의 종에 안 읽은 알림이 있으면 점.
- 소유자: 알림은 나 탭 풀이 완성 · 시기 풀이 완성 · 궁합 리포트 완성 · 별명 지음 넷이다. 오늘의 하늘은 1.0.0 알림함에 넣지 않는다. 30일 보관, 누르면 읽음 처리하고 그 화면으로(7번). 휴대폰 푸시는 아직 없다.
- 서버에 있는 것: 리포트 완성 이벤트 `report.ReportDone(ownerId)`(듣는 곳 없음). 알림 테이블 · 경로는 없다. 궁합 리포트 완성 이벤트는 없다. 계약 표의 `PUT /devices/push-token` 과 모듈 목록의 `push` 는 코드에 없다(문서와 코드가 어긋남 — 이 결정의 범위 밖, 계획의 문서 태스크에서 "구현 전" 으로 표시).

## 결정
1. **종류**: `READING_DONE`(별명 지음 — 그 차트의 요약 해석을 처음 저장했을 때) · `ME_REPORT_DONE` · `TIMING_REPORT_DONE` · `SYNASTRY_REPORT_DONE`(그 궁합의 `synastryId` 를 함께). 알림 문구는 앱이 갖는다(서버는 종류와 대상만 준다).
2. **저장** `notifications`(V24): `id` · `owner_id`(계정을 지우면 함께 지워진다) · `kind`(check) · `synastry_id`(null 허용, 궁합 알림만) · `created_at` · `read_at`(null = 안 읽음). 색인 `(owner_id, created_at desc)`.
3. **경로**:
   - `GET /notifications` → 200 `{ unread, items[{ id, kind, createdAt, read, synastryId? }] }` — 최근 30일, 새것부터, 100개까지. `unread` 는 30일 안의 안 읽은 수(홈의 종 점).
   - `POST /notifications/{id}/read` → 204. 남의 것 · 없는 것 · UUID 가 아닌 값은 404 `NOT_FOUND`. 이미 읽은 것도 204.
4. **만드는 곳**: 새 기능 패키지 `notification` 이 완성 이벤트를 듣고(커밋 뒤 · 자기 트랜잭션 · 실패는 삼키고 구조화 로그만) 한 행을 넣는다. 이벤트: `reading.ReadingCreated(ownerId)`(새로) · `report.MeReportDone(ownerId)` · `report.TimingReportDone(ownerId)`(결정 029) · `synastry.SynastryReportDone(ownerId, synastryId)`(새로). 이벤트에는 글 · 출생 정보가 없다. 새 이벤트들은 허용된 기능 사이 이벤트 목록(`ArchitectureTest` 의 EVENTS · docs/architecture.md 의존 규칙)에 더한다.
5. **30일 지우기**: 매일 한 번(한국 시간 04:00) `created_at` 이 30일 지난 행을 지운다(일정 작업). 읽음 여부와 상관없다.
6. **궁합이 지워지면** 그 궁합의 알림도 지운다(`SynastryDeleted` 를 듣는다). 누르면 404 가 나는 줄을 남기지 않는다.
7. **푸시 · 오늘의 하늘 알림은 하지 않는다.** 휴대폰 푸시가 생기면 같은 이벤트에서 발송을 더하고, "오늘의 하늘 알림" 켜기/끄기는 그때 새 결정으로(7번).

## 근거
- 문구를 앱이 가지면 알림 말투를 바꿔도 서버 배포가 필요 없다(골든 룰 "문구는 strings").
- 이벤트로 만들면 리포트 · 궁합 · 해석 코드는 알림을 모른다(기능 패키지는 서로 의존하지 않는다 — 결정 004).
- 30일이 지나면 화면에 나오지 않으므로 지워도 잃는 것이 없다.

## 대안
- 알림을 저장하지 않고 앱이 상태(리포트 `DONE` 등)를 보고 만들기 — 기기를 바꾸거나 다시 깔면 읽음이 사라지고, "언제" 를 알 수 없다.
- 모두 읽음 경로 — 시안에 없다.

## 결과·재검토 조건
- 푸시가 생기면 `notification` 이 발송도 맡을지, `push` 를 따로 둘지 그 계획에서 정한다.
- docs/architecture.md 의 모듈 · 의존 규칙(이벤트 목록) · 계약 표 · "데이터" 는 계획 S⑤ 의 PR 에서 같은 커밋으로 고친다.
