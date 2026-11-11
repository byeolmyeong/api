---
type: architecture
tags: [서버 아키텍처, API 계약, 차트 계산, 해석 생성, 로그인, 의존 규칙, ArchUnit, 용어]
status: active
last_reviewed: 2026-11-11
---
# 서버 아키텍처

> 상태: 활성 · 기준 2026-11-11 · 무엇을 만드는지의 원본은 앱 저장소의 `../byeolmyeong-app/docs/release-1.0.0.md` 다. 2026-11-11 결정 반영: docs/decisions/003-follow-app-decisions-2026-11-11.md

## 서버가 하는 일
- 차트 계산: 출생 정보를 받아 행성 위치·하우스·각도·추가 지점(버텍스 포함, 키론 제외)을 계산한다. 세션이 있어야 받는다. 결정: docs/decisions/002-chart-calculation.md, docs/decisions/003-follow-app-decisions-2026-11-11.md
- 출생 시각 변환과 출생지: 국내 시·군·구 목록을 갖고, 현지 시각을 당시 표준시·서머타임 이력으로 UTC 로 바꾼다.
- 로그인: 구글·애플·카카오 토큰을 검증하고 자체 세션 토큰을 준다. 익명 계정은 없다. 가입 때 만 14세 이상인지 판정하고, 미만이면 계정을 만들지 않는다.
- 글 생성: 계산한 차트로 Claude API(결정 006)를 호출해 요약 해석·심층 리포트·궁합 글·방향성 제시를 만들고 저장한다. 계산은 AI 에게 맡기지 않는다.
- 변곡점: 정해진 마디(토성 사각·귀환·두 번째 귀환, 천왕성 대립)와 변화·기회·운명적 만남 구간을 계산하고, 구간마다 기간·영역·크기를 붙인다. 심층 리포트의 시기 영역(`Report.timing`)으로 준다. 규칙: 앱 저장소 release-1.0.0 F4. 서버가 고른 것: docs/decisions/008-report-timing.md
- 궁합: 직접 입력과 초대 링크. 두 차트를 겹치는 계산과 나 기준 점수(40~100, 관계 유형별)는 서버에서만 한다. 나 기준으로 여러 명과 1:1 매칭한 결과를 랭킹으로 돌려준다(인원 제한 없음). 규칙: 앱 저장소 release-1.0.0 F5.
- 공유 조회, 요청 속도 제한(남용 방지), 푸시 알림 발송, 계정 삭제.

## 모듈 (패키지) — 기능별. 결정: docs/decisions/004-module-boundaries-archunit.md
- `chart` — 계산. 순수 함수만 둔다(입력: UTC 시각·위경도 → 출력: 차트·변곡점 구간·궁합 점수). DB·HTTP·AI·Spring 을 모른다. 이유: 계산 정확도가 제품의 신뢰이고 기준값 테스트로 혼자 검증돼야 한다.
- `birth` — 출생지 목록과 시각 변환(`java.time` 의 시간대 이력), 그리고 저장된 출생 정보를 읽고 쓰는 한 곳(`BirthProfileService`). 저장된 출생 정보에서 차트 입력과 "같은 차트" 를 가리는 해시(`ChartInput`)를 얻는 곳도 여기다. 해석·리포트·궁합이 모두 소유자의 출생 정보를 쓰는데 기능 패키지는 서로 의존할 수 없어서 여기에 둔다.
- `web` — HTTP 공통: 오류 응답 모양(`ErrorResponse`·`BadRequestException`·`NotFoundException`), 출생 정보 요청 검증(`BirthRequestParser`), 요청 속도 제한, CORS, 지금의 `POST /charts` 컨트롤러. `auth` 를 모른다.
- `auth` — 세션 발급·검증, 세션 검사(`SessionInterceptor`: 로그인 전에 쓰는 경로 목록에 없으면 모두 세션 필요), 소유자를 얻는 한 곳(`CurrentOwner`), 개발용 로그인(`POST /auth/dev`, 결정 005), 계정 삭제(`DELETE /account`), 나중에 구글·애플·카카오 토큰 검증과 만 14세 판정.
- `profile` — 내 출생 정보 저장·조회의 HTTP 입구(`GET`·`PUT /profile`). 저장 자체는 `birth.BirthProfileService` 가 한다.
- `generation` — 글 생성 공통: 공급자를 가리는 인터페이스 `TextGenerator`(지시문·차트 글·JSON 구조를 받아 JSON 을 돌려준다)와 그 Claude 구현, 차트를 프롬프트 글로 옮기는 `chartFacts`, 근거에 쓰는 말(`Basis.kt`). 해석·리포트·궁합 글이 모두 쓰는데 기능 패키지는 서로 의존할 수 없어서 여기에 둔다. `chart` 만 안다(소유자·저장·HTTP 를 모른다).
- `reading` — 요약 해석 생성·저장. 요약 해석에만 있는 것(지시문, 돌려받을 구조, 문단·강점·보완점 개수 검증)을 갖고, 생성은 `generation.TextGenerator` 로 한다.
- `report` — 심층 리포트. 요청은 자리만 잡고 202 로 답하고, 글은 뒤에서 영역(성격·관계·일·감정)마다 따로 생성해 네 영역이 모두 검증을 통과하면 저장한다. 완성되면 `ReportDone` 이벤트를 낸다. 시기 영역은 같은 생성 안에서 뒤쪽 일꾼이 `chart` 로 마디·구간을 계산하고(평생 한 번에 약 0.3초) 표의 글을 붙인 뒤, 다가오는 줄에만 모델의 글을 받아 네 영역과 함께 저장한다. "지금 나의 구간" 은 저장하지 않고 읽을 때 오늘 날짜로 가린다(결정 008).
- `synastry` — 직접 입력, 초대, 수락, 나 기준 점수(`chart` 계산), 랭킹, 궁합 글·궁합 리포트.
- `direction` — 방향성 제시.
- `share` — 공유 조회(공유 카드 4종).
- `push` — 기기 토큰과 알림 발송.

## 디렉터리 구조
```
src/main/kotlin/com/byeolmyeong/api/
  chart/       Positions · Houses · Aspects · Points · Timeline(마디·구간) · SynastryScore   ← 순수 계산
  birth/       Places · BirthTime · BirthProfile(Service·Repository · ChartInput) · PlacesController
  web/         ErrorResponse · BirthRequest(Parser) · RateLimiter · CorsConfig · ChartController
  auth/        AuthController · AuthService · AccountRepository · SessionRepository · Session(CurrentOwner · SessionInterceptor)
  profile/     ProfileController
  generation/  TextGenerator(인터페이스) · ClaudeTextGenerator · ChartFacts · Basis
  reading/     ReadingController · ReadingService · ReadingRepository · ReadingDraft(지시문·구조·검증)
  report/      ReportController · ReportService(+ 일꾼 설정 ReportExecutorConfig) · ReportRepository · Report(모양·지시문·구조·검증) · ReportTiming(시기: 모양·모델에게 맡길 줄·연도 대조·지금) · TimingThemes(시기의 표의 글) · ReportDone(이벤트)
  synastry/    SynastryController · SynastryService · InviteService · SynastryRepository · InviteAccepted(이벤트)
  direction/   DirectionController · DirectionService · DirectionRepository
  share/       ShareController · ShareService
  push/        PushTokenController · PushSender(인터페이스) · 이벤트 리스너
src/main/resources/  application.properties · db/migration(Flyway) · prompts/(글 생성 프롬프트)
src/test/kotlin/...  기능별 테스트 · ArchitectureTest(의존 규칙)
```
- 기능 안의 계층은 파일 이름 끝(`Controller`·`Service`·`Repository`)으로 나눈다. 하위 디렉터리(controller/·service/)는 만들지 않는다.
- 컨트롤러는 요청 검증과 응답 변환만, 서비스는 소유자 검사와 흐름, 리포지토리는 저장만 한다. 계산은 `chart` 를 부른다.

## 의존 규칙 (ArchUnit 테스트 `ArchitectureTest.kt` 가 강제한다)
| 규칙 | 이유 |
|---|---|
| `chart` 는 Kotlin 표준·java.time·astronomy-engine 외에는 의존하지 않는다(Spring·DB·다른 패키지 금지) | 계산은 홀로 검증된다 |
| `birth` 는 `chart` 외 다른 기능 패키지에 의존하지 않는다 | 시각 변환도 순수하게 둔다 |
| 기능 패키지(`profile`·`reading`·`report`·`synastry`·`direction`·`share`·`push`)는 서로 직접 의존하지 않는다. 기능 사이 연결은 Spring 이벤트(`ReportDone`·`InviteAccepted`)로만 한다 | 기능 하나를 고칠 때 다른 기능이 같이 깨지지 않게 |
| 기능 패키지는 `chart`·`birth`·`auth`(소유자 얻기)·`web`(공통)·`generation`(글 생성 공통) 에만 의존할 수 있다 | 공통으로 쓰는 것만 아래로 둔다 |
| `generation` 은 `chart` 외 다른 패키지에 의존하지 않는다 | 글 생성 공통은 차트만 안다. 누구의 글인지·어디에 저장하는지는 기능이 맡는다 |
| `auth`·`web`·`generation` 은 기능 패키지에 의존하지 않는다 | 공통이 기능을 알면 순환이 생긴다 |
| `*Repository` 는 같은 패키지의 `*Service` 에서만 부른다(컨트롤러에서 부르지 않는다) | 소유자 검사를 서비스 한 곳에서 한다 |
| 패키지 사이 순환 의존 0 | |
| 글 생성(Claude API)·소셜 로그인 같은 외부 호출은 인터페이스 하나 뒤에만 둔다. Claude SDK 를 아는 클래스는 `generation.ClaudeTextGenerator` 하나다 | 공급자를 바꿀 수 있게 |

## 용어 (코드·문서·화면에서 같은 말을 쓴다)
| 화면 말 | 코드 이름 | 뜻 |
|---|---|---|
| 요약 해석 | `Reading` | 버튼 하나로 받는 짧은 해석 |
| 심층 리포트 | `Report` | 성격·관계·일·감정·시기 다섯 영역 |
| 마디 | `Milestone(kind = SATURN_SQUARE \| SATURN_RETURN \| URANUS_OPPOSITION \| SECOND_SATURN_RETURN)` | 누구에게나 오는 변곡점(토성 귀환 등) |
| 변화·기회·운명적 만남 구간 | `Interval(kind = CHANGE \| OPPORTUNITY \| FATED)` | 사람마다 다른 변곡점 기간 |
| 나 기준 점수 | `Synastry.score` | 상대 행성이 내 개인 행성에 만드는 각도로 계산한 40~100 |
| 랭킹 | `Ranking` | 나 기준으로 여러 명과 1:1 궁합을 점수순으로 |
| 방향성 제시 | `Direction` | 해 볼 만한 것 3개 |
| 공유 카드 | `ShareCard(kind = READING \| SCORE \| RANKING \| NEXT_TURN)` | 스토리용 카드 |

## API 계약 (앱 저장소와 맞춘다. 바꾸면 이 표를 같은 커밋에서 고친다)
| 메서드·경로 | 인증 | 요청 | 응답 |
|---|---|---|---|
| `GET /places` · `GET /places?q=` | 없음 | 없음 · 검색어 | `[{ id, name, lat, lon }]` |
| `POST /charts` | 세션 | `{ date, time \| null, placeId }` | `Chart` |
| `POST /auth/dev` | 없음 | 없음 | `{ token }` · 개발 설정에서만, 운영 404 (결정 005) |
| `POST /auth/login` | 없음 | `{ provider: GOOGLE \| APPLE \| KAKAO, idToken }` | `{ token }` · 만 14세 미만 403 `UNDER_AGE` · 브랜딩 뒤 구현 |
| `GET /profile` · `PUT /profile` | 세션 | `{ date, time \| null, placeId }` | 같은 형태 · 저장한 적 없으면 `GET` 은 404 `NO_PROFILE` · `PUT` 의 400 은 `POST /charts` 와 같다 |
| `DELETE /account` | 세션 | 없음 | 204 |
| `POST /readings` | 세션 | 없음(프로필의 출생 정보를 쓴다) | `Reading` · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 글을 만들지 못하면 502 `GENERATION_FAILED`(저장하지 않는다) · 처음 만들 때는 수십 초가 걸릴 수 있고, 같은 차트는 저장된 글을 바로 준다 |
| `POST /reports` | 세션 | 없음(프로필의 출생 정보를 쓴다) | 202 `{ status: WRITING \| DONE \| FAILED }` · 글을 기다리지 않고 바로 답한다 · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 아래 "리포트 상태" |
| `GET /reports/me` | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report? }` · 지금 프로필의 차트 기준 · `report` 는 `DONE` 일 때만(`sections` 와 `timing` 이 언제나 함께 온다) · 저장한 출생 정보가 없으면 `NONE` |
| `POST /synastry/direct` | 세션 | `{ name, relation: LOVE \| FRIEND \| WORK, date, time \| null, placeId }` | `Synastry` |
| `POST /synastry/invites` | 세션 | `{ name }` | `{ inviteId, expiresAt }` |
| `GET /invites/{inviteId}` | 없음 | 없음 | `{ inviterName, status }` |
| `POST /invites/{inviteId}/accept` | 세션 | `{ name }` | `Synastry` |
| `GET /synastry` · `GET /synastry/{id}` | 세션 | 없음 | 목록 · `Synastry` |
| `GET /synastry/ranking?relation=` | 세션 | 관계 유형 | `[{ id, name, score, keywords[3] }]` 점수순, 인원 제한 없음 |
| `POST /directions` | 세션 | `{ topic: FUTURE \| LOVE \| SYNASTRY, synastryId? }` | `Direction` |
| `POST /synastry/{id}/report` | 세션 | 없음 | 202 `{ status }` |
| `GET /shared/{shareId}` | 없음 | 없음 | 요약 해석·궁합 점수·랭킹·다음 변곡점 카드 중 하나(글과 숫자만) |
| `PUT /devices/push-token` | 세션 | `{ token }` | 204 |

- 세션: 요청 헤더 `Authorization: Bearer <token>`. 없거나 틀리면 401 `UNAUTHORIZED`. 표에서 인증이 "없음" 인 경로와 `GET /health` 말고는 모두 세션이 필요하다(새 경로는 기본이 세션 필요다). 토큰은 서버가 원문을 저장하지 않는다(해시만).
- 브라우저의 다른 출처 요청(CORS): 설정 `byeolmyeong.cors.allowed-origins`(환경 변수 `ALLOWED_ORIGINS`, 쉼표 구분)에 적힌 주소만 허용한다. 비어 있으면 허용하지 않는다. 허용 메서드 GET·POST·PUT·DELETE, 허용 헤더 `Authorization`·`Content-Type`. 쿠키는 쓰지 않는다.
- `Chart` = `{ timeKnown, houseSystem: "PLACIDUS", moonSignMayChange?, positions[{ body, sign, degree, minute, longitude, house?, retrograde }], points[{ point, sign, degree, minute, longitude, house? }], houses?[12 × { house, sign, degree, minute, longitude }], angles?{ asc{ sign, degree, minute, longitude }, mc{ sign, degree, minute, longitude } }, aspects[{ a, b, type, orb, applying }] }`
  - `longitude` 는 황경 0 이상 360 미만(소수 둘째 자리, 도·분처럼 올리지 않고 버린다). 앱이 휠을 그릴 때 쓴다. `moonSignMayChange: true` 는 시각을 모르고 그날 안에 달 별자리가 바뀔 때만 보낸다. 상승점·중천은 `angles` 에만 온다. `houses` 는 1번부터 순서대로. `houseSystem` 은 시각을 몰라도 보낸다. (2026-11-11 확정·구현)
  - `body`: `SUN`·`MOON`·`MERCURY`·`VENUS`·`MARS`·`JUPITER`·`SATURN`·`URANUS`·`NEPTUNE`·`PLUTO`. `point`: `NORTH_NODE`·`LILITH`·`FORTUNE`·`VERTEX`. `sign`: `ARIES`…`PISCES`. `type`: `CONJUNCTION`·`SEXTILE`·`SQUARE`·`TRINE`·`OPPOSITION`.
  - `degree`(0~29)·`minute`(0~59)는 별자리 안 도수이고 올리지 않고 버린다. `house` 는 1~12. `orb` 는 도(소수 둘째 자리). `aspects` 는 행성 10개 사이만.
  - 시각이 `null`(모름)이면 `timeKnown: false` 이고 `house`·`houses`·`angles` 키와 `FORTUNE`·`VERTEX` 점이 없다(값이 `null` 인 키를 보내지 않는다).
- `POST /charts` 요청: `date` 는 `YYYY-MM-DD`, `time` 은 `HH:mm` 또는 `null`, `placeId` 는 `GET /places` 의 `id`(정수).
- 오류 응답: `{ error }`. `POST /charts` 는 400 `INVALID_REQUEST`(본문 형식)·`INVALID_DATE`(형식, 1900-01-01 이전, 오늘 이후)·`INVALID_TIME`·`UNKNOWN_PLACE`. 요청 수 한도를 넘으면 429 `RATE_LIMITED`.
- `GET /places?q=`: 검색어를 공백으로 나눈 낱말이 이름에 모두 든 곳을 20개까지. `q` 가 없으면 전체 목록(229곳)을 준다. 앱의 출생지 고르기 화면이 쓴다. `name` 은 `시·도 시·군·구` 꼴이고 세종만 `세종특별자치시` 한 낱말이다.
- `Reading` = `{ headline, sections[{ title, body, basis[] }], strengths[], cautions[], shareId }`
- `Synastry` = `{ id, names[2], relation, score(40~100, 나 기준), headline, keywords[3], overlay(나의 차트와 상대 점의 화면 좌표용 황경), shareId, reportStatus }`.
- `Direction` = `{ topic, title, items[3] }` (조언형, 단정 금지).
- `Synastry.overlay` = `{ mine[{ body, longitude }], theirs?[{ body, longitude }], lines[{ a, b, type }] }`. 초대로 연결된 궁합에서는 `theirs` 를 내려보내지 않는다. 이유: 행성 위치로 생일을 역산할 수 있다.
- `GET /synastry` 목록 원소 = `{ id, names[2], relation, score, headline, createdAt }`. `GET /invites/{inviteId}` 의 `status` = `PENDING \| ACCEPTED \| EXPIRED`.
- `Report` = `{ sections[{ area: PERSONALITY \| RELATIONSHIP \| WORK \| EMOTION, title, paragraphs[{ title, body, basis[] }] }], timing }`. `sections` 는 정확히 이 네 영역이 이 순서로 오고, 영역마다 문단 4~6개다. `basis` 는 `Reading` 과 같은 말이다. `timing` 은 아래 "시기" 이고 `DONE` 인 리포트에는 언제나 있다(null 이 아니다). (2026-11-11 구현)
- 리포트 상태(`POST /reports` · `GET /reports/me`): `NONE` 지금 차트의 리포트를 만든 적이 없다(`GET` 만) · `WRITING` 쓰는 중 · `DONE` 완성 · `FAILED` 만들지 못했다(다시 `POST` 하면 새로 시작한다).
  - `POST`: 없거나 `FAILED` 면 생성을 시작하고 `WRITING`. 이미 `DONE` 이면 다시 만들지 않고 `DONE`. 쓰는 중이면 두 번째 생성을 시작하지 않고 `WRITING`. 뒤에서 일할 자리가 꽉 차 시작하지 못하면 `FAILED`(잠시 뒤 다시 `POST`).
  - `WRITING` 인 채 10분이 지나면 멈춘 것으로 본다(서버 재시작 등): `GET` 은 `FAILED` 로 답하고, `POST` 는 다시 시작한다.
  - 앱은 `POST` 뒤 `GET /reports/me` 를 몇 초 간격으로 다시 부른다. 실제 생성은 1분 안팎이다. 출생 정보를 고쳐 차트가 달라지면 `GET` 은 `NONE` 이 되고(예전 리포트는 남아 있어 되돌리면 다시 보인다) `POST` 로 새로 만든다.
- 시기 `Report.timing` = `{ timeKnown, milestones[4], intervals[], current }` (2026-11-11 구현, 결정 008). 규칙의 원본은 앱 저장소 release-1.0.0 F4.
  - 날짜는 모두 한국 달력의 `YYYY-MM-DD` 글자다(시각·시간대 없음). 글자를 그대로 비교해 거르고 정렬할 수 있다. 앱은 천체 계산을 하지 않는다: "앞뒤 10년 먼저" 는 `to >= 오늘-10년 && from <= 오늘+10년` 으로 거른다.
  - `timeKnown`(boolean): 출생 시각을 아는가. `false` 면 상승점·중천·하우스·버텍스가 대상인 구간이 계산되지 않아 `FATED` 구간이 0개이고 `MONEY` 영역이 나오지 않는다. 앱은 이 값으로 "출생 시각을 몰라 일부 구간을 뺐다" 는 안내를 띄운다. 안내 문구와 "시기는 행성의 주기를 계산한 것이고…" 문구는 앱이 갖는다.
  - `milestones` = 언제나 4개, 이 순서(시간 순서): `[{ kind: SATURN_SQUARE | SATURN_RETURN | URANUS_OPPOSITION | SECOND_SATURN_RETURN, date, year, age, from, to, size: BIG | SMALL, title, body }]`. `date` 는 처음 정확히 겹치는 날, `year`(정수)는 그 해, `age`(정수)는 그날의 만 나이. `from`~`to` 는 허용 오차 1° 안에 있는 기간(역행으로 여러 번 지나가면 처음 들어온 날부터 마지막으로 나간 날까지, 몇 주~열 달쯤)이고 `from <= date <= to`. `size` 는 토성 사각만 `SMALL`. null 인 필드는 없다.
  - `intervals` = `[{ id, kind: CHANGE | OPPORTUNITY | FATED, from, to, area: PEOPLE | WORK | MONEY | SELF, size?: BIG | SMALL, title, body }]`. `from` 순서(같으면 `to` 순서)로 온다. 태어난 뒤부터 만 80세 전까지 시작하는 구간 전부다(시각을 알면 약 190개, 모르면 약 100개). 서로 겹칠 수 있다. `id` 는 이 리포트 안에서만 겹치지 않는 글자(`i1`…)다. `size` 는 `CHANGE` 일 때만 있고 다른 종류에는 키가 없다. `from <= to`(하루짜리 구간은 같을 수 있다). 마지막 구간의 `to` 는 만 80세를 조금 넘을 수 있다.
    - `kind`: `CHANGE` 변화 구간(토성·천왕성·해왕성·명왕성 × 태양·달·상승점, 합·사분·충) · `OPPORTUNITY` 기회 구간(목성 × 태양·달·상승점·중천·금성 합·삼분, 목성의 5·7·11하우스 통과) · `FATED` 운명적 만남 구간(목성·토성·천왕성·해왕성·명왕성 × 버텍스 합, 시각을 알 때만).
    - `area`: `PEOPLE` 사람·인연 · `WORK` 일 · `MONEY` 돈 · `SELF` 나 자신. `FATED` 는 언제나 `PEOPLE`.
    - `size`: `BIG` 큰 변화(천왕성·해왕성·명왕성이 태양·달과 합·충) · `SMALL` 작은 변화(그 밖).
    - 무엇이 무엇에 닿아 생긴 구간인지(행성·각도)는 내려보내지 않는다.
  - `title`·`body`: "이 시기의 주제". 마디와 구간 모두 언제나 있다(빈 글 없음). 마디 4개와, 리포트를 만든 날에 걸쳐 있거나 그날부터 10년 안에 시작하는 구간(30개까지)은 모델이 쓴 글이고 나머지는 서버의 정해진 글이다. 앱은 둘을 구분하지 않는다. 지시문은 글에 연도·나이·점성술 용어·사건의 단정을 쓰지 못하게 하고, 서버가 걸러 내는 것은 그 줄의 기간 밖의 해다(서버의 정해진 글에는 숫자와 용어가 없다).
  - `current`(지금 나의 구간, 언제나 있다) = `{ date, intervals[], milestone?, nextInterval?, nextMilestone?, nearestMilestone }`. 저장하지 않고 `GET` 할 때마다 그날(한국 날짜)로 만든다. `date` 는 서버의 오늘. `intervals` 는 오늘에 걸쳐 있는 구간(`from <= date <= to`, 위 `intervals` 의 원소와 같은 모양·같은 `id`, 없으면 빈 배열)이고 "언제까지" 는 각 구간의 `to` 다. `milestone` 은 오늘이 그 `from`~`to` 안인 마디(없으면 키 없음). `nextInterval` 은 오늘 뒤에 처음 시작하는 구간, `nextMilestone` 은 `date` 가 오늘 뒤인 첫 마디(둘 다 없으면 키 없음 — 두 번째 토성 귀환 뒤에는 `nextMilestone` 이 없다). `nearestMilestone` 은 오늘에서 `date` 가 가장 가까운 마디의 `kind`(지났든 아니든, 언제나 있다 — 화면에서 강조할 마디).
  - 이 변경 전에 만든 리포트(`timing` 없음)는 만든 적 없는 것으로 본다: `GET` 은 `NONE`, `POST` 는 새로 만들어 덮어쓴다.
- `GET /shared/{shareId}` = `{ kind: READING \| SCORE \| RANKING \| NEXT_TURN, title, lines[], score?, items?[{ name, score }] }` (글과 숫자만, 출생 정보·근거 없음).

## 데이터
- `accounts`(공급자, 공급자 사용자 식별자 unique) · `sessions`(토큰 해시) · `profiles`(출생 정보) · `readings`(소유자, 종류, 차트 해시, 본문, 공유 식별자) · `reports`(소유자·차트 해시 unique, 상태 `WRITING`·`DONE`·`FAILED`, 본문은 `DONE` 일 때만 — 네 영역의 글과 시기(`timing`: 마디·구간의 날짜와 글, "지금" 은 없음), 생성을 시작한 때·상태가 바뀐 때) · `synastries`(두 참여자 또는 한 참여자 + 직접 입력, 관계 유형, 나 기준 점수, 부를 이름, 키워드, 본문, 공유 식별자) · `invites`(초대 식별자, 만료, 상태) · `directions`(소유자, 주제, 대상, 본문) · `push_tokens`.
- 직접 입력한 궁합 상대의 출생 정보는 저장하지 않는다. 계산한 뒤 버리고 글만 남긴다.
- 랭킹은 `synastries` 의 점수로 만든다. 따로 저장하지 않는다.

## 글 생성 규칙
- 프롬프트는 리소스 파일로 둔다. 본문에 점성술 용어를 쓰지 않고, 문단마다 근거(차트 요소)를 별도 필드로 받는다. 건강·투자·미래 사건을 단정하지 않는다.
- 근거 대조: AI 가 돌려준 `basis` 의 차트 요소가 서버가 계산한 차트에 없으면 그 응답을 버리고 다시 생성한다(상한 2회). 이유: 없는 요소를 근거로 든 글은 신뢰를 깬다.
- 같은 소유자·차트·종류의 글은 한 번만 생성한다. 실패·빈 응답·구조가 틀린 응답은 저장하지 않는다.
- 생성 호출부는 인터페이스 하나(`generation.TextGenerator`) 뒤에 둔다. 이유: 공급자(지금은 Claude, 결정 006)와 모델을 바꿀 수 있어야 한다. 결정 006 이 말하는 `reading.ReadingGenerator`·`ClaudeReadingGenerator` 가 리포트도 쓰게 되면서 이 이름으로 옮겨졌다.
- 프롬프트에는 차트 요소(천체·별자리·하우스·각도 관계)만 넣는다. 출생 날짜·시각·장소와 도수는 넣지 않는다. 시각을 모르면 하우스·상승점·중천·행운점·버텍스를 넣지 않고, 글에서도 쓰지 않게 한다.
- 요약 해석의 모양: 시각을 알면 문단 5개(중심 → 마음 → 첫인상과 지향 → 힘이 몰린 곳 → 과제), 모르면 "첫인상과 지향"이 빠져 4개. 강점 3개, 보완점 3개. 개수가 다르거나 빈 글이 있으면 버린다.
- 지시문은 공통 글쓰기 규칙(`prompts/writing-rules.md`: 말투·용어 금지·단정 금지·근거) 뒤에 글마다의 지시문을 붙여 만든다. 요약 해석: `reading-summary*.md`. 리포트: `report.md` + 영역별 `report-<영역>.md` + 시각을 모르면 `report-time-unknown.md`. 시기: `report-timing.md`.
- 리포트의 모양과 생성: 영역(성격·관계·일·감정)마다 모델을 따로 부른다(네 호출을 한꺼번에). 영역마다 제목 하나와 문단 4~6개, 문단마다 근거. 틀린 영역(빈 글·문단 수·근거 없는 문단·차트에 없는 근거)은 그 영역만 다시 생성한다(영역마다 다시 2회까지). 한 영역이라도 끝내 못 만들거나 호출이 실패하면 리포트 전체가 `FAILED` 이고 아무것도 저장하지 않는다. 관계 영역은 "나는 사람을 어떻게 대하는 사람인가"(친구·가족·연인 전반)이고 연애 조언이 아니다.
- 시기의 글(결정 008): 지시문은 공통 규칙 + `report-timing.md`(+ 시각을 모르면 `report-time-unknown.md`). 차트 글 아래에 "[시기 목록]"(줄마다 id · 시작 해~끝 해 · 종류 · 무엇이 무엇에 닿는지 · 영역)을 주고, 줄마다 `{ id, fromYear, toYear, title, body }` 를 받는다. 모델은 해를 계산하지 않고 옮겨 적기만 한다. 줄에는 해만 넣는다(달·날·나이·도수 없음). 옮겨 적은 해가 계산값과 다르거나 글 안에 그 줄의 기간 밖의 해가 나오면(`YEAR_MISMATCH`), 줄이 빠지거나 더해지거나 글이 비면(`MALFORMED`) 그 응답을 버리고 다시 생성한다(2회까지). 끝내 못 받으면 리포트 전체가 `FAILED` 다. `basis` 는 없다.
- 리포트의 글은 요청 스레드가 아니라 정해진 수의 일꾼(`byeolmyeong.report.worker-threads`·`queue-capacity`)이 쓴다. 일 하나는 한 영역의 글 또는 시기(타임라인 계산 + 글)다. 로그에는 이벤트 이름·영역·사유 코드·시도 횟수·토큰 수만 남긴다.
- 근거에 쓸 수 있는 말(`generation/Basis.kt` 가 단일 출처, 글자까지 같아야 한다): 천체 이름(태양·달·수성·금성·화성·목성·토성·천왕성·해왕성·명왕성), 지점 이름(북쪽 교점·릴리스, 시각을 알면 행운점·버텍스), 시각을 알면 `상승점`·`중천`, 그 차트에서 천체·지점·상승점·중천이 하나라도 놓인 별자리(양·황소·…·물고기, "자리"를 붙이지 않는다), 천체가 하나라도 놓인 하우스(`N하우스`, 시각을 알 때만).

## 요청 속도 제한 (남용 방지, 서버에서만 판정한다)
- 리포트·궁합·방향성 제시에 기능 횟수 제한은 없다(앱 결정 011). 같은 차트·같은 두 사람의 글은 한 번만 만든다.
- 계정·주소(IP)별 분당 요청 수를 제한한다. 한도는 `application.properties` 의 `byeolmyeong.rate-limit.*`(분당, 서버 메모리).
- 광고 시청 판정은 1.1.0 에 더한다(앱 결정 015).

## 외부 시스템
| 시스템 | 용도 | 상태 |
|---|---|---|
| PostgreSQL | 저장 | 로컬은 컨테이너. 운영 호스팅은 [확인 필요] |
| Claude API | 글 생성 | 키는 환경 변수 `ANTHROPIC_API_KEY`. 모델은 설정 `byeolmyeong.claude.model`(결정 006) |
| 구글·애플·카카오 로그인 | 토큰 검증 | [확인 필요] 클라이언트 등록, 만 14세 확인 방법(생년월일 / 카카오 연령대) |
| 푸시 발송 | 초대 수락·리포트 완성 알림 | [확인 필요] 발송 경로는 구현 시 Expo 공식 문서로 확정 |

## 관련
- 결정: docs/decisions/
- 계획: docs/plans/2026-11-10-api-mvp.md
