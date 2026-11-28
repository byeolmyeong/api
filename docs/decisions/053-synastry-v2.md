---
type: decision
tags: [1.1.0, 궁합, 사이 둘, 상대 출생 정보 저장, 한 번에 만들기, 하루 안전 한도, 여러 명 지우기, 나왔어요 알림, 서버 계약]
status: active
last_reviewed: 2026-11-28
superseded_by:
---
# 053. (1.1.0) 궁합 v2 — 사이 둘 · 상대 출생 정보 저장 · 한 번에 만들기 · 하루 안전 한도 · 30초 "나왔어요" 알림

> 상태: 활성 · 결정일 2026-11-28 · 앱 결정 104(2 ~ 6 · 9 의 "나왔어요" · 11) · 111 과 앱 저장소 계획 `docs/plans/2026-11-27-1.1.0.md` 의 S②(S2-1 ~ S2-8)를 서버가 어떻게 만드는지 적는다. 계획이 "제안"으로 비워 둔 이름 · 숫자와 이 결정이 고른 것은 "(제안)" 으로 표시했다.
> - **결정 010 의 1 · 2 · 3 을 대체한다**(상대의 출생 정보를 저장하지 않음 · 겹친 휠은 만든 응답에만 · 리포트를 요청할 때 출생 정보를 다시 받음). 010 의 4(근거는 내 쪽 요소만) · 5(모델에게 이름 · 출생 정보 · 도수를 주지 않음, 점수는 서버만) · 6(`scoreLabel` — 서버는 그대로 두고 1.1.0 앱이 쓰지 않는다) · 7(같은 상대도 새 궁합)은 그대로다.
> - 결정 035 의 4 에서 궁합 리포트 완성 알림을 "완성되자마자" 넣던 것을 아래 8 의 30초 규칙으로 바꾼다. 다른 종류는 그대로다.
> - 계획은 "서버 결정 008 · 010" 을 대체하라고 적었으나 008(시기 영역)에는 상대 출생 정보 규칙이 없다(2026-11-28 확인). 010 만 대체한다.
> - 결정 011(방향성 제시의 궁합 주제는 상대 출생 정보를 요청으로 다시 받음)은 그대로 둔다. 1.1.0 앱은 방향성을 부르지 않는다(앱 결정 100 의 3).
> - 링크로 생긴 궁합(`source = LINK`)과 새로 온 별(`status = NEW_STAR`)은 칸만 둔다. 동작은 다음 줄(계획 S③, 결정 054 자리)이 만든다.

## 맥락
- 1.0.0 의 궁합(결정 010)은 요약을 받을 때까지 기다리는 `POST /synastry/direct` 와 따로 시작하는 `POST /synastry/{id}/report`(상대 출생 정보를 다시 받음)였다. 1.1.0 은 요약과 네 영역을 한 번에 만들어 한 화면에 보인다(앱 104 의 4).
- 앱 104 의 6 이 상대 출생 정보를 저장하기로 뒤집었다. 내 출생 정보를 고치면 직접 넣은 궁합도 다시 계산해야 하기 때문이다.
- 궁합은 모두 무료이고 사용자에게 보이는 한도가 없다. 서버는 프로그램으로 수백 명을 넣는 남용만 막는다(앱 104 의 5).
- 1.0.0 앱은 궁합을 부르지 않는다: 운영 앱(앱 `main` `f04c4c3`)과 앱 `develop`(`3d14ffc`) 모두 `src/app/synastry/_layout.tsx` 가 `/synastry/**` 를 홈으로 돌려보내고, 궁합 경로를 부르는 코드(`src/features/synastry` · `src/features/direction/use-synastry-direction.ts`)는 그 아래 화면에서만 쓰인다(2026-11-28 확인). 그래서 옛 경로를 바꾸거나 지워도 깨질 앱이 없다.

## 결정
1. **사이 입력은 둘이다.** `POST /synastry` 의 `relation` 은 `LOVE` · `FRIEND` 만 받고, 그 밖(`WORK` · `FAMILY` · 없음 · 다른 값)은 400 `INVALID_RELATION` 이다.
   - `chart.Relation` 의 넷 · 가중치 · DB 제약(`relation in (LOVE, FRIEND, WORK, FAMILY)`)과 읽기(`GET /synastry/ranking?relation=` 의 넷)는 옛 기록을 읽으려고 그대로 둔다.
   - "친구" 는 친구 · 가족 · 동료를 아우르는 가까운 사이다. 궁합 글의 지시문(`prompts/synastry.md`)이 친구 · 가족 · 동료 누구와 읽어도 어색하지 않게 쓰게 한다.
2. **직접 넣은 상대의 출생 정보를 저장한다**(V38 — `synastries.partner_birth_date` · `partner_birth_time`(모르면 null) · `partner_place_id`(시각을 모르면 null)).
   - 그 궁합에만 쓴다: 점수 · 이렇게 본 이유 · 글을 다시 쓸 때. 다른 기능 · 로그 · 감사 detail · 분석 · 공유 응답에 넣지 않는다.
   - 궁합을 지우거나 계정을 지우면 같은 행이라 함께 지워진다.
   - 응답으로 돌려주지 않는다. 내려보내는 것은 점수와 이유(내 쪽 대상 · 상대 천체 · 각도 종류 · 결)뿐이다 — 황경 · 별자리 · 출생 정보 원문은 없다. 겹친 휠(`overlay`)은 없앤다(1.1.0 결과 화면에 휠이 없다).
3. **한 번에 만든다**(앱 104 의 4).
   - `POST /synastry`(본문 `{ name, relation, date, time | null, placeId? }`) → 점수를 계산해 행을 `WRITING` 으로 넣고 202 `{ id, status }` 로 바로 답한다.
   - 뒤에서 요약(키워드 · 한 줄)을 쓰고, 요약이 통과하면 네 영역을 동시에 쓴다. 모두 검증을 통과하면 `DONE` 이다. 요약이 끝내 실패하면 네 영역을 부르지 않고 `FAILED` 다.
   - 상태는 궁합 행에 둔다: `synastries.status`(`WRITING` · `DONE` · `FAILED` · `NEW_STAR`) · `started_at`(그 생성의 표식) · `active_at`(살아 있다는 표시, 15초마다 — 2분 끊기면 `FAILED` 로 보인다) · `done_at`. 네 영역의 글은 지금처럼 `synastry_reports` 에 `DONE` 한 행으로 둔다(옛 행과 같은 자리).
   - 다시 하기(제안): `POST /synastry/{id}/retry` → 202 `{ id, status }`. `FAILED` · 멈춘 `WRITING` 이면 같은 행에서 처음부터 다시 쓴다(지금의 내 차트 · 저장된 상대 출생 정보로 점수도 다시). 이미 `DONE` · 쓰는 중이면 그 상태를 준다. 계획의 "다시 보기 = 같은 경로 다시" 를 `POST /synastry` 로 새 행을 만드는 것으로 읽으면 실패한 줄이 목록에 남고 하루 한도를 한 번 더 써서, 같은 행을 다시 쓰는 길로 골랐다.
   - 작은 실천 셋(제안): 앱 104 의 4 의 "이 사이의 작은 실천 셋" 은 오래가기 영역의 응답에 함께 받는다(`sections[LASTING].practices` — 한 줄씩, "기" 로 끝남, `generation.adviceProblem`). 나 탭 풀이의 연애 실천(결정 029)과 같은 방식이고 모델 호출이 늘지 않는다. 원가 로그 이름(`synastry_summary` · `synastry_report_*`)은 그대로다.
   - 이렇게 본 이유(제안 `reasons`): `GET /synastry/{id}` 가 저장된 상대 출생 정보로 읽을 때 계산한다(점수에 쓴 각도 전부, 센 것부터 — 세기 = 점수 × 가중치의 크기). 한 줄은 `{ mine, theirs, type, tone: EASY | TENSE }` 이고 `tone` 은 서버의 결(토성 · 화성이 낀 합은 `TENSE`)이다. 상대 출생 정보가 없는 행(옛 행 · 링크)에는 없다.
   - 생성 한도(결정 022 · 023 — 분 5 · 시간 30)는 만들 때와 다시 하기 때 한 번 센다(그대로). 다시 하기는 같은 행의 자리를 잡은 뒤에 센다 — 동시에 여러 번 불러도 한 번만 센다. 넘으면 그 자리를 `FAILED` 로 돌리고 429 다(PR 리뷰 반영).
   - 서버를 닫는 중(결정 046 — `ReportExecutorDrain` 이 일꾼에 새 일을 받지 않게 한 뒤)에 요약을 마친 궁합이 네 영역을 일꾼에 맡기지 못하면, 실패로 끝내지 않고 **그 스레드에서 네 영역을 이어 쓴다**(드레인은 그 스레드가 끝날 때까지 기다린다). 일꾼이 꽉 차 거절된 것만 `FAILED`(`EXECUTOR_FULL`)이고, 닫는 중에 처음 맡기기부터 거절되면 `FAILED`(`SHUTTING_DOWN`)다(요청을 다 끝낸 뒤 닫으므로 보통은 생기지 않는다). `SynastryService` 의 진행 목록이 빌 때까지 드레인이 기다리게 하는 길은 `report` 가 `synastry` 를 알아야 해서(기능끼리는 이벤트로만) 고르지 않았다(PR 리뷰 반영).
4. **옛 경로를 지운다**: `POST /synastry/direct` · `POST /synastry/{id}/report` · `GET /synastry/{id}/report`. `Synastry.reportStatus` 는 `status` 로, `overlay` 는 없앤다. `GET /synastry` · `GET /synastry/{id}` · `DELETE /synastry/{id}` · `GET /synastry/ranking` 은 남긴다(랭킹은 `DONE` 만 센다). 주소별 분당 한도(`synastry-per-minute` 10)는 `POST /synastry` 에 건다.
5. **내 출생 정보를 바꾸면**(`profile.BirthProfileChanged`) 상대 출생 정보가 저장된 궁합의 점수를 그 자리에서 새 차트로 다시 계산한다(앱 104 의 6).
   - 글은 "다시 써야 함" 이다: 행의 `owner_chart_hash`(그 글을 쓴 때의 내 차트)가 지금 차트와 다르면 그렇다. 그 궁합을 열 때(`GET /synastry/{id}`) 다시 쓰기 시작하고 `WRITING` 을 준다. 하루 한도는 세지 않는다(새 궁합이 아니다).
   - **다시 쓰기도 생성 한도를 센다**(PR 리뷰 반영 — 처음 판은 세지 않았다). 세지 않으면 출생 정보를 A ↔ B 로 바꾸고 궁합을 열기만 해도 모델 호출이 한도 없이 나간다(바꿀 때마다 모든 궁합의 글이 "다시 써야 함" 이 되고, 열 때마다 다섯 번씩 부른다). 그렇다고 열기에 429 를 주면 사용자는 결과 대신 실패를 본다. 그래서 **한도를 넘으면 다시 쓰지 않고 옛 `DONE` 글(점수는 새 값)을 그대로 준다** — 그 궁합은 "다시 써야 함" 인 채 남아 다음에 열 때 다시 시도한다. 로그 `event=synastry_rewrite_deferred reason=RATE_LIMITED`. 한도는 자리를 잡기 전에 센다(열기는 보통 한 번이고, 다시 하기처럼 자리를 되돌릴 상태가 없다).
   - 점수를 다시 계산하다 한 궁합이 실패해도(옛 곳 id 가 목록에서 빠짐 등) 그 궁합만 건너뛰고 로그 `event=synastry_rescore_failed` 를 남긴다. 출생 정보 저장(`PUT /profile`)과 다른 기능의 리스너를 막지 않는다.
   - 상대 출생 정보가 없는 옛 행은 그대로 둔다.
6. **하루 안전 한도**: 계정마다 그날(한국 날짜) 만든 궁합 행이 `byeolmyeong.synastry.daily-limit`(50) 이상이면 `POST /synastry` 는 429 `RATE_LIMITED` 다(안내는 지금 그대로). DB 로 센다(`(owner_id, created_at)` 색인) — 서버 메모리는 배포 때 비워진다(결정 046).
   - 링크로 만들어진 양쪽 기록도 각자 하나로 센다(앱 104 의 7, S③).
   - 결정 003 의 "기능 횟수 제한은 없다" 와 맞서지 않는다: 보통 사람이 닿지 않는 남용 막기(속도 제한과 같은 갈래)이고, 사용자에게 보이는 한도 · 남은 수 · 안내 화면이 없다(앱 104 의 5).
   - 알려진 빈틈: 지운 궁합은 세지 않는다(지우고 다시 만들면 더 만들 수 있다). 생성 한도(시간 30)가 그 길을 묶는다. 동시에 보낸 요청은 한두 개 넘을 수 있다(안전 한도라 잠그지 않는다).
7. **여러 명 지우기**(제안 `POST /synastry/delete { ids[] }`) → 204. 남의 것 · 없는 것 · id 모양이 아닌 값이 하나라도 섞이면 404 `NOT_FOUND` 이고 아무것도 지우지 않는다. 목록이 없거나 비었거나 형식이 틀리면 400 `INVALID_REQUEST`. 한 명 지우기(`DELETE /synastry/{id}`)와 같이 그 궁합의 방향성 제시도 지우고, 감사 기록은 한 명마다 `SYNASTRY_DELETED` 한 줄이다. 링크로 생긴 궁합도 내 행만 지운다.
8. **"나왔어요" 알림은 30초 규칙이다**(앱 104 의 9).
   - `GET /synastry/{id}` 가 `DONE` 을 돌려주면 그 행에 `seen_at` 을 적는다(목록 `GET /synastry` 는 적지 않는다).
   - 10초마다(`byeolmyeong.synastry.notice-check-seconds`) 도는 일이 `done_at` 이 30초(`notice-after-seconds`) 지났고 `seen_at` · `notified_at` 이 없는 `DONE` 행을 한 문장의 조건부 update(`for update skip locked`)로 잡아 `notified_at` 을 적고, 잡은 행마다 `SynastryReportDone` 을 낸다 → 알림함 `SYNASTRY_REPORT_DONE` 한 줄 + 푸시 `SYNASTRY_REPORT_DONE`(제목 "○○ 님과의 궁합이 나왔어요" · 본문 "점수와 풀이를 볼 수 있어요." · `data.synastryId` — 캔버스 "궁합 (결정 104)" 12 알림 칸의 글). 점수 · 사이는 적지 않는다.
   - 배포 중 두 서버가 함께 돌아도 조건부 update 라 한 번만 간다(`.claude/rules/two-servers-overlap.md` 의 줄 단위 선점). 잡은 뒤 이벤트를 내기 전에 서버가 꺼지면 그 알림은 빠진다(두 번 가는 것보다 한 번 빠지는 쪽 — 결정 042 와 같다).
   - 다시 하기 · 다시 쓰기를 시작하면 `done_at` · `seen_at` · `notified_at` 을 비운다. 그 결과도 같은 규칙을 따른다.
   - 서비스 알림이라 푸시 동의만 본다. 밤 무음은 계획 S4-4(결정 055)가 메시지 모양과 함께 정한다.
9. **감사 · 현황**: `SYNASTRY_CREATED` 는 `POST /synastry` 가 쓰기 시작했을 때 남는다(detail `{ relation }`, 이름 · 출생 정보 없음). 글이 실패한 것은 감사가 아니라 행의 `FAILED` · 로그 `synastry_report_failed` · 관리자 현황이 보여 준다. 다시 하기 · 다시 쓰기의 시작은 `SYNASTRY_REPORT_REQUESTED` 다. 관리자 현황의 `synastryReports` 는 `synastries` 의 상태(`WRITING` · `DONE` · `FAILED`)를 센다(새 궁합의 쓰는 중 · 실패는 그 표에만 있다). 살아 있다는 표시가 2분 넘게 끊긴 `WRITING` 은 앱이 보는 것과 같이 `FAILED` 로 센다(PR 리뷰 반영).
10. **상대의 이름은 AI 에 보내지 않는다.** 글 생성 요청에는 `generation.synastryFactsText` 의 재료(사이 · 점수 · 연결)만 들어간다(010 의 5 그대로). 처리방침 초안(앱 `docs/drafts/2026-11-27-legal-1.1.0.md`)이 이것을 전제로 한다. 통합 테스트가 가짜 생성기가 받은 지시문 · 재료에 이름 · 출생 정보가 없음을 본다.
11. **마이그레이션(V38)은 더하기와 NOT NULL 풀기만** 한다(결정 046). `status` 의 기본값은 `DONE`, `source` 는 `DIRECT` 라 겹치는 동안 옛 jar 가 넣는 행도 맞다. 검사: `NEW_STAR` 가 아니면 `relation` · `score` 가 있고, `DONE` 이면 `headline` · `keywords` 가 있다. 옛 행은 모두 `DONE`(요약이 있다)이다. 겹치는 동안 새 jar 가 만든 `WRITING` 행을 옛 jar 의 `GET /synastry` 가 읽으면 그 요청이 실패한다 — 1.0.0 앱은 궁합을 부르지 않아 닿는 사람이 없다고 보고 두 번 배포로 나누지 않는다.

## 근거
- 같은 행에 상태를 두면 목록 · 지도 · 순위 · 보는 중 줄 · 실패 줄을 `GET /synastry` 하나로 그린다. 새로 온 별(S③)도 같은 행의 한 상태다.
- 상대 출생 정보를 저장하므로 "그 궁합의 것인지" 를 확인할 일(`BIRTH_MISMATCH`)과 다시 입력받는 화면이 사라진다.
- "다시 써야 함" 을 따로 적지 않고 `owner_chart_hash` 와 지금 차트를 견주면, 쓰는 도중에 출생 정보가 바뀐 경우(옛 차트로 끝난 글)도 같은 규칙으로 다시 쓰인다.
- 30초 규칙: 앱이 화면을 떠났는지 서버는 알 수 없다. 보는 중 화면은 몇 초마다 `GET` 하므로 30초 안에 받아 가면 화면에 있던 것이다.

## 대안
- 요약이 나오면 바로 결과, 네 영역은 쓰는 중 모양으로(앱 104 의 4 가 한 번 정했다 뒤집음): 기다림이 짧지만 결과 화면이 두 모양이다. 스테이징 실측(로그 `synastry_report_done elapsed_ms`)이 길면 이쪽으로 바꾼다.
- 요약과 네 영역을 처음부터 다섯 개 동시에: 더 빠르지만 요약이 실패해도 네 영역 원가가 나간다. 앱 104 의 4 가 "요약을 한 번 쓴 뒤 네 영역을 동시에" 로 적었다.
- 하루 한도를 감사 기록(`SYNASTRY_CREATED`)으로 세기: 지운 것까지 세지만 `synastry` 가 `audit` 의 표를 읽게 된다(기능끼리는 이벤트로만).
- 실천 셋을 따로 한 번 더 부르기: 원가 로그에 새 이름이 생기고 호출이 다섯 번이 된다.

## 결과 · 재검토
- 계약: docs/architecture.md 의 궁합 행들(같은 PR 에서 고침). 앱 A④ 가 새 계약(`POST /synastry` · `GET /synastry/{id}` 의 `status` · `reasons` · `report.sections[LASTING].practices` · `POST /synastry/{id}/retry` · `POST /synastry/delete`)으로 화면을 만든다.
- 처리방침에 상대 출생 정보 · 이름의 수집 · 보관 · 파기를 적는다(앱 104 의 6, 약관 초안 — 서버 main 이 운영에 가기 전).
- 한 번에 만들기의 기다림을 스테이징에서 잰다. 길면 "요약 먼저"(위 대안).
- 하루 한도 50 · 30초 · 10초는 설정값이다. 운영 로그를 보고 바꾼다.
