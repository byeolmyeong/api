---
type: decision
tags: [아키텍처, 모듈 경계, 의존 규칙, ArchUnit, DDD]
status: active
last_reviewed: 2026-11-11
superseded_by:
---
# 004. 기능별 패키지로 나누고, 의존 규칙을 ArchUnit 테스트로 강제한다

> 상태: 활성 · 결정일 2026-11-11 · 규칙의 원본은 docs/architecture.md "의존 규칙"

## 맥락
1.0.0 범위가 로그인·해석·리포트·궁합·랭킹·공유·방향성 제시로 커졌다. 만드는 사람은 한 명이고 agent 가 코드를 많이 쓴다. 규칙이 문서에만 있으면 패키지끼리 서로 부르며 엉키기 쉽다. 소유자가 DDD 와 ArchUnit 적용을 물었다.

## 결정
- 패키지는 기능(도메인) 단위로 나눈다: `chart`·`birth`·`auth`·`profile`·`reading`·`report`·`synastry`·`direction`·`share`·`push`, HTTP 공통은 `web`. 각 기능 안에서 계층(controller·service·repository)은 파일 이름으로 나누고 하위 디렉터리를 만들지 않는다.
- 전술적 DDD(애그리거트 루트·도메인 이벤트 버스·리포지토리 인터페이스 분리·헥사고날 포트/어댑터)는 쓰지 않는다. 쓰는 것은 경계(기능별 패키지)와 공용 언어(용어 표, docs/architecture.md "용어")뿐이다.
- 의존 규칙(docs/architecture.md "의존 규칙")을 `src/test/kotlin/.../ArchitectureTest.kt` 의 ArchUnit 테스트로 강제한다. `./gradlew test` 가 실패하면 규칙 위반이다.

## 근거
- 계산(`chart`)이 제품의 신뢰라 다른 것에 물들지 않아야 하고, 이건 문서보다 테스트가 지킨다.
- 혼자 만드는 서비스에서 전술적 DDD 의 인터페이스·계층은 대부분 구현이 하나라 비용만 늘린다(golden-rules "단일 구현 인터페이스는 만들지 않는다").
- ArchUnit 은 테스트 의존성 하나로 끝나고, 위반이 빌드에서 바로 보인다.

## 대안
- 계층별 패키지(controller/·service/·repository/) — 기능 하나를 고치려면 여러 디렉터리를 오가야 하고 기능 사이 의존이 안 보인다.
- 헥사고날 + 전술적 DDD 전부 — 1.0.0 규모에 비해 무겁다. 외부 공급자(OpenAI·소셜 로그인)만 인터페이스 하나 뒤에 둔다(기존 규칙).
- 문서로만 규칙 — agent 가 놓친다.

## 결과·재검토 조건
- 첫 구현 태스크(계획 태스크 14 arch-rules)에서 ArchUnit 테스트를 넣는다. 이미 있는 `chart`·`birth`·`web` 은 지금도 규칙을 지키는지 이 테스트로 확인한다.
- 기능이 둘 이상의 다른 기능을 직접 불러야 하면 규칙을 늘리지 말고 먼저 경계가 맞는지 다시 본다.
