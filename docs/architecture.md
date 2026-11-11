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
- 변곡점: 정해진 마디(토성 사각·귀환·두 번째 귀환, 천왕성 대립)와 변화·기회·운명적 만남 구간을 계산하고, 구간마다 기간·영역·크기를 붙인다. 규칙: 앱 저장소 release-1.0.0 F4.
- 궁합: 직접 입력과 초대 링크. 두 차트를 겹치는 계산과 나 기준 점수(40~100, 관계 유형별)는 서버에서만 한다. 나 기준으로 여러 명과 1:1 매칭한 결과를 랭킹으로 돌려준다(인원 제한 없음). 규칙: 앱 저장소 release-1.0.0 F5.
- 공유 조회, 요청 속도 제한(남용 방지), 푸시 알림 발송, 계정 삭제.

## 모듈 (패키지) — 기능별. 결정: docs/decisions/004-module-boundaries-archunit.md
- `chart` — 계산. 순수 함수만 둔다(입력: UTC 시각·위경도 → 출력: 차트·변곡점 구간·궁합 점수). DB·HTTP·AI·Spring 을 모른다. 이유: 계산 정확도가 제품의 신뢰이고 기준값 테스트로 혼자 검증돼야 한다.
- `birth` — 출생지 목록과 시각 변환(`java.time` 의 시간대 이력), 그리고 저장된 출생 정보를 읽고 쓰는 한 곳(`BirthProfileService`). 해석·리포트·궁합이 모두 소유자의 출생 정보를 쓰는데 기능 패키지는 서로 의존할 수 없어서 여기에 둔다.
- `web` — HTTP 공통: 오류 응답 모양(`ErrorResponse`·`BadRequestException`·`NotFoundException`), 출생 정보 요청 검증(`BirthRequestParser`), 요청 속도 제한, CORS, 지금의 `POST /charts` 컨트롤러. `auth` 를 모른다.
- `auth` — 세션 발급·검증, 세션 검사(`SessionInterceptor`: 로그인 전에 쓰는 경로 목록에 없으면 모두 세션 필요), 소유자를 얻는 한 곳(`CurrentOwner`), 개발용 로그인(`POST /auth/dev`, 결정 005), 계정 삭제(`DELETE /account`), 나중에 구글·애플·카카오 토큰 검증과 만 14세 판정.
- `profile` — 내 출생 정보 저장·조회의 HTTP 입구(`GET`·`PUT /profile`). 저장 자체는 `birth.BirthProfileService` 가 한다.
- `reading` — 요약 해석 생성·저장, 근거 대조. 생성 공급자는 `TextGenerator` 인터페이스 하나 뒤에 둔다.
- `report` — 심층 리포트(비동기 생성), 시기 영역의 마디·구간을 `chart` 에서 받아 글을 붙인다.
- `synastry` — 직접 입력, 초대, 수락, 나 기준 점수(`chart` 계산), 랭킹, 궁합 글·궁합 리포트.
- `direction` — 방향성 제시.
- `share` — 공유 조회(공유 카드 4종).
- `push` — 기기 토큰과 알림 발송.

## 디렉터리 구조
```
src/main/kotlin/com/byeolmyeong/api/
  chart/       Positions · Houses · Aspects · Points · Timeline(마디·구간) · SynastryScore   ← 순수 계산
  birth/       Places · BirthTime · BirthProfile(Service·Repository) · PlacesController
  web/         ErrorResponse · BirthRequest(Parser) · RateLimiter · CorsConfig · ChartController
  auth/        AuthController · AuthService · AccountRepository · SessionRepository · Session(CurrentOwner · SessionInterceptor)
  profile/     ProfileController
  reading/     ReadingController · ReadingService · ReadingRepository · ReadingGenerator(인터페이스) · ClaudeReadingGenerator · ReadingPrompt · Basis
  report/      ReportController · ReportService · ReportRepository · ReportDone(이벤트)
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
| 기능 패키지는 `chart`·`birth`·`auth`(소유자 얻기)·`web`(공통) 에만 의존할 수 있다 | 공통으로 쓰는 것만 아래로 둔다 |
| `auth`·`web` 은 기능 패키지에 의존하지 않는다 | 공통이 기능을 알면 순환이 생긴다 |
| `*Repository` 는 같은 패키지의 `*Service` 에서만 부른다(컨트롤러에서 부르지 않는다) | 소유자 검사를 서비스 한 곳에서 한다 |
| 패키지 사이 순환 의존 0 | |
| 글 생성(Claude API)·소셜 로그인 같은 외부 호출은 인터페이스 하나 뒤에만 둔다 | 공급자를 바꿀 수 있게 |

## 용어 (코드·문서·화면에서 같은 말을 쓴다)
| 화면 말 | 코드 이름 | 뜻 |
|---|---|---|
| 요약 해석 | `Reading` | 버튼 하나로 받는 짧은 해석 |
| 심층 리포트 | `Report` | 성격·관계·일·감정·시기 다섯 영역 |
| 마디 | `Milestone` | 누구에게나 오는 변곡점(토성 귀환 등) |
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
| `POST /reports` | 세션 | 없음 | 202 `{ status }` |
| `GET /reports/me` | 세션 | 없음 | `{ status: NONE \| WRITING \| DONE, report? }` |
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
- `Report` = `{ sections[{ area: PERSONALITY \| RELATIONSHIP \| WORK \| EMOTION, title, paragraphs[{ title, body, basis[] }] }], timing }`. `timing`(시기 영역) = `{ milestones[{ kind, year, age }], intervals[{ kind: CHANGE \| OPPORTUNITY \| FATED, from, to, area: PEOPLE \| WORK \| MONEY \| SELF, size: BIG \| SMALL, title, body }], current? }`. `from`·`to` 는 `YYYY-MM`. `FATED` 는 시각을 알 때만.
- `GET /shared/{shareId}` = `{ kind: READING \| SCORE \| RANKING \| NEXT_TURN, title, lines[], score?, items?[{ name, score }] }` (글과 숫자만, 출생 정보·근거 없음).

## 데이터
- `accounts`(공급자, 공급자 사용자 식별자 unique) · `sessions`(토큰 해시) · `profiles`(출생 정보) · `readings`(소유자, 종류, 차트 해시, 본문, 공유 식별자) · `reports`(소유자·차트 해시 unique, 상태, 본문) · `synastries`(두 참여자 또는 한 참여자 + 직접 입력, 관계 유형, 나 기준 점수, 부를 이름, 키워드, 본문, 공유 식별자) · `invites`(초대 식별자, 만료, 상태) · `directions`(소유자, 주제, 대상, 본문) · `push_tokens`.
- 직접 입력한 궁합 상대의 출생 정보는 저장하지 않는다. 계산한 뒤 버리고 글만 남긴다.
- 랭킹은 `synastries` 의 점수로 만든다. 따로 저장하지 않는다.

## 글 생성 규칙
- 프롬프트는 리소스 파일로 둔다. 본문에 점성술 용어를 쓰지 않고, 문단마다 근거(차트 요소)를 별도 필드로 받는다. 건강·투자·미래 사건을 단정하지 않는다.
- 근거 대조: AI 가 돌려준 `basis` 의 차트 요소가 서버가 계산한 차트에 없으면 그 응답을 버리고 다시 생성한다(상한 2회). 이유: 없는 요소를 근거로 든 글은 신뢰를 깬다.
- 같은 소유자·차트·종류의 글은 한 번만 생성한다. 실패·빈 응답·구조가 틀린 응답은 저장하지 않는다.
- 생성 호출부는 인터페이스 하나(`reading.ReadingGenerator`) 뒤에 둔다. 이유: 공급자(지금은 Claude, 결정 006)와 모델을 바꿀 수 있어야 한다.
- 프롬프트에는 차트 요소(천체·별자리·하우스·각도 관계)만 넣는다. 출생 날짜·시각·장소와 도수는 넣지 않는다. 시각을 모르면 하우스·상승점·중천·행운점·버텍스를 넣지 않고, 글에서도 쓰지 않게 한다.
- 요약 해석의 모양: 시각을 알면 문단 5개(중심 → 마음 → 첫인상과 지향 → 힘이 몰린 곳 → 과제), 모르면 "첫인상과 지향"이 빠져 4개. 강점 3개, 보완점 3개. 개수가 다르거나 빈 글이 있으면 버린다.
- 근거에 쓸 수 있는 말(`reading/Basis.kt` 가 단일 출처, 글자까지 같아야 한다): 천체 이름(태양·달·수성·금성·화성·목성·토성·천왕성·해왕성·명왕성), 지점 이름(북쪽 교점·릴리스, 시각을 알면 행운점·버텍스), 시각을 알면 `상승점`·`중천`, 그 차트에서 천체·지점·상승점·중천이 하나라도 놓인 별자리(양·황소·…·물고기, "자리"를 붙이지 않는다), 천체가 하나라도 놓인 하우스(`N하우스`, 시각을 알 때만).

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
