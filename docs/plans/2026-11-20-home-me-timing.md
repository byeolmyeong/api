---
type: plan
status: approved
tags: [홈, 나 탭, 시기, 오늘의 하늘, 그날의 하늘, 알림함, 공유, 근거 구조, 자세한 풀이]
last_reviewed: 2026-11-20
---
# 홈 · 나 탭 · 시기 · 오늘의 하늘 · 알림함 · 공유 v2 — 서버

> 상태: 승인(소유자 2026-11-20 "그 작업해야지 지금까지 만들어 놓은 것들은" — 확정 시안대로 구현 승인) · 기준 2026-11-20 develop `7ed7b42`(PR #24 — 결정 026~028 · V21 까지) · 브랜치 `docs/home-me-timing-plan` · 앱 계획 `../byeolmyeong-app/docs/plans/2026-11-20-home-me-timing.md`(앱 브랜치 `docs/home-me-timing-plan`) · 시안: 피그마 `UlZGMcMO4LidtzLlJl4KFv` 「별명」 보드 11 · 12 · 14 · 캔버스 https://claude.ai/artifact/Rt33TzPSEe4PdxNRCej3xT

## 목표
- 무엇을: 확정 시안(홈 · 나 탭 · 시기 · 오늘의 하늘 · 알림함 · 공유)에 필요한 서버 일을 한다 — 근거를 구조로 주고, 심층 리포트를 나 탭 풀이 · 시기 풀이 둘로 나누고, 이번 달을 계산하고, 그날의 하늘을 만들어 저장하고, 알림함과 새 공유 카드를 더한다.
- 누구를 위해: 앱을 다시 열 이유(오늘의 하늘 · 지금 나의 시기)가 필요한 사용자, 1.1.0 에 풀이를 팔 소유자.
- 끝나면: 앱 계획의 묶음 A①~A⑤ 가 기다리는 경로가 스테이징에 모두 있고, 계약 표(`docs/architecture.md`)가 그 모양 그대로다. 옛 경로는 새 테스트앱 빌드 뒤 정리 PR(S⑦)에서 빠진다.

## 결정된 전제
- 소유자 질문 · 답 원본: 앱 저장소 `docs/raw/2026-11-20-session-owner-qna-home-me-timing.md` 1~12번. 원본이 이 계획보다 우선한다.
- 서버 결정: 029(풀이 나누기 · 받을 수 있나 자리) · 030(나 탭 풀이 v2 · 요약 해석 v2) · 031(근거 구조) · 032(이번 달) · 033(시기 풀이 v2) · 034(그날의 하늘) · 035(알림함) · 036(공유 v2) · 037("~기") · 038(일 방식). 대체 표시: 008 · 009 · 011 · 012.
- 앱 결정 046(아래 탭 5칸) · 047(홈 v2) · 048(나 탭) · 049(시기 탭) · 050(풀이 둘) · 051(오늘의 하늘 1.0.0) · 052(알림함) · 053(공유 v2) · 054(이유 문장) — 앱 저장소 `docs/decisions/`.
- 홈의 궁합 랭킹 · 궁합 탭은 보류다(원본 12번 — 궁합 시안이 나오면 새 결정). 이 계획은 궁합을 바꾸지 않는다(궁합 리포트 완성 알림만 더한다).
- 결제는 만들지 않는다. 1.0.0 은 두 풀이 모두 무료이고 확인 자리만 둔다(원본 4번).

## 번호
- develop 과 모든 원격 브랜치를 훑었다(2026-11-20): 서버 결정은 028 이 마지막이라 **029~038**, 마이그레이션은 V21 이 마지막이라 **V22~V25**. 앱 결정은 온보딩 v2(로컬 `feat/onboarding-v2`)가 044 · 045 를 써서 **046~054**.

## 내 결정 (묻지 않고 고른 기본값)
- 근거 후보의 범위(결정 031 의 4): 천체 10 + 시각을 알면 상승점 · 중천, 3개 이상 모인 하우스, 각도(행성 사이 + 해 · 달과 상승점 · 중천의 합 · 충 · 사분), 해 · 달 · 상승점 별자리를 다스리는 별(전통 지배성). 지점(교점 · 릴리스 · 행운점 · 버텍스)은 근거 후보에 넣지 않는다.
- 새 기능 패키지 둘: `sky`(이번 달 · 그날의 하늘), `notification`(알림함). 나 탭 풀이 · 시기 풀이는 `report` 에 둔다. 통과 계산 · 다스리는 별 · 일 방식은 `chart` 의 순수 함수, 문구표 · 구간 표(`TimingThemes` 를 `report` 에서 옮김) · 근거 후보 · 실천 검사는 `generation`(기능 패키지끼리 의존할 수 없어서).
- "받을 수 있나" 는 `report/EntitlementPolicy.kt` 의 함수 하나(1.0.0 언제나 허용). 인터페이스를 두지 않는다.
- `GET /timing/month` 의 속도 제한 `byeolmyeong.rate-limit.month-per-minute=60`(계정별). 생성 한도에는 세지 않는다.
- 알림 30일 지우기는 매일 한국 시간 04:00.
- 실천 한 줄은 40자 안팎(지시문만 — 검사는 "기" 끝맺음과 빈 글).

## 범위 밖
- 결제(RevenueCat) · 402 를 실제로 쓰는 판정 — 1.1.0 결제 계획.
- 휴대폰 푸시 발송 · 기기 토큰 · "오늘의 하늘 알림" 켜기/끄기 · 아침 미리 만들기 — 푸시 계획.
- 궁합 탭 · 홈 궁합 칸 · 궁합 시안 — 보류.
- 옛 `reports` 테이블 · 옛 `SUMMARY` 행 지우기(DB 파괴적 변경 — 따로 소유자 확인).
- 관리 화면에 새 데이터 보이기.

## 전역 제약
- Spring Boot 4.1 · Kotlin · JVM 21 · Flyway. 마이그레이션은 더하기만(V25 의 제약 넓히기는 행을 바꾸지 않는다).
- 계약을 바꾸면 `docs/architecture.md` 의 계약 표를 같은 커밋에서 고친다(CLAUDE.md). 새 기능 패키지 · 새 이벤트는 "모듈" · "의존 규칙" 과 `ArchitectureTest` 의 목록을 같은 커밋에서.
- 테스트 먼저(빨강 → 초록). 차트 계산은 `chart` 의 순수 함수 + 기준값 테스트.
- 시각 "모름": 하우스 · 상승점 · 중천 · CLUSTER · 하우스 통과 · 버텍스는 후보 · 응답 · 프롬프트 어디에도 없다.
- 출생 정보 · 차트 본문은 로그 · 이벤트 · 공유 카드에 넣지 않는다. 로그는 `event` 식별자 + 코드 값만.
- 생성의 실패 · 빈 응답 · 구조가 틀린 응답은 저장하지 않는다. 재시도는 호출마다 2회까지(지금 규칙).
- 모델 호출은 `generation.TextGenerator` 뒤에만.

## 계약 표 (앱이 그대로 타입으로 옮긴다)
### 공통 타입
- `Body` = `SUN` · `MOON` · `MERCURY` · `VENUS` · `MARS` · `JUPITER` · `SATURN` · `URANUS` · `NEPTUNE` · `PLUTO` · `Sign` = `ARIES` … `PISCES` · `AspectType` = `CONJUNCTION` · `SEXTILE` · `SQUARE` · `TRINE` · `OPPOSITION`(지금 `Chart` 와 같은 글자) · `Angle` = `"ASC"` | `"MC"`.
- `BasisItem`(결정 031) — `kind` 로 나눈다:
  - `{ "kind": "PLACEMENT", "body": Body | Angle, "sign": Sign, "house"?: 1..12 }`
  - `{ "kind": "CLUSTER", "house": 1..12, "bodies": [Body, …] }`(3개 이상)
  - `{ "kind": "ASPECT", "a": Body | Angle, "b": Body | Angle, "type": AspectType }`
  - `{ "kind": "RULER", "body": Body, "ruler": Body }`
  - `{ "kind": "TRANSIT", "mover": Body, "target"?: Body | Angle | "VERTEX", "type"?: AspectType, "house"?: 1..12 }`(target + type, 또는 house)
  - 시각을 모르면 `house` 키 · `CLUSTER` · `ASC` · `MC` · `VERTEX` · 하우스 통과가 나오지 않는다(값이 null 인 키를 보내지 않는다).
- `Reason` = `{ "when": "BIRTH" | "NOW" | "DAY", "items": [BasisItem](1~2) }`. `TRANSIT` 은 `NOW` · `DAY` 에만, 나머지는 `BIRTH` 에만.
- `Star` = `{ "body"?: Body | Angle, "sign"?: Sign, "house"?: 1..12, "count"?: 정수 }`(그 영역 문단들의 reason 에서 서버가 겹치지 않게 앞에서부터 1~3개).
- 오류 공통: 404 `NO_PROFILE`(저장한 출생 정보가 없다) · 429 `RATE_LIMITED`(생성 한도 분당 5 · 시간당 30, 결정 022 · 023 — 새로 생성할 때만) · 502 `GENERATION_FAILED`(저장하지 않는다) · 402 `PURCHASE_REQUIRED`(**예약** — 1.1.0 결제 확인에서만, 1.0.0 에는 나오지 않는다).

### 경로
| 경로 | 인증 | 요청 | 응답 · 오류 | 묶음 |
|---|---|---|---|---|
| `POST /readings` (바뀜) | 세션 | 없음 | `Reading` = `{ headline, sections[{ title, body, reason: Reason, basis[] }], strengths[3], cautions[2], createdAt, shareId }` · `createdAt` UTC `YYYY-MM-DDTHH:mm:ssZ`(그 요약 해석을 만든 때) · `basis[]`(근거 낱말) · `shareId` 는 옮기는 동안만, S⑦ 에 뺀다 · 문단은 시각을 알면 5 · 모르면 4 · 오류는 지금과 같다 | S① |
| `POST /me-report` (새) | 세션 | 없음 | 202 `{ status: WRITING \| DONE \| FAILED }` · 상태 규칙은 지금 `POST /reports` 와 같다(없거나 FAILED 면 시작, DONE · WRITING 이면 그대로, 자리가 꽉 차면 FAILED, `active_at` 2분) · 404 `NO_PROFILE` · 429 · (402 예약) | S② |
| `GET /me-report` (새) | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report?: MeReport }` · `report` 는 DONE 일 때만 · 지금 프로필 차트 기준(출생 정보를 고치면 NONE) | S② |
| `GET /timing/month?month=YYYY-MM` (새) | 세션 | `month` 선택 — 없으면 한국 날짜의 이번 달 | 200 `MonthSky` · 400 `INVALID_MONTH`(형식) · 400 `MONTH_OUT_OF_RANGE`(지난달 · 이번 달 · 다음 달 밖) · 404 `NO_PROFILE` · 429(계정별 분당 `month-per-minute`) · 생성 한도에 세지 않음 · 저장 안 함 · 시기 풀이 없이 열림 | S③ |
| `POST /timing-report` (새) | 세션 | 없음 | 202 `{ status }`(me-report 와 같은 규칙) | S③ |
| `GET /timing-report` (새) | 세션 | 없음 | 200 `{ status, report?: TimingReport }` | S③ |
| `POST /day-sky` (새) | 세션 | `{ date?: "YYYY-MM-DD" }` — 없으면 한국 날짜의 오늘 | 200 `DaySky` · 저장본이면 바로, 없으면 만들어 저장(수십 초) · 400 `INVALID_REQUEST`(본문 형식) · 400 `INVALID_DATE` · 400 `DATE_OUT_OF_RANGE`(지난달 1일 ~ 다음 달 말일 밖) · 404 `NO_PROFILE` · 429(새로 만들 때 생성 한도) · 502 `GENERATION_FAILED` | S④ |
| `GET /notifications` (새) | 세션 | 없음 | 200 `{ unread, items[{ id, kind, createdAt, read, synastryId? }] }` · 30일 안 · 새것부터 · 100개까지 | S⑤ |
| `POST /notifications/{id}/read` (새) | 세션 | 없음 | 204(이미 읽었어도) · 남의 것 · 없는 것 · UUID 가 아닌 값 404 `NOT_FOUND` | S⑤ |
| `POST /shares` (새 종류) | 세션 | `{ kind: NICKNAME }` · `{ kind: ME_AREA, area }` · `{ kind: DAY_SKY, date }` · `{ kind: NEXT_BIG }` | 200 `{ shareId, card }` · 공유할 글이 없으면 404 `NOT_FOUND` · `area` 가 다섯 밖 400 `INVALID_AREA` · `date` 형식 400 `INVALID_DATE` | S⑥ |
| `POST /reports` · `GET /reports/me` · `POST /directions {LOVE \| FUTURE}` | — | — | 앱이 더 부르지 않는다 → S⑦ 에서 뺀다(공용 API 제거, 소유자 확인) | S⑦ |

### 응답 모양
- `MeReport` = `{ areas: [MeArea × 5] }` — 순서 `PERSONALITY` · `LOVE` · `RELATIONSHIP` · `WORK` · `EMOTION`.
- `MeArea` = `{ area, title, keywords[3], stars[Star 1~3], paragraphs[{ title, body, reason: Reason }], glance?, practices?, reason?, closer?, workStyle?, shaken? }`
  - `paragraphs`: LOVE 말고 정확히 4개. LOVE 는 `[]`.
  - `PERSONALITY` → `glance` = `{ shining[2], tiring[2] }`
  - `LOVE` → `practices` = `[{ title, body } × 3]`(title 은 "~기") + `reason: Reason`
  - `RELATIONSHIP` → `closer` = `{ first, close, longtime }`
  - `WORK` → `workStyle` = `{ together, flexible, fast }` — 정수 10~90, 0 쪽 = 혼자서 · 계획대로 · 꼼꼼하게(서버 계산, 결정 038)
  - `EMOTION` → `shaken` = `{ when, comfort }`
  - 해당 영역이 아닌 칸은 키가 없다.
- `MonthSky` = `{ month: "YYYY-MM", timeKnown, themes[{ area: PEOPLE | WORK | MONEY | SELF, title, from, to }](0~2), days[{ date, category: PEOPLE | WORK | MIND, text, reason: Reason }](0~3) }` — `days` 가 달력의 점이자 "눈여겨볼 날", `reason.when` 은 `DAY`.
- `TimingReport` = `{ timeKnown, milestones[4], intervals[], current }` — 지금 `Report.timing` 과 같은 모양에 더해:
  - `current.mainId?` — 대표 구간 id(오늘 걸친 구간 중 CHANGE BIG > CHANGE SMALL > FATED > OPPORTUNITY, 같으면 늦게 시작한 것). 없으면 키 없음. 읽을 때 정한다.
  - 구간 `title` 은 서버 표 제목(이번 달 `themes[].title` 과 같은 말).
  - 모델이 쓴 구간(오늘 걸친 것 + 10년 안에 시작하는 것, 30개까지)에만: `summary` · `body` · `detail{ changes, howTo, after }` · `practices[{ title } × 3]` · `reason`(`when: NOW`, TRANSIT). 나머지 구간은 이 키들이 없다(`body` 는 표 본문).
  - 마디: 지금처럼 `title` · `body`(모델) · `year` · `age` · `date` · `from` · `to` · `size` · `kind`.
- `DaySky` = `{ date, title, category: PEOPLE | WORK | MIND, flow{ people, work, mind }, reason: Reason(when DAY), practices[{ title } × 3], createdAt }`.
- 알림 `kind` = `READING_DONE` · `ME_REPORT_DONE` · `TIMING_REPORT_DONE` · `SYNASTRY_REPORT_DONE`(이것만 `synastryId`). 문구는 앱.
- 새 공유 카드(`card`): `NICKNAME` `{ kind, nickname?, sunSign, headline }` · `ME_AREA` `{ kind, nickname?, area, title }`(LOVE 는 영역 제목, 나머지는 첫 문단 제목) · `DAY_SKY` `{ kind, nickname?, date, title }` · `NEXT_BIG` `{ kind, nickname?, year, age, title }`. `nickname` 은 옛 계정이라 없으면 키 없음.

## 마이그레이션 (더하기만)
- `V22__detail_reports.sql` — `detail_reports`(id uuid pk · owner_id → accounts on delete cascade · kind text check in ('ME','TIMING') · chart_hash · status check in ('WRITING','DONE','FAILED') · body jsonb · started_at · updated_at · active_at, unique(owner_id, kind, chart_hash), check((status = 'DONE') = (body is not null))) — `reports`(V4 · V9)와 같은 꼴.
- `V23__day_skies.sql` — `day_skies`(id · owner_id → accounts cascade · chart_hash · date date · body jsonb not null · created_at, unique(owner_id, chart_hash, date)).
- `V24__notifications.sql` — `notifications`(id · owner_id → accounts cascade · kind check 넷 · synastry_id uuid null · created_at · read_at null), 색인 (owner_id, created_at desc).
- `V25__share_kinds.sql` — `shares.kind` 의 check 를 지우고 (READING · SCORE · RANKING · NEXT_TURN · NICKNAME · ME_AREA · DAY_SKY · NEXT_BIG) 로 다시 건다. 행은 바뀌지 않는다.
- 요약 해석 v2 는 마이그레이션이 없다(`readings.kind = 'SUMMARY_V2'`).

## 프롬프트 (`src/main/resources/prompts/`)
| 파일 | 무엇 | 묶음 |
|---|---|---|
| `writing-rules.md` (바뀜) | "실천은 '~기' 로 끝나는 한 줄", 근거는 "[근거 후보]" 의 id 에서 고른다 | S① |
| `reading-summary.md` · `reading-summary-time-known.md` · `reading-summary-time-unknown.md` (바뀜) | 보완점 2 · 문단마다 근거 id 1~2 | S① |
| `me-report.md` (새) | 나 탭 풀이 공통(문단 4 · 두 문장 · 키워드 3 · 근거 id) | S② |
| `me-report-personality.md` · `me-report-love.md` · `me-report-relationship.md` · `me-report-work.md` · `me-report-emotion.md` (새) | 영역별 칸(glance · practices · closer · shaken), 일은 숫자를 쓰지 않음 | S② |
| `report-time-unknown.md` (그대로) | 시각 모름 — 나 탭 풀이 · 시기 풀이 · 그날의 하늘이 같이 붙인다 | — |
| `timing-report.md` (새, `report-timing.md` 를 옮겨 고침) | 9줄 묶음마다 줄의 summary · body · detail · practices, 제목은 쓰지 않음, 해는 옮겨 적기만 | S③ |
| `day-sky.md` (새) | 정해진 제목 · 근거로 흐름 셋 · 실천 셋(눈여겨볼 날이 아니면 제목도) | S④ |
| `report.md` · `report-personality.md` · `report-relationship.md` · `report-work.md` · `report-emotion.md` · `report-timing.md` · `direction-love.md` · `direction-future.md` | 옛 경로와 함께 지움(`direction.md` · `direction-synastry.md` · `direction-time-unknown.md` 는 궁합 주제가 계속 쓴다) | S⑦ |

## AI 호출 수 (재시도는 호출마다 2회까지 더)
| 언제 | 호출 | 옛것 |
|---|---|---|
| 가입(별명) | 요약 해석 1 · 이번 달 0 | 1 |
| 나 탭 풀이 | 영역 5(한꺼번에) | 심층 리포트 5(4 영역 + 시기 1) |
| 시기 풀이 | 약 4(마디 4 + 구간 30까지를 9줄씩 묶어 — 설명 · 자세히 · 실천) | (리포트 안 1) |
| 오늘의 하늘 | 그날 처음 연 사람마다 1 | 없음 |
| 그날의 하늘 | 누른 날짜마다 1(오늘은 오늘의 하늘과 같은 글 · 앞뒤 한 달만) | 없음 |
| 없어짐 | 방향성 LOVE 1 · FUTURE 시기 바뀔 때 1 | |

## 기존 데이터 (출시 전)
- 요약 해석: 새 모양은 kind `SUMMARY_V2`. 옛 `SUMMARY` 행은 읽지 않으므로 다음 `POST /readings` 가 새로 만든다(생성 한도 한 번). 원본 5번 "이미 만든 풀이 · 리포트는 새 모양으로 다시 만든다".
- 심층 리포트: 옛 `reports` 행은 새 경로가 읽지 않는다. 사용자가 나 탭 · 시기 탭에서 "자세한 풀이 받기" 를 다시 누르면 새로 만든다. 옛 행 지우기는 범위 밖.
- 방향성 LOVE · FUTURE 저장본: 읽지 않게 된다(S⑦ 뒤 경로가 없다). 행은 남긴다.
- 공유: 이미 만든 `READING` · `NEXT_TURN` 카드는 계속 열린다.

## 태스크

### 0. adr-docs · 상태: 완료
- 할 것: 결정 029~038, 008 · 009 · 011 · 012 앞머리 대체 표시, 이 계획.
- 검증: `ls docs/decisions | cut -c1-3 | sort | uniq -d` → 비어 있음, 원격 어느 브랜치에도 029~038 · V22~V25 없음

### S① 근거 구조 · 요약 해석 v2 · "~기" (결정 031 · 030 의 4 · 037)

#### 1. s1-basis-candidates · 상태: 대기
- 읽을 것: 결정 031, `generation/Basis.kt` · `ChartFacts.kt`, `chart/Aspects.kt`
- 할 것: `chart/Rulers.kt`(전통 지배성 — 별자리 → 다스리는 별), `generation` 에 `BasisItem` · `Reason` · `Star` 타입, `basisCandidates(chart)`(id → BasisItem 표, 순서 고정), id 를 `BasisItem` 으로 바꾸기, 그 영역 reason 들에서 `Star` 1~3 만들기. `chartFacts` 끝에 "[근거 후보]"(id · 짧은 설명 — 용어는 모델에게만) 블록.
- 주는 것: 후보 id 꼴(`P-SUN` · `P-ASC` · `C-5` · `A-SATURN-SUN-SQUARE` · `R-MOON` · `T-…`)
- 하지 말 것: 지점(교점 · 릴리스 · 행운점 · 버텍스)을 출생 근거 후보에 넣지 않는다. 이유: 시안 용어 사전에 없다(결정 031 의 4).
- 검증: 단위 테스트 — 시각 모름 차트에 house · ASC · MC · CLUSTER 후보 0개, id 겹침 0, CLUSTER 는 3개 이상만, 염소자리 달 → `R-MOON` 의 ruler SATURN(빨강 먼저)

#### 2. s1-advice-check · 상태: 대기
- 읽을 것: 결정 037, `direction/Direction.kt` 의 `adviceProblem`
- 할 것: 실천 검사를 `generation` 의 함수 하나로 옮기고 끝맺음을 "기" 로 넓힌다(금지 표현은 그대로). 궁합 방향성이 그 함수를 쓰게 한다. `writing-rules.md` 에 한 줄.
- 하지 말 것: 금지 표현 목록을 줄이지 않는다.
- 검증: "전하기" 통과 · "전해요" 실패 · "보기" 통과 · 지금 궁합 방향성 테스트 그대로 초록

#### 3. s1-reading-v2 · 상태: 대기
- 읽을 것: 결정 030 의 4 · 5, 031, `reading/*`, 계약 표 `POST /readings` · `Reading`
- 할 것: 응답 구조의 문단 근거를 후보 id enum(1~2)으로, 보완점 2, `createdAt`(저장 행의 `created_at`), kind `SUMMARY_V2`, 옛 `basis[]` 는 reason 에서 낱말로 만들어 함께. 지시문 세 파일 고침. 계약 표 · "글 생성 규칙"(요약 해석의 모양 · 근거 대조 · 근거에 쓸 수 있는 말) 같은 커밋.
- 주는 것: 앱 A② · A① 이 쓰는 `reason` · `createdAt`
- 하지 말 것: 옛 `SUMMARY` 행을 지우거나 고치지 않는다. 이유: 파괴적 변경 없이 새 kind 로 다시 만든다.
- 검증: 통합 테스트 — 응답에 `reason.items` 1~2 · `cautions` 2 · `createdAt`, 시각 모름 응답에 house 키 없음, 옛 `SUMMARY` 행만 있는 계정은 새로 생성(빨강 먼저)

### S② 나 탭 풀이 · 일 방식 (결정 029 · 030 · 038)

#### 4. s2-migration · 상태: 대기
- 할 것: `V22__detail_reports.sql`(위 "마이그레이션").
- 검증: 깨끗한 DB 와 V21 DB 양쪽에서 돈다(Testcontainers), DONE 인데 body 가 null 이면 거절

#### 5. s2-work-style · 상태: 대기
- 읽을 것: 결정 038, `docs/drafts/2026-11-20-work-style-formula.md`(소유자 검토 뒤 값)
- 할 것: `chart/WorkStyle.kt` 순수 함수(차트 → together · flexible · fast).
- 하지 말 것: 공식의 값을 초안과 다르게 바꾸지 않는다. 이유: 소유자가 본 값이 원본이다.
- 검증: 기준값 테스트 — 초안의 예1(시각 앎) 51 · 36 · 44, 같은 차트 시각 모름 61 · 29 · 40, 예2 69 · 65 · 77, 모든 값 10~90

#### 6. s2-entitlement · 상태: 대기
- 할 것: `report/EntitlementPolicy.kt`(`allows(ownerId, kind)` — 1.0.0 언제나 true). 계약 표에 402 `PURCHASE_REQUIRED` 를 "예약" 으로.
- 하지 말 것: 인터페이스 · 설정 스위치를 만들지 않는다. 이유: 단일 구현 인터페이스 금지, 1.1.0 에 함수 안만 바꾼다.
- 검증: 서비스가 생성 시작 전에 부르는지(테스트 대역으로 false 를 주면 402 · 행이 FAILED 로 남지 않음)

#### 7. s2-me-report · 상태: 대기
- 읽을 것: 결정 029 · 030, `report/ReportService.kt`(자리 잡기 · 심장박동 · 일꾼), `Report.kt`
- 할 것: `POST/GET /me-report`, `detail_reports`(kind ME), 영역 다섯 생성(한꺼번에) · 영역별 칸 구조 · 검증(문단 4 · 키워드 3 · LOVE 실천 "~기" · 숫자 금지), `stars` 만들기, WORK 에 `workStyle` 넣기, 생성 한도 한 번, `MeReportDone` 이벤트(허용 목록에 추가). 지시문 `me-report*.md` 여섯. 계약 표 · "리포트 상태" · "데이터" · 모듈 설명 같은 커밋.
- 하지 말 것: 옛 `POST /reports` 를 지우거나 바꾸지 않는다. 이유: 옛 테스트앱이 쓴다(S⑦).
- 검증: 통합 테스트 — NONE → WRITING → DONE, 영역 순서 · 칸, 한 영역 끝내 실패 → FAILED · 저장 없음, 출생 정보 고친 뒤 NONE, 시각 모름에 house 키 없음, 2분 멈춤 → FAILED

### S③ 이번 달 · 시기 풀이 (결정 032 · 033, 029 의 시기 쪽)

#### 8. s3-move-themes · 상태: 대기
- 할 것: `report/TimingThemes.kt` → `generation`(동작 그대로, 옮기기만).
- 검증: 지금 시기 테스트 그대로 초록, `ArchitectureTest` 초록

#### 9. s3-transits · 상태: 대기
- 읽을 것: 결정 032 의 3, 034 의 3, 문구표 초안의 "고르는 규칙", `chart/Positions.kt` · `Aspects.kt`
- 할 것: `chart/Transits.kt` — 그날(한국 날짜 정오)의 통과 목록(지나가는 별 × 내 점 × 각도, 허용 오차 1°, 세기 순 — 달 포함 여부는 인자), 그달의 눈여겨볼 날 후보(해 · 수성 · 금성 · 화성, 조합마다 가장 정확한 날, 세기 · 사흘 간격 · 칩 겹침 규칙으로 셋까지).
- 하지 말 것: 날짜 · 도수를 모델에게 넘기지 않는다.
- 검증: 기준값 테스트(고정 차트 · 고정 달) — 같은 입력 같은 결과, 시각 모름이면 ASC · MC 대상 0, 달 빼기 옵션

#### 10. s3-month-phrases · 상태: 대기(문구표 초안 소유자 검토 뒤)
- 읽을 것: `docs/drafts/2026-11-20-month-phrases.md`
- 할 것: `generation` 에 문구표(칸 = 지나가는 별 × 내 별 × 결 — 칩 · 문구 3~5) · 고르기(`날짜 + 조합 id` 의 고정 해시 mod 문구 수, 한 달 안 겹침이면 다음 것).
- 하지 말 것: 소유자가 보지 않은 문구를 넣지 않는다(초안 그대로면 PR 에 "초안" 표시).
- 검증: 같은 날 · 같은 조합 → 같은 문구, 한 달 셋의 문구 겹침 0, 모든 칸의 문구 수 3~5, 문구에 숫자 · 금지 표현 없음

#### 11. s3-month-endpoint · 상태: 대기
- 할 것: 새 기능 패키지 `sky` — `GET /timing/month`(month 선택 · 범위 · 오류), `themes`(그달에 걸친 구간 우선순위 둘, 제목 겹침 없음), `days`(9 · 10 의 결과 + reason). 속도 제한 `month-per-minute`. 계약 표 · 모듈 · 요청 속도 제한 같은 커밋.
- 하지 말 것: 저장하지 않고 생성 한도에 세지 않는다.
- 검증: 통합 테스트 — month 없음 → 이번 달, 범위 밖 400, 형식 400, 출생 정보 없음 404, 같은 요청 두 번 같은 본문

#### 12. s3-timing-report · 상태: 대기
- 읽을 것: 결정 033, `report/ReportTiming.kt` · `ReportService.kt`
- 할 것: `POST/GET /timing-report`(kind TIMING), 구간 제목을 표 제목으로 고정, 9줄 묶음 약 4 호출(한꺼번에) · 묶음별 재시도, 모델 구간의 summary · body · detail · practices · reason, `current.mainId`(읽을 때), `TimingReportDone`. 지시문 `timing-report.md`. 계약 표 · "시기" · "글 생성 규칙"(시기의 글) 같은 커밋.
- 하지 말 것: 옛 `POST /reports` 의 시기 생성은 바꾸지 않는다(S⑦ 까지).
- 검증: 통합 테스트 — 모델 구간에만 detail · practices · reason, 표 구간에는 키 없음, 구간 제목 = 표 제목, `mainId` 우선순위(고정 날짜), 한 묶음 끝내 실패 → FAILED, YEAR_MISMATCH 재시도

### S④ 그날의 하늘 · 오늘의 하늘 (결정 034)

#### 13. s4-migration · 상태: 대기
- 할 것: `V23__day_skies.sql`.
- 검증: 깨끗한 DB · V22 DB 에서 돈다, 같은 (owner, chart_hash, date) 두 번째 insert 거절

#### 14. s4-day-sky · 상태: 대기
- 읽을 것: 결정 034, `reading/ReadingService.kt`(진행 중 생성 표시 · 기다리기)
- 할 것: `sky` 에 `POST /day-sky` — 날짜 범위, 눈여겨볼 날이면 그 문구 · 칩 · 근거, 아니면 그날 가장 센 통과(달 포함)로 근거, 모델은 흐름 · 실천(+ 제목), 검증(칸 · "~기" · 숫자 · 금지 표현), 저장 · 같은 사람 같은 날 동시 요청 하나만, 생성 한도(새로 만들 때만). 지시문 `day-sky.md`. 계약 표 · "데이터" · 생성 한도 목록 같은 커밋.
- 하지 말 것: 아침 일괄 생성 · 알림을 만들지 않는다(범위 밖).
- 검증: 통합 테스트 — 저장본 재요청은 모델 0회 · 한도 0회, 동시 두 요청 → 모델 1회, 범위 밖 400, 눈여겨볼 날의 제목 = 이번 달 `days[].text`, date 없음 → 한국 오늘

### S⑤ 알림함 (결정 035)

#### 15. s5-migration · 상태: 대기
- 할 것: `V24__notifications.sql`.
- 검증: 깨끗한 DB · V23 DB 에서 돈다, 계정 삭제 시 함께 지워짐

#### 16. s5-events · 상태: 대기
- 할 것: `reading.ReadingCreated(ownerId)`(그 차트의 SUMMARY_V2 를 처음 저장했을 때), `synastry.SynastryReportDone(ownerId, synastryId)`. `ArchitectureTest` 의 EVENTS · docs/architecture.md 의존 규칙에 `MeReportDone` · `TimingReportDone` · 이 둘을 더한다.
- 검증: 이벤트에 글 · 출생 정보 필드 없음(타입 검사), 저장본을 돌려준 요청은 이벤트 없음

#### 17. s5-notifications · 상태: 대기
- 할 것: 새 기능 패키지 `notification` — 리스너(커밋 뒤 · 자기 트랜잭션 · 실패는 삼키고 `event` 로그), `GET /notifications` · `POST /notifications/{id}/read`, 매일 04:00(한국) 30일 지난 행 지우기, `SynastryDeleted` 에 그 궁합 알림 지우기. 계약 표 · 모듈 · "데이터" 같은 커밋. 계약 표의 `PUT /devices/push-token` · 모듈의 `push` 에 "구현 전" 표시(문서와 코드의 어긋남).
- 하지 말 것: 알림 문구를 서버에 두지 않는다.
- 검증: 통합 테스트 — 완성마다 한 행, 남의 알림 read 404, 31일 된 행 지움, unread 수, 100개 상한

### S⑥ 공유 v2 (결정 036)

#### 18. s6-migration · 상태: 대기
- 할 것: `V25__share_kinds.sql`.
- 검증: 옛 네 종류 행이 그대로 남고 새 종류 insert 가 된다

#### 19. s6-share-kinds · 상태: 대기
- 읽을 것: 결정 012 · 036, `share/*`
- 할 것: 새 종류 넷 · 카드 모양 · 묻는 이벤트(`NicknameCardRequested` · `MeAreaCardRequested` · `NextBigCardRequested` · `DaySkyCardRequested`, 허용 목록에 추가) · 답하는 쪽(reading · report · sky). 계약 표 · "공유" 같은 커밋.
- 하지 말 것: 카드에 출생 날짜 · 시각 · 곳 · 도수를 넣지 않는다.
- 검증: 통합 테스트 — 종류마다 카드 모양, 글이 없으면 404, 같은 내용은 한 행(해시), 공개 조회가 새 카드를 그대로

### S⑦ 옛 경로 정리 (새 테스트앱 빌드가 나온 뒤)

#### 20. s7-remove-old · 상태: 대기(소유자 확인)
- 할 것: `POST /reports` · `GET /reports/me` · `POST /directions {LOVE | FUTURE}` · `Reading.sections[].basis` · `Reading.shareId` 를 응답 · 코드 · 계약 표에서 뺀다. 옛 지시문(위 "프롬프트" 표의 S⑦ 줄) 지움. `report.ReportDone` 지움.
- 하지 말 것: `reports` 테이블 · 행을 지우지 않는다(범위 밖). 궁합 방향성은 남긴다.
- 검증: 제목에 "공용 API 제거" 명시, `./gradlew build` 초록, 앱 develop 에서 옛 경로를 부르는 곳 0(앱 저장소 grep)

### 21. notify-app · 상태: 대기
- 할 것: 묶음마다 PR 설명에 "앱 맞춤 필요" 와 바뀐 계약 줄을 적는다(CLAUDE.md "앱 저장소에 맞춤 변경이 필요하다고 보고").

## 앱 계획과 맞출 것 (배포 순서)
| 차례 | 서버 | 앱 | 기다림 |
|---|---|---|---|
| 1 | S① 근거 · 요약 해석 v2 | A① 아래 탭 5칸 + 홈 v2 | A① 은 서버를 기다리지 않는다(오늘의 하늘 · 종은 숨김, "N일" 은 `createdAt` 이 오면 보임) |
| 2 | S② 나 탭 풀이 | A② 나 탭 | A② 는 S① + S② + 앱 `feat/sky-map` + 온보딩 v2 합친 뒤 |
| 3 | S③ 이번 달 · 시기 풀이 → S④ 그날의 하늘 | A③ 시기 탭 | A③ 은 S③ + S④ |
| 4 | S⑤ 알림함 | A④ 오늘의 하늘 + 알림함 | A④ 는 S④ + S⑤ |
| 5 | S⑥ 공유 v2 | A⑤ 공유 v2 | A⑤ 는 S⑥ + Meta 앱 ID + 개발 빌드 |
| 6 | S⑦ 옛 경로 정리 | — | 새 테스트앱 빌드 뒤 · 소유자 확인 |
- 서버 묶음 사이: S② · S③ 은 S① 위에서 병렬 가능(둘 다 V22 의 `detail_reports` 를 쓴다 — S② 가 마이그레이션을 가져가고 S③ 은 그 위에). S④ 는 S③ 의 문구표 · 통과 계산 위. S⑤ 는 S② · S③ 의 완성 이벤트 위. S⑥ 은 S② · S③ · S④ 위.
- 서버가 먼저 develop(스테이징)에 들어가고, 앱 묶음은 스테이징에서 그 경로를 확인한 뒤 합친다. 옛 경로를 남겨 두므로 서버를 먼저 배포해도 옛 테스트앱이 깨지지 않는다 — 단 S① 뒤 요약 해석은 새로 만들어진다(옛 앱도 `basis[]` 를 받으므로 그대로 보인다).

## 검증
- 묶음마다 `./gradlew build` 통과(서버 `/verify`). 새 테스트가 각 태스크 검증을 덮는다. 계약 표와 응답의 키가 같은지 통합 테스트의 JSON 비교로.

## 리뷰 초점
1. 시각 "모름" 응답 어디에도 house · ASC · MC · CLUSTER · VERTEX 가 새지 않는가 — 1 · 3 · 7 · 11 · 12 · 14.
2. 출생 정보 · 차트 본문이 로그 · 이벤트 · 카드에 들어가지 않는가 — 7 · 14 · 16 · 19.
3. 저장본을 돌려주는 요청이 생성 한도를 쓰지 않는가, 새로 만들 때 한 번만 세는가 — 7 · 12 · 14.
4. 묶음 하나가 실패하면 시기 풀이 전체가 저장되지 않는가 — 12.
5. 그날의 하늘의 동시 요청이 모델을 두 번 부르지 않는가 — 14.
6. 옛 경로 · 옛 칸을 남긴 동안 옛 테스트앱이 그대로 도는가 — 3 · 7.

## 자동 반복
- 없음.

## 위험 · 되돌리기
- 생성 비용: 그날의 하늘은 누른 날짜 수만큼 늘어난다. 앞뒤 한 달 · 생성 한도가 상한이다. 비용이 크면 소유자에게 보고하고 한도를 새 결정으로.
- 시기 풀이 묶음 4회가 리포트 일꾼 자리를 오래 잡는다 — 일꾼 수 · 줄 길이 설정(`byeolmyeong.report.*`)을 스테이징에서 본다.
- 문구표 · 공식이 초안 그대로 나가면 소유자가 본 적 없는 문구가 보인다 — 10 · 5 는 검토 뒤에.
- 되돌리기: 마이그레이션은 더하기만이라 코드만 되돌리면 된다. 요약 해석은 kind 를 되돌리면 옛 행이 다시 읽힌다. S⑦ 은 되돌리기 어렵다(옛 앱이 깨짐) — 소유자 확인 뒤.

## 열린 항목 (소유자 — 기본값으로 진행)
1. 다음 큰 변화 공유 카드의 "2028년 · 만 29세 무렵" 으로 태어난 해를 ±1 로 알 수 있다 → 기본값: 시안대로 둔다(결정 036 · 앱 053).
2. 시기 이름을 문구표 하나로 맞춰 시기 풀이 뒤에도 AI 가 이름을 바꾸지 않게 → 기본값: 그렇게 한다(결정 033 · 앱 049).
3. 시기 자세히를 시기 풀이 때 한꺼번에 만들까, 열 때마다 → 기본값: 한꺼번에(결정 033).
4. 그날의 하늘도 생성 한도(분당 5 · 시간당 30)를 같이 쓴다, 날짜를 빨리 넘기면 429 → 기본값: 그대로(결정 034 · 앱 049).
- 검토할 초안: 이번 달 문구표 `docs/drafts/2026-11-20-month-phrases.md`, 일 방식 공식 `docs/drafts/2026-11-20-work-style-formula.md`(둘 다 "초안 · 소유자 검토 필요").
- 궁합 탭 · 홈 궁합 칸: 보류(궁합 시안이 나오면 새 결정).
- 개인정보 수집 · 이용 동의 글은 앱 온보딩 v2 에서 처리(이 계획 밖).

## 실행 기록
- 0: 결정 029~038 · 대체 표시 · 이 계획(2026-11-20). develop · 원격 브랜치 모두 029~ · V22~ 없음 확인.
