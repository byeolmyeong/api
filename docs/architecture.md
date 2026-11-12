---
type: architecture
tags: [서버 아키텍처, API 계약, 관리자 API, 차트 계산, 해석 생성, 로그인, 의존 규칙, ArchUnit, 용어]
status: active
last_reviewed: 2026-11-12
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
- `web` — HTTP 공통: 오류 응답 모양(`ErrorResponse`·`BadRequestException`·`NotFoundException`), 출생 정보 요청 검증(`BirthRequestParser`), 요청 속도 제한, CORS(앱의 주소와 관리 화면의 주소를 경로로 나눈다), 지금의 `POST /charts` 컨트롤러. `auth` 를 모른다.
- `auth` — 세션 발급·검증, 세션 검사(`SessionInterceptor`: 로그인 전에 쓰는 경로 목록에 없으면 모두 세션 필요. `GET /faqs` 는 GET 만 열고, `/admin/**` 는 보지 않는다 — 관리자 세션 검사가 맡는다), 소유자를 얻는 한 곳(`CurrentOwner`), 개발용 로그인(`POST /auth/dev`, 결정 005), 계정 삭제(`DELETE /account`), 나중에 구글·애플·카카오 토큰 검증과 만 14세 판정.
- `profile` — 내 출생 정보 저장·조회의 HTTP 입구(`GET`·`PUT /profile`). 저장 자체는 `birth.BirthProfileService` 가 한다.
- `generation` — 글 생성 공통: 공급자를 가리는 인터페이스 `TextGenerator`(지시문·차트 글·JSON 구조를 받아 JSON 을 돌려준다)와 그 Claude 구현, 차트를 프롬프트 글로 옮기는 `chartFacts`, 근거에 쓰는 말(`Basis.kt`). 해석·리포트·궁합 글이 모두 쓰는데 기능 패키지는 서로 의존할 수 없어서 여기에 둔다. `chart` 만 안다(소유자·저장·HTTP 를 모른다).
- `reading` — 요약 해석 생성·저장. 요약 해석에만 있는 것(지시문, 돌려받을 구조, 문단·강점·보완점 개수 검증)을 갖고, 생성은 `generation.TextGenerator` 로 한다.
- `report` — 심층 리포트. 요청은 자리만 잡고 202 로 답하고, 글은 뒤에서 영역(성격·관계·일·감정)마다 따로 생성해 네 영역이 모두 검증을 통과하면 저장한다. 완성되면 `ReportDone` 이벤트를 낸다. 시기 영역은 같은 생성 안에서 뒤쪽 일꾼이 `chart` 로 마디·구간을 계산하고(평생 한 번에 약 0.3초) 표의 글을 붙인 뒤, 다가오는 줄에만 모델의 글을 받아 네 영역과 함께 저장한다. "지금 나의 구간" 은 저장하지 않고 읽을 때 오늘 날짜로 가린다(결정 008).
- `synastry` — 직접 입력 궁합: 나 기준 점수(`chart` 계산), 키워드·한 줄 요약 생성, 목록·랭킹, 궁합 리포트(202 로 답하고 뒤에서 영역마다 쓴다). 상대의 출생 정보·행성 위치는 요청 안에서만 쓰고 저장하지 않는다(결정 010). 초대·수락은 아직 없다(태스크 9 의 나머지).
- `direction` — 방향성 제시(해 볼 만한 것 3개). 지금은 이성 주제 하나다. 방향성 제시에만 있는 것(지시문, 돌려받을 구조, 항목 개수·근거·조언형 검증)을 갖고, 생성은 `generation.TextGenerator` 로 한다. 미래·궁합 주제는 리포트 시기·궁합이 들어온 뒤에 더한다. 결정: docs/decisions/009-direction-storage-and-advice-check.md
- `inquiry` — 문의하기. 설정의 문의 글을 검증해 저장만 한다(관리 화면·메일 발송 없음). 계정별 시간당 제한을 여기서 건다. 결정: docs/decisions/013-inquiries-stored-on-server.md
- `admin` — 관리자 API(`/admin/**`). 앱 사용자와 완전히 따로인 관리자 계정·로그인·세션(`AdminSessionInterceptor`·`CurrentAdmin`), 첫 관리자 만들기, 관리자 관리, 문의 관리(상태·메모·처리한 관리자), 현황 수치(개수만), FAQ 편집. 다른 기능의 테이블은 자기 SQL 로 읽는다. 다른 기능 패키지를 모른다(`auth` 도 쓰지 않는다). 결정: docs/decisions/014-admin-back-office.md
- `faq` — 자주 묻는 질문의 공개 조회(`GET /faqs`, 세션 없음). `faqs` 테이블을 읽기만 한다. 글을 쓰는 것은 `admin` 이고 두 패키지는 코드로 서로를 모른다(결정 014).
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
  synastry/    SynastryController · SynastryService · SynastryRepository · Synastry(모양·두 차트 사이 연결의 글·지시문·구조·검증) · SynastryReport(리포트의 모양·지시문·구조·검증) · (초대 때) InviteService · InviteAccepted(이벤트)
  direction/   DirectionController · DirectionService · DirectionRepository · Direction(모양·지시문·구조·검증)
  inquiry/     InquiryController · InquiryService(계정별 제한) · InquiryRepository · Inquiry(모양·검증)
  admin/       Admin(로그인한 관리자·거절·본문 읽기) · AdminSession(CurrentAdmin · AdminSessionInterceptor) · AdminPasswords(bcrypt) · AdminAuthController · AdminAuthService(+ AdminSessionRepository) · AdminAccountController(+ 첫 관리자 AdminBootstrap) · AdminAccountService(+ AdminAccountRepository) · AdminInquiryController · AdminInquiryService(+ AdminInquiryRepository — `inquiries` 를 자기 SQL 로 읽고 처리 칸만 쓴다) · AdminStats(Controller · Service · Repository — 다른 기능의 테이블을 개수만 읽는다) · AdminFaqController · AdminFaqService(+ AdminFaqRepository)
  faq/         FaqController(+ FaqService · FaqRepository — 공개한 것만 읽는다)
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
| 기능 패키지(`profile`·`reading`·`report`·`synastry`·`direction`·`inquiry`·`admin`·`faq`·`share`·`push`)는 서로 직접 의존하지 않는다. 기능 사이 연결은 Spring 이벤트(`ReportDone`·`InviteAccepted`)로만 한다 | 기능 하나를 고칠 때 다른 기능이 같이 깨지지 않게 |
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
| `GET /faqs` | 없음 | 없음 | `[{ id, question, answer }]` 공개한 것만 순서대로 · 하나도 없으면 `[]` · 아래 "자주 묻는 질문" |
| `POST /charts` | 세션 | `{ date, time \| null, placeId \| null }` · `placeId` 는 `time` 이 있을 때만 필수 | `Chart` |
| `POST /auth/dev` | 없음 | 없음 | `{ token }` · 개발 설정에서만, 운영 404 (결정 005) |
| `POST /auth/login` | 없음 | `{ provider: GOOGLE \| APPLE \| KAKAO, idToken }` | `{ token }` · 만 14세 미만 403 `UNDER_AGE` · 브랜딩 뒤 구현 |
| `GET /profile` · `PUT /profile` | 세션 | `{ date, time \| null, placeId \| null }` · `placeId` 는 `time` 이 있을 때만 필수 | 같은 형태 · `time` 이 `null` 이면 `placeId` 도 `null` · 저장한 적 없으면 `GET` 은 404 `NO_PROFILE` · `PUT` 의 400 은 `POST /charts` 와 같다 |
| `DELETE /account` | 세션 | 없음 | 204 |
| `POST /readings` | 세션 | 없음(프로필의 출생 정보를 쓴다) | `Reading` · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 글을 만들지 못하면 502 `GENERATION_FAILED`(저장하지 않는다) · 처음 만들 때는 수십 초가 걸릴 수 있고, 같은 차트는 저장된 글을 바로 준다 |
| `POST /reports` | 세션 | 없음(프로필의 출생 정보를 쓴다) | 202 `{ status: WRITING \| DONE \| FAILED }` · 글을 기다리지 않고 바로 답한다 · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 아래 "리포트 상태" |
| `GET /reports/me` | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report? }` · 지금 프로필의 차트 기준 · `report` 는 `DONE` 일 때만(`sections` 와 `timing` 이 언제나 함께 온다) · 저장한 출생 정보가 없으면 `NONE` |
| `POST /synastry/direct` | 세션 | `{ name, relation: LOVE \| FRIEND \| WORK \| FAMILY, date, time \| null, placeId? }` (상대의 출생 정보) | 200 `Synastry` + `overlay` · 아래 "궁합(직접 입력)" |
| `POST /synastry/invites` | 세션 | `{ name }` | `{ inviteId, expiresAt }` |
| `GET /invites/{inviteId}` | 없음 | 없음 | `{ inviterName, status }` |
| `POST /invites/{inviteId}/accept` | 세션 | `{ name }` | `Synastry` |
| `GET /synastry` · `GET /synastry/{id}` | 세션 | 없음 | `[Synastry]` 새것부터 · `Synastry` · 둘 다 `overlay` 없음 · 남의 것·없는 것 404 `NOT_FOUND` |
| `GET /synastry/ranking?relation=` | 세션 | 관계 유형(필수) | `[{ id, name, score, scoreLabel?, keywords[3] }]` 점수가 높은 순(같으면 먼저 만든 것), 인원 제한 없음 · 유형이 없거나 틀리면 400 `INVALID_RELATION` |
| `POST /directions` | 세션 | `{ topic: LOVE }` (프로필의 출생 정보를 쓴다. `FUTURE`·`SYNASTRY` 와 `synastryId` 는 아직 받지 않는다) | 200 `Direction` · `topic` 이 없거나 `LOVE` 가 아니면 400 `INVALID_TOPIC` · 본문이 JSON 이 아니면 400 `INVALID_REQUEST` · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 글을 만들지 못하면 502 `GENERATION_FAILED`(저장하지 않는다) · 처음 만들 때는 수십 초가 걸릴 수 있고, 같은 차트는 저장된 글을 바로 준다 |
| `POST /synastry/{id}/report` | 세션 | `{ date, time \| null, placeId? }` (상대의 출생 정보를 다시 보낸다) | 202 `{ status: WRITING \| DONE \| FAILED }` · 아래 "궁합 리포트" |
| `GET /synastry/{id}/report` | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report? }` · `report` 는 `DONE` 일 때만 · 남의 것·없는 것 404 `NOT_FOUND` |
| `POST /inquiries` | 세션 | `{ category: ACCOUNT \| READING \| SYNASTRY \| BUG \| OTHER, body, replyEmail? \| null }` | 201 `{ id }` · 아래 "문의하기" |
| `GET /shared/{shareId}` | 없음 | 없음 | 요약 해석·궁합 점수·랭킹·다음 변곡점 카드 중 하나(글과 숫자만) |
| `PUT /devices/push-token` | 세션 | `{ token }` | 204 |

- 세션: 요청 헤더 `Authorization: Bearer <token>`. 없거나 틀리면 401 `UNAUTHORIZED`. 표에서 인증이 "없음" 인 경로와 `GET /health` 말고는 모두 세션이 필요하다(새 경로는 기본이 세션 필요다). 토큰은 서버가 원문을 저장하지 않는다(해시만). `/admin/**` 는 이 표에 없다 — 아래 "관리자 API" 가 따로 다루고, 사용자 세션 토큰은 거기서 통하지 않는다(관리자 토큰도 이 표의 경로에서 통하지 않는다).
- 브라우저의 다른 출처 요청(CORS): 설정 `byeolmyeong.cors.allowed-origins`(환경 변수 `ALLOWED_ORIGINS`, 쉼표 구분)에 적힌 주소만 허용한다. 비어 있으면 허용하지 않는다. 허용 메서드 GET·POST·PUT·DELETE, 허용 헤더 `Authorization`·`Content-Type`. 쿠키는 쓰지 않는다. `/admin/**` 는 이 주소들에 열리지 않는다 — 관리 화면의 주소(`ADMIN_ALLOWED_ORIGINS`)만 부를 수 있고, 그 주소는 이 표의 경로를 부를 수 없다("관리자 API" 의 "브라우저 출처").
- `Chart` = `{ timeKnown, houseSystem: "PLACIDUS", moonSignMayChange?, positions[{ body, sign, degree, minute, longitude, house?, retrograde }], points[{ point, sign, degree, minute, longitude, house? }], houses?[12 × { house, sign, degree, minute, longitude }], angles?{ asc{ sign, degree, minute, longitude }, mc{ sign, degree, minute, longitude } }, aspects[{ a, b, type, orb, applying }] }`
  - `longitude` 는 황경 0 이상 360 미만(소수 둘째 자리, 도·분처럼 올리지 않고 버린다). 앱이 휠을 그릴 때 쓴다. `moonSignMayChange: true` 는 시각을 모르고 그날 안에 달 별자리가 바뀔 때만 보낸다. 상승점·중천은 `angles` 에만 온다. `houses` 는 1번부터 순서대로. `houseSystem` 은 시각을 몰라도 보낸다. (2026-11-11 확정·구현)
  - `body`: `SUN`·`MOON`·`MERCURY`·`VENUS`·`MARS`·`JUPITER`·`SATURN`·`URANUS`·`NEPTUNE`·`PLUTO`. `point`: `NORTH_NODE`·`LILITH`·`FORTUNE`·`VERTEX`. `sign`: `ARIES`…`PISCES`. `type`: `CONJUNCTION`·`SEXTILE`·`SQUARE`·`TRINE`·`OPPOSITION`.
  - `degree`(0~29)·`minute`(0~59)는 별자리 안 도수이고 올리지 않고 버린다. `house` 는 1~12. `orb` 는 도(소수 둘째 자리). `aspects` 는 행성 10개 사이만.
  - 시각이 `null`(모름)이면 `timeKnown: false` 이고 `house`·`houses`·`angles` 키와 `FORTUNE`·`VERTEX` 점이 없다(값이 `null` 인 키를 보내지 않는다).
- `POST /charts`·`PUT /profile` 요청: `date` 는 `YYYY-MM-DD`, `time` 은 `HH:mm` 또는 `null`, `placeId` 는 `GET /places` 의 `id`(정수).
- 태어난 시각을 모르면 태어난 곳을 받지 않는다(결정 007): `time` 이 `null`(또는 키 없음)이면 `placeId` 는 없어도 되고, 보내도 읽지 않으며(목록에 없는 id 여도 400 이 아니다) 저장하지 않는다. 그때 `GET`·`PUT /profile` 의 응답은 `placeId: null` 이다. `time` 이 있으면 `placeId` 는 필수이고 없거나 `null` 이거나 목록에 없으면 400 `UNKNOWN_PLACE` 다. 시각을 모르는 날의 기준 시각은 한국 표준시 정오다.
- 오류 응답: `{ error }`. `POST /charts` 는 400 `INVALID_REQUEST`(본문 형식)·`INVALID_DATE`(형식, 1900-01-01 이전, 오늘 이후)·`INVALID_TIME`·`UNKNOWN_PLACE`. 요청 수 한도를 넘으면 429 `RATE_LIMITED`.
- `GET /places?q=`: 검색어를 공백으로 나눈 낱말이 이름에 모두 든 곳을 20개까지. `q` 가 없으면 전체 목록(229곳)을 준다. 앱의 출생지 고르기 화면이 쓴다. `name` 은 `시·도 시·군·구` 꼴이고 세종만 `세종특별자치시` 한 낱말이다.
- `Reading` = `{ headline, sections[{ title, body, basis[] }], strengths[], cautions[], shareId }`
- 궁합(직접 입력) (2026-11-11 구현, 결정 010. 초대로 연결된 궁합은 아직 없다)
  - `Synastry` = `{ id, name, relation: LOVE | FRIEND | WORK | FAMILY, score, scoreLabel?, headline, keywords[3], reportStatus: NONE | WRITING | FAILED | DONE, createdAt, overlay? }`. `id` 는 UUID, `name` 은 상대를 부를 이름, `score` 는 나 기준 40~100 의 정수(서버 계산), `headline` 은 한 줄 요약, `createdAt` 은 UTC `YYYY-MM-DDTHH:mm:ssZ`. 값이 없는 키는 보내지 않는다(`null` 로 보내지 않는다).
  - `scoreLabel`: 점수가 60 미만이면 `"다른 결의 사이"`, 60 이상이면 키가 없다. 앱은 이 키가 있으면 점수 옆에 그대로 붙인다(60 을 앱에서 다시 판정하지 않는다). 그때 키워드와 한 줄 요약은 끌리는 점부터 쓰여 있다.
  - `overlay`(겹친 휠) = `{ mine[{ body, longitude }], mineAsc?, theirs[{ body, longitude }], lines[{ mine, theirs, type }] }`. **`POST /synastry/direct` 의 응답에만 있다.** 상대의 행성 위치라 서버가 저장하지 않으므로 `GET` 에는 없다. `mine`·`theirs` 는 행성 10개(`body` 순서는 `Chart` 와 같다), `longitude` 는 `Chart` 와 같은 황경(소수 둘째 자리, 버림). `mineAsc` 는 내 상승점의 황경이고 내 태어난 시각을 알 때만 있다. `lines` 는 점수에 쓴 각도다: `mine` 은 `SUN`·`MOON`·`MERCURY`·`VENUS`·`MARS`·`ASC`(내 쪽), `theirs` 는 `body`(상대 쪽), `type` 은 `Chart` 의 각도 종류.
  - `POST /synastry/direct` 요청: `name` 은 앞뒤 공백을 뗀 1~20자, `date`·`time`·`placeId` 는 **상대의** 출생 정보이고 형식은 `POST /charts` 와 같다. `placeId` 는 `time` 이 있을 때만 필수이고 `time` 이 `null` 이면 없어도 된다(와도 읽지 않는다, 앱 결정 020). 나는 저장된 프로필이다. 같은 상대를 다시 보내면 새 궁합이 하나 더 생긴다(서버는 같은 사람인지 알 수 없다).
  - `POST /synastry/direct` 오류: 400 `INVALID_REQUEST`(본문 형식)·`INVALID_NAME`·`INVALID_RELATION`·`INVALID_DATE`·`INVALID_TIME`·`UNKNOWN_PLACE`(시각이 있는데 곳이 없거나 목록에 없다) · 404 `NO_PROFILE`(내 출생 정보를 저장한 적 없다) · 429 `RATE_LIMITED` · 502 `GENERATION_FAILED`(글을 만들지 못했다. 아무것도 저장하지 않는다. 다시 보내면 된다). 글을 받을 때까지 기다렸다 답하므로 수십 초가 걸릴 수 있다.
- `Direction` = `{ topic: LOVE, title, items[3 × { title, body, basis[] }] }`. `title` 은 세 항목을 아우르는 한 줄 제목. `items` 는 정확히 3개이고, 항목의 `title` 은 "보기"로 끝나는 실천 한 줄, `body` 는 2~3문장, `basis` 는 `Reading` 과 같은 말(1개 이상)이다. 공유 식별자는 없다. 지금 프로필의 차트 기준이라 출생 정보를 고치면 새 글이 만들어진다(예전 글은 남아 있어 되돌리면 다시 나온다). 다시 생성하는 방법은 없다. (2026-11-11 `LOVE` 구현, `FUTURE`·`SYNASTRY` 는 남음)
- 궁합 리포트 (2026-11-11 구현, 결정 010)
  - `SynastryReport` = `{ sections[{ area: ATTRACTION | TALK | CONFLICT | LASTING, title, paragraphs[{ title, body, basis[] }] }] }`. `sections` 는 정확히 이 네 영역(끌림·대화·갈등·오래가기)이 이 순서로 오고, 영역마다 문단 3~4개다. `basis` 는 내 쪽 요소의 말뿐이다: `태양`·`달`·`수성`·`금성`·`화성`, 내 시각을 알면 `상승점`(문단마다 1개 이상).
  - `POST /synastry/{id}/report`: 서버에 상대의 출생 정보가 없으므로 본문으로 다시 받는다(형식·`placeId` 규칙은 `POST /synastry/direct` 와 같다). 계산 직후에는 앱이 입력 화면의 값을 그대로 보내고, 나중에 처음 요청할 때는 다시 입력받는다. 글을 기다리지 않고 202 로 답하고, 앱은 `GET /synastry/{id}/report` 를 몇 초 간격으로 다시 부른다(`Synastry.reportStatus` 도 같은 값이다).
  - 상태: `NONE` 요청한 적 없다(`GET` 만) · `WRITING` 쓰는 중 · `DONE` 완성 · `FAILED` 만들지 못했다(다시 `POST` 하면 새로 시작한다). 없거나 `FAILED` 면 생성을 시작하고 `WRITING`. 이미 `DONE`·`WRITING` 이면 다시 만들지 않고 그 상태를 준다(그때는 본문을 읽지 않으므로 없어도 된다). 뒤에서 일할 자리가 꽉 차면 `FAILED`. `WRITING` 인데 살아 있다는 표시(`active_at`, 서버가 15초마다 적는다)가 2분 넘게 끊겼으면 `FAILED` 로 보이고 `POST` 는 다시 시작한다. 줄에서 오래 기다리는 것만으로는 `FAILED` 가 되지 않는다(심층 리포트와 같다 — 아래 "리포트 상태"). 궁합(`id`)마다 리포트는 하나다.
  - `POST` 오류: 400 `INVALID_REQUEST`(본문이 없거나 형식이 틀림)·`INVALID_DATE`·`INVALID_TIME`·`UNKNOWN_PLACE`·`BIRTH_MISMATCH`(보낸 출생 정보로 계산한 점수가 그 궁합의 점수와 다르다 — 다른 사람의 정보다) · 404 `NOT_FOUND`(남의 것·없는 것)·`NO_PROFILE` · 409 `PROFILE_CHANGED`(내 출생 정보가 그 궁합을 계산한 때와 다르다. 궁합을 새로 계산해야 한다).
- 초대(아직 구현 전): 초대로 연결된 궁합에서는 `overlay.theirs` 를 내려보내지 않는다. 이유: 행성 위치로 생일을 역산할 수 있다. `GET /invites/{inviteId}` 의 `status` = `PENDING \| ACCEPTED \| EXPIRED`. 두 사람의 이름(`names[2]`)·`shareId` 를 `Synastry` 에 더할지는 초대·공유(태스크 9 의 나머지·10)에서 정한다.
- `Report` = `{ sections[{ area: PERSONALITY \| RELATIONSHIP \| WORK \| EMOTION, title, paragraphs[{ title, body, basis[] }] }], timing }`. `sections` 는 정확히 이 네 영역이 이 순서로 오고, 영역마다 문단 4~6개다. `basis` 는 `Reading` 과 같은 말이다. `timing` 은 아래 "시기" 이고 `DONE` 인 리포트에는 언제나 있다(null 이 아니다). (2026-11-11 구현)
- 리포트 상태(`POST /reports` · `GET /reports/me`): `NONE` 지금 차트의 리포트를 만든 적이 없다(`GET` 만) · `WRITING` 쓰는 중 · `DONE` 완성 · `FAILED` 만들지 못했다(다시 `POST` 하면 새로 시작한다).
  - `POST`: 없거나 `FAILED` 면 생성을 시작하고 `WRITING`. 이미 `DONE` 이면 다시 만들지 않고 `DONE`. 쓰는 중이면 두 번째 생성을 시작하지 않고 `WRITING`. 뒤에서 일할 자리가 꽉 차 시작하지 못하면 `FAILED`(잠시 뒤 다시 `POST`).
  - 멈춘 생성(쓰는 도중 서버가 꺼진 경우): 생성을 맡은 서버는 살아 있는 동안 그 행에 살아 있다는 표시(`active_at`)를 15초마다 다시 적는다(줄에서 기다리는 것도). `WRITING` 인데 이 표시가 2분 넘게 끊겼으면 멈춘 것으로 본다: `GET` 은 `FAILED` 로 답하고, `POST` 는 다시 시작한다. 자리를 잡은 뒤 흐른 시간으로는 가리지 않는다 — 요청이 몰려 줄에서 오래 기다리는 리포트는 `WRITING` 그대로다.
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
- 문의하기 `POST /inquiries` (2026-11-12 구현, 결정 013). 앱 사용자가 읽는 경로(`GET`·목록)는 없다. 관리자는 "관리자 API" 의 `/admin/inquiries` 로 읽고 처리한다(결정 014).
  - 요청: `category` 는 `ACCOUNT`(계정)·`READING`(해석)·`SYNASTRY`(궁합)·`BUG`(오류)·`OTHER`(기타) 중 하나. `body` 는 앞뒤 공백을 뗀 10~2000자(글자 수는 유니코드 코드 포인트로 센다: 한글 한 글자가 1, 이모지 한 개도 1 — JavaScript 의 `.length` 가 아니라 `[...body].length`). 가운데의 줄바꿈은 그대로 둔다. `replyEmail` 은 답장 받을 이메일이고 없어도 된다: 키가 없거나 `null` 이거나 빈 글·공백뿐인 글이면 "없음" 이다. 있으면 앞뒤 공백을 뗀 254자 이하이고 모양만 본다(`@` 가 정확히 하나, 그 앞이 비지 않음, 그 뒤에 `.` 이 있음, 공백 없음). 실제로 있는 주소인지는 확인하지 않는다. 그 밖의 키는 읽지 않는다.
  - 응답: 201 `{ id }`(UUID). 서버는 저장만 한다(답장·알림 없음).
  - 오류: 400 `INVALID_REQUEST`(본문이 JSON 객체가 아니거나, `category`·`body`·`replyEmail` 이 글자가 아닌 타입이다 — 숫자·배열·객체·불리언)·`INVALID_CATEGORY`(없거나 `null` 이거나 목록에 없다)·`INVALID_BODY`(없거나 `null` 이거나 길이가 틀리다)·`INVALID_EMAIL` · 401 `UNAUTHORIZED` · 429 `RATE_LIMITED`. 여러 개가 틀리면 이 순서로 처음 걸린 것 하나를 준다. 오류 응답에 입력값은 없다.
  - 429: 한 계정이 한 시간(서버 시계의 정시부터 정시까지)에 5건까지 저장할 수 있고 여섯 번째부터 429 다. 검증에서 걸린 요청(400)은 세지 않는다. 같은 계정의 다른 기기도 같이 센다.
- 자주 묻는 질문 `GET /faqs` (2026-11-12 구현, 결정 014): 세션 없이 읽는다(로그인 전 화면에서도). 관리자가 공개한 것만, 관리자가 정한 순서대로 온다. `id` 는 UUID, `question` 은 1~200자, `answer` 는 1~2000자의 꾸밈 없는 글자(줄바꿈이 들어 있을 수 있다. HTML·마크다운이 아니다). 아직 글이 없으면 `[]` 다(오류가 아니다). 응답 헤더 `Cache-Control: public, max-age=300`. 주소별 분당 120번을 넘으면 429 `RATE_LIMITED`. 같은 경로의 다른 메서드와 `/faqs/` 아래 경로는 없다. 글을 쓰는 곳은 "관리자 API" 의 `/admin/faqs` 다.
- `GET /shared/{shareId}` = `{ kind: READING \| SCORE \| RANKING \| NEXT_TURN, title, lines[], score?, items?[{ name, score }] }` (글과 숫자만, 출생 정보·근거 없음).

## 관리자 API (관리 화면 저장소 `byeolmyeong-admin` 과 맞춘다. 바꾸면 이 절을 같은 커밋에서 고친다. 결정 014)
관리 화면(웹)이 부르는 API 다. 앱은 부르지 않는다. 경로는 모두 `/admin/` 아래다.

### 공통
- 요청·응답 본문은 JSON(`Content-Type: application/json`, UTF-8). 본문이 없는 응답(204)은 빈 본문이다.
- 인증: `POST /admin/auth/login` 말고는 모두 요청 헤더 `Authorization: Bearer <token>` 이 필요하다. `token` 은 로그인 응답의 값이다. 헤더가 없거나, 토큰이 틀리거나, 세션이 끝났거나(로그인 뒤 12시간), 로그아웃했거나, 그 관리자가 꺼졌으면 **401 `{ "error": "UNAUTHORIZED" }`** 다. `/admin/` 아래의 없는 경로도 토큰 없이는 401 이다. 화면은 401 을 받으면 토큰을 버리고 로그인 화면으로 보낸다.
- 관리자 세션은 앱 사용자의 세션과 완전히 따로다: 앱 사용자의 토큰으로 `/admin/**` 를 부르면 401, 관리자 토큰으로 앱의 경로(`/profile` 등)를 부르면 401 이다.
- 세션 수명: 로그인한 때부터 12시간(응답의 `expiresAt`). 쓰는 동안 늘어나지 않고, 갱신 경로는 없다(다시 로그인한다). 한 관리자가 여러 곳에서 동시에 로그인할 수 있다. 서버는 토큰 원문을 저장하지 않는다(해시만). 쿠키는 쓰지 않는다.
- 오류 응답은 언제나 `{ "error": "<코드>" }` 하나다(입력값·설명 글 없음). 코드는 경로마다 아래에 적었다. 공통: 400 `INVALID_REQUEST`(본문이 없거나 JSON 객체가 아니거나, 필드의 타입이 틀리다 — 글자 자리에 숫자·배열·객체·불리언 등) · 401 `UNAUTHORIZED` · 404 `NOT_FOUND`(경로의 id 가 UUID 가 아니거나 그런 것이 없다) · 429 `RATE_LIMITED`.
- 응답의 값이 없는 필드는 키를 빼지 않고 `null` 로 보낸다(앱 쪽 계약과 다르다). 요청에서 읽지 않는 키는 무시한다.
- 시각은 모두 UTC 의 `YYYY-MM-DDTHH:mm:ssZ` 글자다(예 `2026-11-12T04:05:06Z`). id 는 UUID 글자다.
- 글자 수는 유니코드 코드 포인트로 센다(한글 한 글자가 1, 이모지 한 개도 1 — JavaScript 의 `.length` 가 아니라 `[...text].length`).

### 경로
| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `POST /admin/auth/login` (인증 없음) | `{ loginId, password }` | 200 `{ token, expiresAt, admin: Admin }` |
| `POST /admin/auth/logout` | 없음 | 204 |
| `GET /admin/me` | 없음 | 200 `Admin` |
| `PUT /admin/me/password` | `{ currentPassword, newPassword }` | 204 |
| `GET /admin/admins` | 없음 | 200 `[AdminAccount]` 먼저 만든 관리자부터 |
| `POST /admin/admins` | `{ loginId, name, password }` | 201 `AdminAccount` |
| `PATCH /admin/admins/{id}` | `{ active }` | 200 `AdminAccount` |
| `GET /admin/inquiries?status=&category=&page=&size=` | 쿼리(모두 선택) | 200 `{ items: [Inquiry], page, size, total }` 새것부터 |
| `GET /admin/inquiries/{id}` | 없음 | 200 `Inquiry` |
| `PATCH /admin/inquiries/{id}` | `{ status?, memo? }` | 200 `Inquiry` |
| `GET /admin/stats` | 없음 | 200 `Stats` |
| `GET /admin/faqs` | 없음 | 200 `[Faq]` 보이는 순서대로(공개하지 않은 것 포함) |
| `POST /admin/faqs` | `{ question, answer, published? }` | 201 `Faq` |
| `PATCH /admin/faqs/{id}` | `{ question?, answer?, published? }` | 200 `Faq` |
| `DELETE /admin/faqs/{id}` | 없음 | 204 |
| `PUT /admin/faqs/order` | `{ ids: [id, …] }` | 200 `[Faq]` 새 순서의 전체 목록 |

### 로그인·세션
- `Admin` = `{ id, loginId, name }`. `AdminAccount` = `{ id, loginId, name, active, createdAt }`. 비밀번호와 그 해시는 어떤 응답에도 없다.
- `POST /admin/auth/login`: `loginId` 는 앞뒤 공백과 대소문자를 가리지 않는다. `password` 는 글자 그대로 본다.
  - 200 `{ token, expiresAt, admin }`. `token` 을 화면이 보관해 이후 요청의 `Authorization: Bearer <token>` 에 넣는다. `expiresAt` 은 이 세션이 끝나는 때다.
  - 401 `INVALID_CREDENTIALS`: 없는 아이디, 틀린 비밀번호, 꺼진 관리자 — 셋을 구분해 주지 않는다. 관리자가 한 명도 없는 서버에서도 이 답이다.
  - 400 `INVALID_REQUEST`: `loginId`·`password` 가 없거나 `null` 이거나 글자가 아니다.
  - 429 `RATE_LIMITED`: 로그인 시도는 10분(서버 시계의 10분 단위 창)에 아이디별 5번·주소(IP)별 20번까지다. 성공한 로그인도 센다. 넘으면 맞는 비밀번호여도 그 창이 끝날 때까지 429 다.
- `POST /admin/auth/logout`: 이 요청의 토큰만 끝낸다(같은 관리자의 다른 세션은 그대로). 204. 그 뒤 그 토큰은 401 이다.
- `PUT /admin/me/password`: 자기 비밀번호만 바꾼다. 204. 바꾸면 이 요청의 세션은 그대로이고 **같은 관리자의 다른 세션은 모두 끝난다**.
  - 400 `INVALID_CURRENT_PASSWORD`(지금 비밀번호가 틀리다) · `INVALID_PASSWORD`(새 비밀번호가 규칙에 안 맞다) · `INVALID_REQUEST`(둘 중 하나가 없거나 글자가 아니다) · 429 `RATE_LIMITED`(지금 비밀번호 확인도 아이디별 로그인 시도에 같이 센다).
- 비밀번호 규칙: 10자 이상, UTF-8 로 72바이트 이하(영문·숫자·기호만이면 72자, 한글만이면 24자까지). 앞뒤 공백을 떼지 않는다. 그 밖의 조합 규칙은 없다.

### 관리자 관리
- 역할 구분은 없다. 관리자는 누구나 다른 관리자를 만들고 끄고 켤 수 있다. 관리자를 지우거나, 이름·아이디를 고치거나, 남의 비밀번호를 다시 정하는 경로는 없다.
- `POST /admin/admins`: `loginId` 는 앞뒤 공백을 떼고 소문자로 바꾼 뒤 소문자·숫자·`.`·`_`·`-` 3~32자(첫 글자는 소문자나 숫자). `name` 은 앞뒤 공백을 뗀 1~40자. `password` 는 위 규칙(처음 비밀번호이고, 받은 관리자가 `PUT /admin/me/password` 로 바꾼다). 201 `AdminAccount`(`active: true`).
  - 400 `INVALID_LOGIN_ID` · `INVALID_NAME` · `INVALID_PASSWORD`(없거나 `null` 이어도 이 코드. 여러 개가 틀리면 이 순서로 처음 걸린 것 하나) · `INVALID_REQUEST`(타입이 틀리다) · 409 `LOGIN_ID_TAKEN`(이미 있는 아이디 — 꺼진 관리자의 아이디도 다시 쓸 수 없다).
- `PATCH /admin/admins/{id}`: `{ "active": false }` 로 끄고 `{ "active": true }` 로 켠다. 200 바뀐 `AdminAccount`. 이미 그 상태여도 200 이다.
  - 끄면 그 관리자의 세션이 모두 지워져 그 사람의 다음 요청부터 401 이고, 로그인도 401 `INVALID_CREDENTIALS` 다. 다시 켜면 로그인할 수 있다(예전 토큰은 되살아나지 않는다). 그 관리자가 남긴 처리 기록은 그대로다.
  - 409 `CANNOT_DEACTIVATE_SELF`(자기 자신은 끌 수 없다) · 409 `LAST_ACTIVE_ADMIN`(마지막 남은 활성 관리자는 끌 수 없다 — 두 관리자가 동시에 서로를 끌 때만 나온다) · 400 `INVALID_REQUEST`(`active` 가 없거나 `null` 이거나 불리언이 아니다 — `"false"`·`0` 도 틀리다) · 404 `NOT_FOUND`.

### 문의
- `Inquiry` = `{ id, category, body, replyEmail, createdAt, status, memo, handledBy, handledAt, ownerRef }`. 목록의 원소와 한 건 조회와 `PATCH` 의 응답이 모두 이 모양이다(목록에도 글 전체가 온다).
  - `category`: `ACCOUNT`(계정)·`READING`(해석)·`SYNASTRY`(궁합)·`BUG`(오류)·`OTHER`(기타). 앱 사용자가 고른 값이다.
  - `body`: 문의 글(10~2000자, 줄바꿈이 들어 있을 수 있다). 사용자가 쓴 글이므로 화면은 HTML 로 해석하지 않고 글자로만 보여 준다.
  - `replyEmail`: 답장 받을 이메일 또는 `null`(남기지 않음). 모양만 확인된 값이다. 서버는 메일을 보내지 않는다 — 답장은 관리자가 자기 메일로 직접 보낸다.
  - `createdAt`: 문의를 보낸 때.
  - `status`: `NEW`(새 문의, 앱이 보내면 언제나 이 값) · `IN_PROGRESS`(처리 중) · `DONE`(끝남). 어느 값에서 어느 값으로든 바꿀 수 있다.
  - `memo`: 관리자끼리 보는 메모 또는 `null`. 앱 사용자에게는 보이지 않는다.
  - `handledBy`: `{ id, name }` 또는 `null` — 상태나 메모를 **마지막으로** 바꾼 관리자(`name` 은 표시 이름). `handledAt` 은 그때 또는 `null`. 아무도 손대지 않은 문의는 둘 다 `null` 이다. 이력은 남지 않는다(마지막 한 번만).
  - `ownerRef`: 보낸 계정을 가리키는 12자(16진수) 글자. 같은 계정이 보낸 문의는 값이 같아 묶어 볼 수 있다. 계정 id 가 아니고 이것으로 다른 정보를 찾을 수 없다. 출생 정보·계정 id 는 어떤 응답에도 없다.
- `GET /admin/inquiries`: 만든 때가 늦은 것부터(같으면 id 가 큰 것부터). 쿼리는 모두 없어도 되고, 빈 값(`status=`)은 없는 것으로 본다.
  - `status`: 위 세 값 중 하나. 그 상태만 준다. 그 밖의 값은 400 `INVALID_STATUS`(소문자도 틀리다).
  - `category`: 위 다섯 값 중 하나. 그 종류만 준다. 목록에 없는 값이면 오류가 아니라 빈 목록이다.
  - `page`: 0 부터 세는 쪽 번호(기본 0). `size`: 한 쪽의 개수 1~100(기본 20). 정수가 아니거나 범위를 벗어나면 400 `INVALID_PAGE`.
  - 응답 `{ items, page, size, total }`: `page`·`size` 는 서버가 쓴 값, `total` 은 조건에 맞는 문의의 전체 수(쪽 수는 `Math.ceil(total / size)`). 마지막 쪽을 넘으면 `items` 가 빈 배열이다.
- `GET /admin/inquiries/{id}`: 404 `NOT_FOUND`(없거나, 계정이 지워져 함께 지워졌거나, id 가 UUID 가 아니다).
- `PATCH /admin/inquiries/{id}`: `status`·`memo` 가운데 **보낸 키만** 바꾼다(둘 중 하나는 있어야 한다). 200 바뀐 `Inquiry`. 바꿀 때마다 `handledBy` 가 이 요청의 관리자로, `handledAt` 이 지금으로 바뀐다(값이 예전과 같아도). 처리한 관리자는 토큰에서만 얻는다 — 본문의 다른 키(`handledBy`·`body` 등)는 읽지 않는다.
  - `status`: 위 세 값 중 하나. `memo`: 앞뒤 공백을 뗀 2000자 이하의 글. `null` 이거나 빈 글·공백뿐인 글이면 메모를 지운다(`null` 이 된다).
  - 400 `INVALID_REQUEST`(본문이 객체가 아니거나, 두 키가 모두 없거나, 타입이 틀리다) · `INVALID_STATUS`(`status` 키가 있는데 `null` 이거나 목록에 없다) · `INVALID_MEMO`(2000자를 넘는다) · 404 `NOT_FOUND`. 거절된 요청은 아무것도 바꾸지 않는다.
- 문의는 지울 수 없고 글·종류·이메일은 고칠 수 없다. 앱 사용자가 계정을 지우면 그 사람의 문의는 처리한 것까지 모두 함께 지워진다(목록에서 사라지고 한 건 조회는 404).

### 현황 수치
- `GET /admin/stats` → `Stats`. 부를 때마다 DB 에서 새로 센다(캐시 없음). 모든 수는 0 이상의 정수다. 개수뿐이고 사람별 행·출생 정보·글은 없다.
  ```
  {
    "generatedAt": "2026-11-12T04:05:06Z",
    "accounts":        { "total": 0, "last7Days": 0, "last30Days": 0 },
    "profiles":        { "total": 0 },
    "readings":        { "total": 0 },
    "reports":         { "total": 0, "byStatus": { "DONE": 0, "WRITING": 0, "FAILED": 0 } },
    "synastries":      { "total": 0 },
    "synastryReports": { "total": 0, "byStatus": { "DONE": 0, "WRITING": 0, "FAILED": 0 } },
    "directions":      { "total": 0, "byTopic": { "LOVE": 0 } },
    "inquiries":       { "total": 0, "byStatus": { "NEW": 0, "IN_PROGRESS": 0, "DONE": 0 } }
  }
  ```
  - `generatedAt`: 센 때. `accounts`: 앱 사용자 계정(관리자는 세지 않는다) — 전체, 지금부터 7일(168시간) 안에 만든 것, 30일 안에 만든 것(`last30Days` 는 `last7Days` 를 포함한다). 개발용 로그인 계정도 센다.
  - `profiles`: 출생 정보를 저장한 계정 수. `readings`: 요약 해석. `reports`: 심층 리포트. `synastries`: 궁합. `synastryReports`: 궁합 리포트. `directions`: 방향성 제시. `inquiries`: 문의.
  - `byStatus` 는 적힌 키가 언제나 모두 있고(없으면 0) 합이 `total` 이다. `byTopic` 은 DB 에 있는 주제만 온다(지금은 `LOVE` 하나. 글이 하나도 없으면 `{}`, 주제가 늘면 키가 늘어난다 — 화면은 키를 돌면서 그린다).
  - 리포트의 `WRITING` 은 행에 적힌 값 그대로다: 서버가 꺼져 멈춘 생성(앱에는 `FAILED` 로 보인다)도 여기서는 `WRITING` 이다.
  - **세지 못하는 것**: 저장되지 않는 실패 — 요약 해석·방향성 제시·궁합 요약의 생성 실패(502)는 DB 에 남지 않아 수치에 없다. 리포트의 `FAILED` 는 행으로 남은 것만이고, 다시 요청해 성공하면 그 행이 `DONE` 으로 바뀌므로 "지금까지 실패한 횟수" 가 아니라 "지금 실패 상태인 리포트 수" 다.
  - 계정이 지워지면 그 사람의 행도 지워지므로 모든 수는 "지금 남아 있는 것" 이다(누적이 아니다).

### FAQ
- `Faq` = `{ id, question, answer, sortOrder, published, updatedAt, updatedBy }`.
  - `question`: 앞뒤 공백을 뗀 1~200자. `answer`: 앞뒤 공백을 뗀 1~2000자(가운데 줄바꿈은 그대로 둔다). 둘 다 꾸밈 없는 글자다(HTML·마크다운으로 해석하지 않는다).
  - `sortOrder`: 정수. 작은 것부터 보인다는 뜻뿐이다(값이 1 부터 이어진다고 믿지 않는다 — 지우면 빈 번호가 생긴다). 목록은 이미 이 순서로 온다.
  - `published`: `true` 인 것만 앱(`GET /faqs`)에 보인다.
  - `updatedAt`·`updatedBy`(`{ id, name }`): 글이나 공개 여부를 마지막으로 바꾼(또는 만든) 때와 관리자. 순서만 바꾼 것은 적지 않는다.
- `POST /admin/faqs`: 목록의 끝에 붙는다. `published` 는 불리언이고 없으면 `false`(공개하지 않은 채로 만든다). 201 `Faq`.
  - 400 `INVALID_QUESTION`·`INVALID_ANSWER`(없거나 `null` 이거나 비었거나 너무 길다. 둘 다 틀리면 `INVALID_QUESTION`) · `INVALID_REQUEST`(타입이 틀리다 — `published: "true"` 도 틀리다).
- `PATCH /admin/faqs/{id}`: 보낸 키만 바꾼다(셋 중 하나는 있어야 한다). 공개는 `{ "published": true }`, 내리기는 `{ "published": false }`. 200 바뀐 `Faq`. 순서는 여기서 바꾸지 않는다(`sortOrder` 를 보내도 읽지 않는다).
  - 400 `INVALID_REQUEST`(세 키가 모두 없거나, 타입이 틀리거나, `published` 가 `null` 이다) · `INVALID_QUESTION`·`INVALID_ANSWER`(키가 있는데 `null` 이거나 비었거나 너무 길다) · 404 `NOT_FOUND`.
- `DELETE /admin/faqs/{id}`: 204. 되돌릴 수 없다. 404 `NOT_FOUND`.
- `PUT /admin/faqs/order`: `ids` 에 **지금 있는 FAQ 전부의 id 를(공개하지 않은 것까지) 보일 순서대로 한 번씩** 담아 보낸다. 200 새 순서의 `[Faq]`.
  - 400 `INVALID_ORDER`: 빠진 id 가 있거나, 없는 id·UUID 가 아닌 글자가 있거나, 같은 id 가 두 번 있다 — 그 사이 다른 관리자가 FAQ 를 만들거나 지웠을 때도 이 답이므로 화면은 목록을 다시 받아 온다. 400 `INVALID_REQUEST`: `ids` 가 없거나 배열이 아니거나 원소가 글자가 아니다. FAQ 가 하나도 없을 때의 `{ "ids": [] }` 는 200 `[]`.
- 앱 쪽 공개 조회는 `GET /faqs`(세션 없음, 위 "API 계약"): 공개한 것만 같은 순서로 `[{ id, question, answer }]`. 응답이 5분 캐시될 수 있어(`Cache-Control: public, max-age=300`) 공개·수정·내리기가 앱에 보이기까지 5분쯤 걸릴 수 있다. 관리 화면의 출처는 `GET /faqs` 를 부를 수 없다(CORS) — 미리 보기는 `GET /admin/faqs` 의 `published` 로 그린다.
- 처음 글은 서버에 없다(빈 목록). 관리 화면으로 넣는다.

### 브라우저 출처 (CORS)
- 관리 화면이 열리는 주소를 서버의 환경 변수 `ADMIN_ALLOWED_ORIGINS`(설정 `byeolmyeong.cors.admin-allowed-origins`, 쉼표로 구분, 예 `https://admin.example.com,http://localhost:5173`)에 적어야 브라우저가 부를 수 있다. 주소는 `스킴://호스트[:포트]` 까지이고 끝에 `/` 를 붙이지 않는다.
- `/admin/**` 는 이 주소만 허용한다. 허용 메서드 GET·POST·PUT·PATCH·DELETE, 허용 요청 헤더 `Authorization`·`Content-Type`. 쿠키·자격 증명 허용은 쓰지 않는다(`fetch` 에 `credentials` 를 주지 않는다).
- 앱의 주소(`ALLOWED_ORIGINS`)는 `/admin/**` 를 부를 수 없고, 관리 화면의 주소는 `/admin/**` 밖의 경로를 부를 수 없다. 허용하지 않은 출처의 브라우저 요청은 맞는 토큰이 있어도 403(본문 `Invalid CORS request`, JSON 이 아니다)이다. 비워 두면 `/admin/**` 는 어떤 브라우저 출처에도 열리지 않는다(`Origin` 헤더가 없는 호출 — curl·서버 사이 — 은 CORS 와 상관없이 토큰만 본다).
- 401 응답에도 허용 헤더가 붙으므로 화면이 401 을 읽을 수 있다.

### 첫 관리자와 환경 변수
- 첫 관리자는 API 로 만들 수 없다. 서버가 뜰 때 **관리자가 한 명도 없고** 환경 변수 `ADMIN_BOOTSTRAP_ID`·`ADMIN_BOOTSTRAP_PASSWORD` 가 **둘 다** 있으면 그 값으로 만든다(표시 이름은 아이디와 같다). 아이디·비밀번호 규칙은 위와 같고, 규칙에 안 맞으면 만들지 않는다(로그 `event=admin_bootstrap_skipped reason=…`). 관리자가 한 명이라도 있으면 두 변수는 읽지 않는다(비밀번호를 덮어쓰지 않는다). 하나라도 없으면 관리자가 없는 채로 뜨고 `/admin/**` 는 전부 401 이다.
- 설정(`application.properties`): `byeolmyeong.admin.session-hours`(12) · `byeolmyeong.admin.bcrypt-strength`(12) · `byeolmyeong.rate-limit.admin-login-per-ip`(20) · `byeolmyeong.rate-limit.admin-login-per-id`(5).

## 데이터
- `accounts`(공급자, 공급자 사용자 식별자 unique) · `sessions`(토큰 해시) · `profiles`(출생 정보. 태어난 곳은 시각을 알 때만 있다) · `readings`(소유자, 종류, 차트 해시, 본문, 공유 식별자) · `reports`(소유자·차트 해시 unique, 상태 `WRITING`·`DONE`·`FAILED`, 본문은 `DONE` 일 때만 — 네 영역의 글과 시기(`timing`: 마디·구간의 날짜와 글, "지금" 은 없음), 생성을 시작한 때(그 생성의 표식)·살아 있다는 표시를 마지막으로 적은 때(`active_at`, V9)·상태가 바뀐 때) · `synastries`(두 참여자 또는 한 참여자 + 직접 입력, 관계 유형, 나 기준 점수, 부를 이름, 키워드, 본문, 공유 식별자) · `invites`(초대 식별자, 만료, 상태) · `directions`(소유자·주제·대상 unique, 본문. 대상의 뜻은 주제가 정한다: `LOVE` 는 차트 해시 — 결정 009) · `inquiries`(V11: 소유자, 종류, 글, 답장 받을 이메일 — 없으면 null, 만든 때. 기기 정보·주소는 없다 — 결정 013) · `push_tokens`.
- 소유자를 가리키는 테이블은 모두 `on delete cascade` 다: 계정을 지우면 문의도 함께 지워진다. 문의는 관리자 API(`/admin/inquiries`)로 읽고 처리한다(결정 014).
- 관리자 쪽 테이블(V12, 결정 014): `admin_accounts`(로그인 아이디 unique·소문자, bcrypt 해시, 표시 이름, 활성 여부, 만든 때) · `admin_sessions`(토큰 해시, 관리자, 끝나는 때) · `faqs`(질문, 답, 순서, 공개 여부, 고친 때, 고친 관리자). `inquiries` 에는 처리 칸 넷이 더해졌다: 상태(`NEW`·`IN_PROGRESS`·`DONE`, 기본 `NEW`)·관리자 메모·처리한 관리자·처리한 때. 관리자 테이블은 `accounts`·`sessions` 와 아무 관계가 없고, 관리자는 지우지 않는다(끄기만 한다).
- 직접 입력한 궁합 상대의 출생 정보는 저장하지 않는다. 계산한 뒤 버리고 글만 남긴다.
- 랭킹은 `synastries` 의 점수로 만든다. 따로 저장하지 않는다.
- 지금의 궁합 테이블(직접 입력, V7. 위 줄의 `synastries` 설명보다 이쪽이 맞다): `synastries`(소유자, 부를 이름, 관계 유형, 나 기준 점수, 한 줄 요약, 키워드, 계산할 때의 내 차트 해시) · `synastry_reports`(궁합마다 한 행, 상태 `WRITING`·`DONE`·`FAILED`, 본문은 `DONE` 일 때만, 생성을 시작한 때, 살아 있다는 표시를 마지막으로 적은 때 `active_at` — V9). 초대로 연결된 두 참여자와 공유 식별자는 초대·공유 때 더한다.
- 직접 입력한 상대의 행성 황경·별자리와 두 차트 사이의 각도 목록도 저장하지 않는다. 이유: 내 차트와 겹쳐 보면 상대의 생일을 좁힐 수 있다(결정 010).

## 글 생성 규칙
- 프롬프트는 리소스 파일로 둔다. 본문에 점성술 용어를 쓰지 않고, 문단마다 근거(차트 요소)를 별도 필드로 받는다. 건강·투자·미래 사건을 단정하지 않는다.
- 근거 대조: AI 가 돌려준 `basis` 의 차트 요소가 서버가 계산한 차트에 없으면 그 응답을 버리고 다시 생성한다(상한 2회). 이유: 없는 요소를 근거로 든 글은 신뢰를 깬다.
- 같은 소유자·차트·종류의 글은 한 번만 생성한다. 실패·빈 응답·구조가 틀린 응답은 저장하지 않는다.
- 생성 호출부는 인터페이스 하나(`generation.TextGenerator`) 뒤에 둔다. 이유: 공급자(지금은 Claude, 결정 006)와 모델을 바꿀 수 있어야 한다. 결정 006 이 말하는 `reading.ReadingGenerator`·`ClaudeReadingGenerator` 가 리포트도 쓰게 되면서 이 이름으로 옮겨졌다.
- 프롬프트에는 차트 요소(천체·별자리·하우스·각도 관계)만 넣는다. 출생 날짜·시각·장소와 도수는 넣지 않는다. 시각을 모르면 하우스·상승점·중천·행운점·버텍스를 넣지 않고, 글에서도 쓰지 않게 한다.
- 요약 해석의 모양: 시각을 알면 문단 5개(중심 → 마음 → 첫인상과 지향 → 힘이 몰린 곳 → 과제), 모르면 "첫인상과 지향"이 빠져 4개. 강점 3개, 보완점 3개. 개수가 다르거나 빈 글이 있으면 버린다.
- 지시문은 공통 글쓰기 규칙(`prompts/writing-rules.md`: 말투·용어 금지·단정 금지·근거) 뒤에 글마다의 지시문을 붙여 만든다. 요약 해석: `reading-summary*.md`. 리포트: `report.md` + 영역별 `report-<영역>.md` + 시각을 모르면 `report-time-unknown.md`. 시기: `report-timing.md`.
- 리포트의 모양과 생성: 영역(성격·관계·일·감정)마다 모델을 따로 부른다(네 호출을 한꺼번에). 영역마다 제목 하나와 문단 4~6개, 문단마다 근거. 틀린 영역(빈 글·문단 수·근거 없는 문단·차트에 없는 근거)은 그 영역만 다시 생성한다(영역마다 다시 2회까지). 한 영역이라도 끝내 못 만들거나 호출이 실패하면 리포트 전체가 `FAILED` 이고 아무것도 저장하지 않는다. 관계 영역은 "나는 사람을 어떻게 대하는 사람인가"(친구·가족·연인 전반)이고 연애 조언이 아니다.
- 방향성 제시의 모양과 검증(지금은 이성 주제): 지시문은 공통 규칙 + `direction.md` + 주제별 `direction-<주제>.md`(지금은 `direction-love.md`) + 시각을 모르면 `direction-time-unknown.md`. 한 줄 제목과 항목 3개, 항목마다 근거. 요약 해석과 같은 검증(빈 글·개수·근거 없는 항목·차트에 없는 근거)에 더해 조언형인지 글자로 본다: 항목 제목이 "보기"로 끝나지 않거나, 어느 글에든 "반드시"·"틀림없이"·"운명", 앞일을 말하는 끝맺음(ㄹ 받침 뒤의 "거예요"·"거에요"·"겁니다"·"것입니다"·"것이에요"), "~게 됩니다"·"~게 돼요" 가 있으면 버리고 다시 생성한다(다시 2회까지, 그래도 틀리면 502). 규칙의 단일 출처는 `direction/Direction.kt` 의 `adviceProblem`. 이성 주제는 "연애에서 해 볼 만한 것"(실천)이고 리포트 관계 영역(성향 설명)과 다르다.
- 시기의 글(결정 008): 지시문은 공통 규칙 + `report-timing.md`(+ 시각을 모르면 `report-time-unknown.md`). 차트 글 아래에 "[시기 목록]"(줄마다 id · 시작 해~끝 해 · 종류 · 무엇이 무엇에 닿는지 · 영역)을 주고, 줄마다 `{ id, fromYear, toYear, title, body }` 를 받는다. 모델은 해를 계산하지 않고 옮겨 적기만 한다. 줄에는 해만 넣는다(달·날·나이·도수 없음). 옮겨 적은 해가 계산값과 다르거나 글 안에 그 줄의 기간 밖의 해가 나오면(`YEAR_MISMATCH`), 줄이 빠지거나 더해지거나 글이 비면(`MALFORMED`) 그 응답을 버리고 다시 생성한다(2회까지). 끝내 못 받으면 리포트 전체가 `FAILED` 다. `basis` 는 없다.
- 리포트의 글은 요청 스레드가 아니라 정해진 수의 일꾼(`byeolmyeong.report.worker-threads`·`queue-capacity`)이 쓴다. 일 하나는 한 영역의 글 또는 시기(타임라인 계산 + 글)다. 서버는 자기가 맡은 생성을 기억해 두고 `byeolmyeong.report.heartbeat-seconds`(15초)마다 그 행들의 `active_at` 을 다시 적는다(주기 작업, `@EnableScheduling`). 서버가 꺼지면 더 적히지 않으므로 2분 뒤 그 행은 멈춘 것으로 보인다. 일은 모델을 부르기 전마다(줄에서 나온 직후, 답이 틀려 다시 부르기 전) 그 행이 아직 자기 생성의 것인지(`WRITING` 이고 `started_at` 이 같은지) 보고, 아니면 부르지 않고 그만둔다 — 다른 생성이 자리를 잡았거나, 줄이 차서 실패로 끝났거나, 계정이 지워진 경우다. 끝낼 때도 같은 조건으로만 저장한다. 로그에는 이벤트 이름·영역·사유 코드·시도 횟수·토큰 수만 남긴다.
- 궁합 글: 지시문은 `writing-rules.md` + `synastry.md`(두 사람 글의 규칙) + 글마다의 파일(`synastry-summary.md`). 모델에게 주는 것은 관계 유형, 서버가 계산한 점수(60 미만이면 "다른 결의 사이" 표시), 두 차트 사이의 연결("나의 금성(황소) – 상대의 화성(염소): 결", 강한 것부터)뿐이다. 부를 이름·출생 정보·도수는 주지 않는다. 키워드 3개(겹치지 않게)와 한 줄 요약을 받고, 개수·빈 글·길이가 틀리거나 글에 숫자가 있으면 버리고 다시 생성한다(다시 2회까지, 끝내 못 만들면 502 이고 저장하지 않는다). 점수는 응답 구조에 칸이 없다.
- 궁합 리포트의 글: 영역(끌림·대화·갈등·오래가기)마다 모델을 따로 부른다. 지시문은 `writing-rules.md` + `synastry.md` + `synastry-report.md` + `synastry-report-<영역>.md`, 재료는 요약과 같은 연결 목록에 "근거로 쓸 수 있는 말"(내 쪽 요소)을 붙인 것이다. 영역마다 제목 하나와 문단 3~4개. 틀린 영역(빈 글·문단 수·근거 없는 문단·내 쪽 요소가 아닌 근거·글에 적힌 점수나 퍼센트)은 그 영역만 다시 생성한다(다시 2회까지). 한 영역이라도 끝내 못 만들면 `FAILED` 이고 아무것도 저장하지 않는다. 뒤에서 일하는 스레드는 심층 리포트의 일꾼(`reportExecutor`, `byeolmyeong.report.*`)을 같이 쓴다 — 동시에 나가는 모델 호출 수의 상한이 하나다. 살아 있다는 표시를 적는 주기(`byeolmyeong.report.heartbeat-seconds`)와 멈춤의 기준(2분), 모델을 부르기 전마다 그 행이 아직 자기 생성의 것인지 보고 아니면 그만두는 것도 심층 리포트와 같고, 코드는 `synastry` 안에 따로 있다(기능 패키지는 서로 의존하지 않는다).
- 근거에 쓸 수 있는 말(`generation/Basis.kt` 가 단일 출처, 글자까지 같아야 한다): 천체 이름(태양·달·수성·금성·화성·목성·토성·천왕성·해왕성·명왕성), 지점 이름(북쪽 교점·릴리스, 시각을 알면 행운점·버텍스), 시각을 알면 `상승점`·`중천`, 그 차트에서 천체·지점·상승점·중천이 하나라도 놓인 별자리(양·황소·…·물고기, "자리"를 붙이지 않는다), 천체가 하나라도 놓인 하우스(`N하우스`, 시각을 알 때만).

## 요청 속도 제한 (남용 방지, 서버에서만 판정한다)
- 리포트·궁합·방향성 제시에 기능 횟수 제한은 없다(앱 결정 011). 같은 차트·같은 두 사람의 글은 한 번만 만든다.
- 계정·주소(IP)별 분당 요청 수를 제한한다. 한도는 `application.properties` 의 `byeolmyeong.rate-limit.*`(분당, 서버 메모리). 지금 거는 곳: `POST /charts`·`GET /places`·`POST /synastry/direct`(주소별, 요청마다 모델 호출이 나간다)·`GET /faqs`(주소별, `faqs-per-minute` 120).
- 관리자 로그인(`POST /admin/auth/login`)은 10분에 주소별 `byeolmyeong.rate-limit.admin-login-per-ip`(20)번·아이디별 `admin-login-per-id`(5)번까지다. `admin.AdminAuthService` 가 같은 `web.RateLimiter` 를 쓴다(결정 014).
- 계정별 제한은 `POST /inquiries` 하나뿐이다: 소유자 id 별로 한 시간에 `byeolmyeong.rate-limit.inquiries-per-hour`(5)건, 같은 `web.RateLimiter`(고정 창, 서버 메모리)를 `inquiry.InquiryService` 가 쓴다(결정 013). 다른 엔드포인트의 계정별 제한은 미뤄져 있다(계획 "배포 전 확인" 1번).
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
