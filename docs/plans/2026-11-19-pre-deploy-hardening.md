---
type: plan
status: approved
tags: [배포 전 확인, 개발용 로그인, 요청 속도 제한, 안전장치]
last_reviewed: 2026-11-19
---
# 배포 전 안전장치 — 운영 개발용 로그인 차단 · 계정별 생성 속도 제한 · 낮은 등급 3건

> 상태: 승인(2026-11-19, 소유자 "승인") · 기준 2026-11-19 · 이 계획이 끝나야 저장소 변수 `DEPLOY_ENABLED` 를 켠다(결정 020 #7)

## 목표
- 무엇을: 서버 계획 "배포 전 확인"(docs/plans/2026-11-10-api-mvp.md)에서 남은 것 — 1(생성 기능의 계정별 요청 속도 제한, `POST /readings` 동시 요청 겹침), 2(운영에서 개발용 로그인이 켜진 채 뜨지 않게), 낮은 등급 4·5·6 — 을 끝낸다. 3(세션 만료·로그아웃)은 결정 021 로 이미 풀렸다.
- 누구를 위해: 스테이징·운영 서버. 스테이징은 개발용 로그인이 켜져 있고 운영과 같은 Anthropic 키를 쓴다(결정 020 #6) — 주소를 아는 누구나 생성 비용을 일으킬 수 있다.
- 끝나면: 운영 설정으로 개발용 로그인을 켜면 서버가 뜨지 않는다. 한 계정이 생성 기능을 짧은 시간에 많이 부르면 429. 같은 계정의 같은 요약 해석을 동시에 두 번 불러도 모델은 한 번만 불린다. 그 뒤 `DEPLOY_ENABLED=true` 로 스테이징 첫 배포.

## 결정된 전제
- 결정 005(개발용 로그인), 003(기능 횟수 제한은 없다 — 이 계획의 제한은 남용을 막는 속도 제한이다), 014(관리자), 020(배포·스테이징 설정), 021(소셜 로그인·세션).
- 규칙: docs/golden-rules.md, CLAUDE.md "규칙"(요청 속도 제한은 서버에서만, API 계약을 바꾸면 docs/architecture.md 계약 표를 같은 커밋에서).

## 인터뷰 결과 · 내 결정
소유자가 "다 해"(2026-11-19)라고 해서 묻지 않고 아래 기본값으로 정했다. 숫자는 설정 값이라 나중에 바꾸기 쉽다.
- 환경 이름: 새 환경 변수 `DEPLOY_ENV`(값 `local`·`staging`·`prod`, 설정 `byeolmyeong.deploy-env`). **비어 있으면 `prod` 로 본다** — VM 에서 빠뜨려도 개발용 로그인을 켤 수 없는 쪽이 안전하다. 로컬은 `.env` 에 `DEPLOY_ENV=local` 을 적는다. 틀리면 비용: 로컬에서 한 줄을 더 적어야 한다.
- 안전장치: `byeolmyeong.auth.dev-enabled=true` 인데 환경이 `local`·`staging` 이 아니면 **서버가 뜨지 않는다**(시작할 때 예외, 이유를 한 줄 로그). 404 로 숨기는 것보다 시작 실패가 배포 상태 확인(/health)에서 바로 드러난다.
- 계정별 생성 속도 제한: 모델을 부르는 `POST /readings`·`POST /reports`·`POST /synastry/{id}/report`·`POST /directions`·`POST /synastry/direct` 를 **한 계정이 합쳐서 분당 5번, 시간당 30번**. 넘으면 429 `RATE_LIMITED`(기존 응답과 같다). 저장본을 돌려준 요청도 센다(단순·예측 가능, 보통 사용자는 한참 못 미친다). 기존 주소별 제한은 그대로 둔다. 서버 메모리에서 센다(기존 RateLimiter 와 같은 방식).
- `POST /readings` 겹침: 같은 계정·같은 차트·같은 종류의 생성이 진행 중이면 **기다렸다가 그 결과를 돌려준다**(409 를 새로 만들지 않아 앱 계약이 그대로다).
- 낮은 등급 4: 시각은 `HH:mm` 만 받는다(초가 붙으면 400 `INVALID_TIME`). 날짜가 오늘(한국 시각)이면 아직 오지 않은 시각은 400 `INVALID_TIME`.
- 낮은 등급 5: 세션 없이 여는 경로를 "메서드 + 경로" 로 정한다. `/shared/*` 는 GET 만. `/invites/*` 는 아직 만든 경로가 없으므로 목록에서 뺀다(초대를 만들 때 필요한 메서드만 연다).
- 낮은 등급 6: `ClaudeTextGenerator` 가 응답 필드를 `try` 안에서 읽고 SDK 예외를 `GenerationException` 으로 바꾼다.

## 범위 밖
- 서버 여러 대에서 함께 세는 제한(지금은 서버 한 대 — 결정 020).
- 앱 화면의 429 문구 바꾸기(이미 다른 429 를 받는다. 필요하면 앱 저장소에서).
- 리포트·궁합 리포트의 겹침(이미 행 상태 `WRITING` 으로 한 번만 쓴다), 방향성 제시의 겹침(리뷰가 짚지 않았다).

## 전역 제약
- 테스트부터: 새 동작마다 실패하는 테스트를 먼저 보고 구현한다(red → green).
- 한 태스크 = 한 커밋. 커밋은 한국어 Conventional Commits. 작업 브랜치 `feat/pre-deploy-safety` → PR 은 `develop` 으로.
- 출생 정보·토큰·계정 id 를 로그에 남기지 않는다. 오류 응답에 입력값을 넣지 않는다.
- API 계약이 바뀌는 태스크는 docs/architecture.md 계약 표를 같은 커밋에서 고친다.

## 태스크

### 1. adr-022-safety · 상태: 완료
- 읽을 것: docs/decisions/005·020·021, 이 계획 "내 결정"
- 할 것: `docs/decisions/022-deploy-env-and-generation-limits.md` — `DEPLOY_ENV`(비면 prod)와 개발용 로그인 시작 거부, 계정별 생성 속도 제한(분당 5·시간당 30, 대상 경로), `POST /readings` 겹침은 기다려 같은 결과. 결정 005 에는 front matter 대체 표시만("022(운영 안전장치)").
- 주는 것: 결정 022 번호, `DEPLOY_ENV` 이름, 설정 이름 `byeolmyeong.deploy-env`·`byeolmyeong.rate-limit.generation-per-minute`·`generation-per-hour`
- 하지 말 것: 결정 005 본문 고쳐 쓰기. 이유: golden rules.
- 검증: `grep -n "022" docs/decisions/005-dev-auth-first.md` → front matter 한 줄
- 실행 패턴: 혼자
- 결과 요약: 결정 022 를 썼다(997d4ee). DEPLOY_ENV(없으면 prod)·개발용 로그인 허용 환경·생성 한도 분당 5·시간당 30·요약 해석 진행 중 대기. 결정 005 는 운영 안전장치만 대체 표시.

### 2. dev-login-guard · 상태: 완료
- 읽을 것: `auth/AuthService.kt`, `application.properties`, `src/test/kotlin/.../TestHttp.kt`(`DEV_AUTH_ON`), `DevAuthOffByDefaultIntegrationTest`
- 할 것: 설정 `byeolmyeong.deploy-env=${DEPLOY_ENV:prod}`. 시작할 때 `dev-enabled=true` 이고 환경이 `local`·`staging` 이 아니면 예외로 멈춘다(로그 `event=startup_refused reason=dev_login_outside_local_staging`). 테스트에서 개발용 로그인을 켜는 곳은 환경을 `local` 로 함께 준다. 루트 `.env.example` 에 `DEPLOY_ENV` 설명(로컬 `local`), `deploy/.env.example` 에 스테이징 `staging`·운영 `prod`.
- 주는 것: `DEPLOY_ENV` 를 VM `.env` 에 넣어야 한다는 사실(태스크 7)
- 하지 말 것: 운영에서 404 로만 숨기기. 이유: 켜진 채 뜬 것을 아무도 모른다.
- 검증: 테스트(빨강 먼저) — ① 켜짐 + prod → 컨텍스트 시작 실패 ② 켜짐 + 환경 없음 → 실패 ③ 켜짐 + staging → `POST /auth/dev` 200 ④ 꺼짐 + prod → 시작·`/auth/dev` 404. `./gradlew test` 통과.
- 실행 패턴: 혼자
- 결과 요약: `auth/DevLoginGuard.kt`(bb0ee10). 개발용 로그인이 켜졌는데 DEPLOY_ENV 가 local·staging 이 아니면 `event=startup_refused` 로그 후 시작 거부. 설정 `byeolmyeong.deploy-env`.

### 3. generation-rate-limit · 상태: 완료
- 읽을 것: `web/RateLimitConfig.kt`·`web/RateLimiter.kt`, `auth/Session.kt`(소유자 속성), 대상 컨트롤러 5곳, docs/architecture.md 의 해당 경로 오류 줄
- 할 것: 세션 검사 뒤에 도는 인터셉터 하나가 대상 5개 경로(POST)를 소유자 id 로 센다 — 분당 `generation-per-minute`(5)·시간당 `generation-per-hour`(30), 둘 중 하나라도 넘으면 429 `{"error":"RATE_LIMITED"}`. 기존 주소별 제한은 그대로.
- 하지 말 것: 주소(IP)로 세기. 이유: 같은 계정이 여러 곳에서 부르면 못 막고, 같은 곳의 다른 사람을 막는다.
- 검증: 테스트(빨강 먼저) — 같은 계정 6번째(분) 429, 다른 계정은 200, 시간 창은 시계를 주입해 31번째 429, GET 은 세지 않음. 계약 표의 다섯 경로 오류에 429 를 적는다.
- 실행 패턴: 혼자
- 결과 요약: `auth/GenerationRateLimit.kt`(d6b75b0). 세션 검사(순서 0) 뒤 순서 1, 다섯 경로 POST 를 계정별로 합쳐 세고 넘으면 429 `RATE_LIMITED`. `Session.kt` 에 `markOwner`·`ownerOf`.

### 4. readings-in-flight · 상태: 완료
- 읽을 것: `reading/ReadingService.kt`
- 할 것: 같은 (소유자, 종류, 차트 해시)의 생성이 진행 중이면 그 결과를 기다려 돌려준다. 실패하면 기다리던 쪽도 같은 실패(502). 끝나면 진행 표시를 지운다(성공·실패 모두).
- 하지 말 것: 응답 코드를 새로 만들기. 이유: 앱 계약이 그대로여야 한다.
- 검증: 테스트(빨강 먼저) — 느린 가짜 생성기로 같은 계정이 동시에 두 번 부르면 생성기 호출 1번·둘 다 같은 글, 다른 계정·다른 차트는 각자 호출.
- 실행 패턴: 혼자
- 결과 요약: `ReadingService.summary`(6deb4d5). (소유자·종류·차트 해시)별 진행 표시, 뒤 요청은 최대 10분 대기, 실패는 같은 예외, 표시는 finally 에서 지움.

### 5. birth-time-strict · 상태: 완료
- 읽을 것: `web/BirthRequest.kt`, `birth/BirthTime.kt`, 계약 표의 `INVALID_TIME`
- 할 것: 시각은 `HH:mm` 형식만. 날짜가 오늘(한국 시각)이면 지금보다 늦은 시각은 400 `INVALID_TIME`.
- 검증: 테스트(빨강 먼저) — `12:30:00` → 400, `12:30` → 200, 오늘 + 한 시간 뒤 → 400(시계 주입), 어제 + 23:59 → 200.
- 실행 패턴: 혼자
- 결과 요약: `web/BirthRequest.kt`(94353c9). 시각 `HH:mm` 만, 오늘(한국 시각)이면 지금보다 늦은 시각은 INVALID_TIME, 시각 모름은 그대로. 파서에 시계 인자.

### 6. public-paths-by-method · 상태: 완료
- 읽을 것: `auth/Session.kt`
- 할 것: 세션 없이 여는 목록을 "메서드 + 경로" 로. `/shared/*` 는 GET 만, `/invites/*` 는 뺀다. 나머지(`/health`·`/places`·`/auth/*` 로그인류·`/error`·애플 알림)는 지금 열린 메서드 그대로.
- 검증: 테스트(빨강 먼저) — `POST /shared/x` 세션 없이 401, `GET /shared/x` 세션 없이 404(없는 식별자)·200, 지금 열린 경로들은 그대로.
- 실행 패턴: 혼자
- 결과 요약: `auth/Session.kt`(42f309f·d5869bb). `PUBLIC_ENDPOINTS`(메서드 + 경로), HEAD 는 GET 과 같이, `/invites/*` 뺌, `/error`·`/admin/**` 는 그대로 제외.

### 7. claude-generator-try · 상태: 완료
- 읽을 것: `generation/ClaudeTextGenerator.kt`
- 할 것: `stopReason`·`usage`·`content` 읽기를 `try` 안으로. SDK 예외는 `GenerationException("SDK")`.
- 검증: 테스트(빨강 먼저) — 응답 필드 읽기에서 SDK 예외가 나면 `GenerationException`.
- 실행 패턴: 혼자
- 결과 요약: `generation/ClaudeTextGenerator.kt`(bf753f1). 응답 필드 읽기를 부르기와 같은 try 에. SDK 예외는 GenerationException("SDK").

### 8. vm-env-and-first-staging-deploy · 상태: 진행 (VM `.env` 는 끝, 합친 뒤 첫 배포)
- 할 것: 두 VM `.env` 에 `DEPLOY_ENV`(스테이징 `staging`, 운영 `prod`). 저장소 변수 `DEPLOY_ENABLED=true`(소유자 확인). develop 에 합칠 때의 deploy 워크플로가 스테이징에 첫 배포.
- 검증: `https://api-staging.byeolmyeong.com/health` 200(인증서 Let's Encrypt), 스테이징 `POST /auth/dev` 200, 같은 계정 생성 6번째 429. 배포 계획(2026-11-18) 태스크 8 의 되돌리기 연습.
- 실행 패턴: 함께
- 결과 요약:

## 검증 (전체)
- `/verify`(`./gradlew build`) 통과, 새 테스트가 모두 빨강을 거쳤다.
- 로컬 배포 모의: `AUTH_DEV_ENABLED=true` + `DEPLOY_ENV` 없음 → 서버가 뜨지 않고 이유 한 줄, `staging` → 뜬다.

## 리뷰 초점
1. 안전장치가 테스트 설정·로컬 `.env`·스테이징·운영 네 곳 모두에서 의도대로 도는가(빠뜨린 곳에서 개발용 로그인이 켜지지 않는가).
2. 속도 제한 인터셉터가 세션 검사보다 뒤에서 돌아 소유자를 아는가, CORS 사전 요청을 세지 않는가.
3. 진행 중 생성 표시가 예외·타임아웃 뒤에도 지워지는가(영원히 기다리는 요청이 없는가).
4. 시각 검사가 "시각 모름"(시각 없음) 입력을 막지 않는가.
5. 공개 경로를 줄이면서 지금 앱이 쓰는 로그인 전 요청(`/places`·`/faqs`·`/app-config`·`/auth/*`·`/shared/*`)이 그대로인가.

## 자동 반복
- 태스크 2~7 은 테스트가 기준이라 미달이면 그 태스크만 고쳐 다시 돈다(세 번 미달이면 멈추고 보고). 사람 없이 끝까지 돌리지는 않는다.

## 위험 · 되돌리기
- `DEPLOY_ENV` 를 VM 에 넣지 않으면 스테이징의 개발용 로그인이 켜져 있어 서버가 뜨지 않는다 → 태스크 8 에서 먼저 넣는다. 배포 상태 확인이 실패하면 마지막 정상 jar 로 돌아간다(첫 배포는 정상 jar 가 없어 실패로 끝난다).
- 속도 제한 숫자가 낮으면 정상 사용자가 429 를 본다 → 설정 값만 바꾸면 된다.
- 되돌리기: 브랜치를 버리면 원래대로. 합친 뒤에는 커밋 되돌리기.

## 실행 기록
- 1(adr-022-safety): `docs/decisions/022-deploy-env-and-generation-limits.md` 새로, 결정 005 앞머리 `superseded_by`. 커밋 997d4ee.
- 2(dev-login-guard): 빨강 → 초록. `./gradlew test --tests '*DevLoginGuardTest'` → `tests 5 fail 0 err 0`. 커밋 bb0ee10.
- 3(generation-rate-limit): 빨강 → 초록. `GenerationRateLimitInterceptorTest` → `tests 5 fail 0`, `GenerationRateLimitIntegrationTest` → `tests 2 fail 0`. 커밋 d6b75b0.
- 4(readings-in-flight): 빨강(모델 2번·시도 6번) → 초록. `ReadingApiIntegrationTest` → `tests 16 fail 0 err 0`. 커밋 6deb4d5.
  - 결정: 기존 테스트 '동시에 온 두 요청은 한 행만 남기고 같은 글을 받는다'(모델이 두 번 불리는 것을 CyclicBarrier 로 확인)를 지웠다 — 결정 022 가 바꾼 동작을 확인하던 테스트라 남기면 거짓 실패다. 한 행·같은 글은 새 테스트가 확인한다 — 틀리면 비용: 동시 요청에서 저장 경합(insertIfAbsent) 경로를 직접 치는 테스트가 없어진다(그 경로는 다른 소유자·서버 재시작 같은 드문 경우에만 탄다).
- 5(birth-time-strict): 빨강(형식·미래 시각 2건) → 초록. `BirthRequestParserTest` → `tests 5 fail 0`, `ChartApiIntegrationTest` → `tests 8 fail 0`, `ProfileApiIntegrationTest` → `tests 6 fail 0`. 커밋 94353c9.
- 6(public-paths-by-method): 빨강(테스트 전용 컨트롤러로 `DELETE /shared/{id}`·`GET`·`POST /invites/{id}` 가 세션 없이 200) → 초록. 관련 테스트(SessionPublic·AuthApi·AppConfigApi·Share·Faq·Places) → `관련 테스트 92개 · 실패 0 · 오류 0`. 커밋 42f309f, 문서 d5869bb.
- 7(claude-generator-try): 빨강(usage 없는 응답에서 SDK 예외가 그대로 나옴) → 초록. `ClaudeTextGeneratorTest` → `tests 9 fail 0 err 0`. 커밋 bf753f1.
- 전체: `./gradlew build` → `BUILD SUCCESSFUL in 3m 44s`, `테스트 512개 · 실패 0 · 오류 0 · 건너뜀 0`.
- 로컬 배포 모의(버리는 Postgres + `build/libs/app.jar`, `AUTH_DEV_ENABLED=true`):
  ```
  [unset] DEPLOY_ENV=(없음) → 종료(exit 1)  로그: event=startup_refused reason=dev_login_outside_local_staging deploy_env=prod
  [prod] DEPLOY_ENV=prod → 종료(exit 1)  로그: event=startup_refused reason=dev_login_outside_local_staging deploy_env=prod
  [staging] DEPLOY_ENV=staging → 떠 있음 · /health 200
  ```
- 8(일부): 두 VM `.env` 에 `DEPLOY_ENV`(staging·prod)를 넣고 `ALLOWED_ORIGINS` 를 웹 주소로 바꿨다(권한 600 그대로). 문서 dd59b3b.
  ```
  byeolmyeong-staging: DEPLOY_ENV=staging SITE_ADDRESS=api-staging.byeolmyeong.com ALLOWED_ORIGINS=https://app.staging.byeolmyeong.com ADMIN_ALLOWED_ORIGINS=https://admin-staging.byeolmyeong.com AUTH_DEV_ENABLED=true · 권한 600
  byeolmyeong-prod: DEPLOY_ENV=prod SITE_ADDRESS=api.byeolmyeong.com ALLOWED_ORIGINS=https://app.byeolmyeong.com ADMIN_ALLOWED_ORIGINS=https://admin.byeolmyeong.com · 권한 600
  ```
  - 결정: VM `.env` 는 합치기 전에 바꿨다 — 아직 컨테이너를 띄우지 않았고, 옛 jar 는 DEPLOY_ENV 를 읽지 않아 해가 없다. 앱 세션(byeolmyeong-45)이 웹 주소(앱 결정 037)를 알려 와 ALLOWED_ORIGINS 도 같이 — 틀리면 비용: 웹 주소가 또 바뀌면 두 VM `.env` 를 다시 고친다.

