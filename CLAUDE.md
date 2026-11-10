# 별명 서버 (byeolmyeong-api)

"별명"(한국어 서양 점성술 앱)의 서버. 앱이 보낸 차트로 AI 해석을 생성·저장하고 공유 링크로 내보낸다. 앱 저장소: https://github.com/byeolmyeong/byeolmyeong-app (로컬 `../byeolmyeong-app`)

## 항상 (자동 로드, 합계 30,000자 이내)
- 위반 금지 규칙: @docs/golden-rules.md
- 인수인계: `.local/handoff.md`가 있으면 지금 읽는다. 지난 세션이 어디까지 했고 다음에 뭘 할지가 있다.

## 상황별 (그 일을 할 때만 읽는다)
- 제품·범위·컨셉(해석 말투)을 판단할 때 → 앱 저장소의 `../byeolmyeong-app/docs/product.md`. 기획 문서의 원본은 앱 저장소다. 여기에 복사하지 않는다.
- 서버 구조·데이터 흐름·API 계약을 바꿀 때 → docs/architecture.md
- 지금 할 일 → docs/plans/2026-11-10-api-mvp.md
- 코드 어디서 시작할지 모를 때 → docs/codebase-map.md (`/agent-codebase bootstrap` 이 만든다)
- "왜 이렇게 됐나"가 궁금할 때 → docs/decisions/ ADR 번호순. 바뀌면 새 번호로 대체. 종합된 답은 docs/synthesis/.

## 디렉터리 (이름이 곧 지시)
- `src/main/kotlin/` 서버 코드 · `src/main/resources/` 설정·프롬프트·DB 마이그레이션 · `src/test/kotlin/` 검증 (계획 태스크 1 에서 만든다)
- `docs/` agent가 참고할 문서 · `docs/raw/` 원본(던지는 곳, 고치지 않음) · `.claude/` 설정·규칙·스킬·에이전트·훅 · `build/` 생성물(커밋 안 함)

## 스택
Spring Boot · Kotlin(JVM 21) · Gradle(Kotlin DSL) · PostgreSQL · OpenAI API. 버전은 계획 태스크 1 에서 확정해 docs/decisions/001-spring-boot-postgres.md 에 적는다.

## 명령어 (계획 태스크 1 이후)
- 개발: `./gradlew bootRun` · 빌드·테스트: `./gradlew build` · 테스트만: `./gradlew test`
- 로컬 DB: `docker compose up -d`. 환경 변수 이름은 .env.example

## 도구 (.claude/)
- 스킬 `/plan-feature`(킷 제공): 한 커밋을 넘는 요청은 바로 만들지 않는다. `docs/plans/`에 계획 파일 → 승인 → 실행 → 검증.
- 스킬 `/verify`: 완료 기준(테스트·빌드) 한 번에. 표의 명령·기준값이 이 프로젝트의 "완료"다.
- 스킬 `/ralph-loop`·`/handoff`·`/retro`(킷 제공): 기준 충족까지 자율 반복 · 세션 인수인계 · 월간 회고.
- 에이전트 `reviewer`: 만든 쪽이 검사하지 않는다. diff와 완료 기준만 주고 깨끗한 컨텍스트에서 문제를 찾게 한다.
- 문서 큐레이션 담당: 저장소 소유자 한 명.

## 규칙
- 소유자는 토큰에서만 얻는다. 요청 본문·경로의 소유자 id 를 믿지 않는다. 남의 자원은 404 로 답한다.
- 차트는 서버에서 다시 계산하지 않는다. 앱이 보낸 차트 JSON 의 형식과 값 범위만 검사한다. 근거: docs/architecture.md
- AI 생성 호출은 인터페이스 하나 뒤에 둔다. 실패·빈 응답은 저장하지 않는다.
- API 키는 환경 변수에만 둔다. 출생 정보는 로그에 남기지 않는다.
- API 계약(경로·요청·응답)을 바꾸면 docs/architecture.md 의 계약 표를 같은 커밋에서 고치고, 앱 저장소에 맞춤 변경이 필요하다고 보고한다.
- 결정이 바뀌면 코드보다 먼저 docs/decisions/에 새 ADR. 원본은 docs/raw/, 종합은 docs/synthesis/.
- 커밋: 한국어 + Conventional Commits. 한 커밋 = 한 관심사. 작업 브랜치에서 한다.
- 한 커밋을 넘는 일은 /plan-feature 로 계획을 승인받고 실행한다. 완료라고 말하기 전에 /verify 를 이번에 돌린다.
- 이 파일은 80줄을 넘기지 않는다. 긴 설명은 docs/ 에 두고 여기서는 가리키기만 한다.
