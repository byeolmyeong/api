# 서버 저장소 (byeolmyeong-api) — 앱 이름은 바꿀 예정(가칭 "별명")

한국어 서양 점성술 앱(이름 변경 예정)의 서버. 차트를 계산하고, 그 차트로 AI 해석·리포트·궁합 글을 생성·저장하고, 로그인·초대·공유·알림을 맡는다. 앱 저장소: https://github.com/byeolmyeong/byeolmyeong-app (로컬 `../byeolmyeong-app`)

## 항상 (자동 로드, 합계 30,000자 이내)
- 위반 금지 규칙: @docs/golden-rules.md
- 인수인계: `.local/handoff.md`가 있으면 지금 읽는다. 지난 세션이 어디까지 했고 다음에 뭘 할지가 있다.

## 상황별 (그 일을 할 때만 읽는다)
- 무엇을 만드는지(기능·규칙·출시 기준) → 앱 저장소의 `../byeolmyeong-app/docs/release-1.0.0.md`. 제품 방향은 `../byeolmyeong-app/docs/product.md`. 기획 문서의 원본은 앱 저장소다. 여기에 복사하지 않는다.
- 서버 구조·의존 규칙·용어·데이터 흐름·API 계약을 볼 때·바꿀 때 → docs/architecture.md (의존 규칙은 ArchitectureTest 가 강제)
- 지금 할 일 → docs/plans/2026-11-10-api-mvp.md
- 코드 어디서 시작할지 모를 때 → docs/codebase-map.md (`/agent-codebase bootstrap` 이 만든다)
- "왜 이렇게 됐나"가 궁금할 때 → docs/decisions/ ADR 번호순. 바뀌면 새 번호로 대체. 종합된 답은 docs/synthesis/.
- 배포·서버 운영(스테이징·운영 VM, 백업·복원)을 할 때 → docs/deploy.md (결정 020)

## 디렉터리 (이름이 곧 지시)
- `src/main/kotlin/` 서버 코드 · `src/main/resources/` 설정·프롬프트·DB 마이그레이션 · `src/test/kotlin/` 검증. 패키지 뿌리 `com.byeolmyeong.api`
- `docs/` agent가 참고할 문서 · `docs/raw/` 원본(던지는 곳, 고치지 않음) · `deploy/` 스테이징·운영 VM 의 compose·스크립트 · `.claude/` 설정·규칙·스킬·에이전트·훅 · `build/` 생성물(커밋 안 함)

## 스택
Spring Boot · Kotlin(JVM 21) · Gradle(Kotlin DSL) · PostgreSQL · Claude API(글 생성, 결정 006). 버전은 docs/decisions/001-spring-boot-postgres.md

## 명령어
- 개발: `./gradlew bootRun` · 빌드·테스트: `./gradlew build` · 테스트만: `./gradlew test`
- 테스트는 Docker 가 떠 있어야 한다(Testcontainers).
- 로컬 DB: `docker compose up -d`(`.env` 의 `DATABASE_USERNAME`·`DATABASE_PASSWORD` 를 읽는다). 환경 변수 이름은 .env.example
- 배포: `develop` 에 합쳐지고 CI 통과 → 스테이징, `main` 에 합쳐지고 CI 통과 → 운영(`.github/workflows/deploy.yml`, 저장소 변수 `DEPLOY_ENABLED=true` 일 때만). 배포물은 `./gradlew bootJar` 의 `build/libs/app.jar`

## 도구 (.claude/)
- 스킬 `/plan-feature`(킷 제공): 한 커밋을 넘는 요청은 바로 만들지 않는다. `docs/plans/`에 계획 파일 → 승인 → 실행 → 검증.
- 스킬 `/verify`: 완료 기준(테스트·빌드) 한 번에. 표의 명령·기준값이 이 프로젝트의 "완료"다.
- 스킬 `/ralph-loop`·`/handoff`·`/retro`(킷 제공): 기준 충족까지 자율 반복 · 세션 인수인계 · 월간 회고.
- 에이전트 `reviewer`: 만든 쪽이 검사하지 않는다. diff와 완료 기준만 주고 깨끗한 컨텍스트에서 문제를 찾게 한다.
- 문서 큐레이션 담당: 저장소 소유자 한 명.

## 규칙
- 소유자는 토큰에서만 얻는다. 요청 본문·경로의 소유자 id 를 믿지 않는다. 남의 자원은 404 로 답한다.
- 차트 계산은 `chart` 패키지의 순수 함수에서만 한다. AI 에게 계산을 맡기지 않는다. 근거: docs/decisions/002-chart-calculation.md
- 초대로 연결된 상대의 출생 정보와 행성 위치를 다른 사람에게 내려보내지 않는다. 직접 입력한 상대의 출생 정보는 저장하지 않는다.
- AI 생성 호출은 인터페이스 하나 뒤에 둔다. 실패·빈 응답·근거가 차트와 안 맞는 응답은 저장하지 않는다.
- 요청 속도 제한·만 14세 판정·궁합 점수·변곡점 구간은 서버에서만 판정·계산한다. 기능 횟수 제한은 없다(docs/decisions/003-follow-app-decisions-2026-11-11.md).
- API 키는 환경 변수에만 둔다. 출생 정보는 로그에 남기지 않는다.
- API 계약(경로·요청·응답)을 바꾸면 docs/architecture.md 의 계약 표를 같은 커밋에서 고치고, 앱 저장소에 맞춤 변경이 필요하다고 보고한다.
- 결정이 바뀌면 코드보다 먼저 docs/decisions/에 새 ADR. 원본은 docs/raw/, 종합은 docs/synthesis/.
- 커밋: 한국어 + Conventional Commits. 한 커밋 = 한 관심사. 작업 브랜치에서 하고 PR 은 `develop` 으로(`gh pr create --base develop`, 스테이징). `develop` → `main` 은 운영 배포다.
- 한 커밋을 넘는 일은 /plan-feature 로 계획을 승인받고 실행한다. 완료라고 말하기 전에 /verify 를 이번에 돌린다.
- 이 파일은 80줄을 넘기지 않는다. 긴 설명은 docs/ 에 두고 여기서는 가리키기만 한다.
