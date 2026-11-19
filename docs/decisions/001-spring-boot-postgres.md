---
type: decision
tags: [Spring Boot, PostgreSQL, 저장소 분리, OpenAI]
status: active
last_reviewed: 2026-11-10
superseded_by: 006-reading-provider-claude.md (글 생성 공급자만), 020-hosting-oracle-vercel.md (운영 호스팅만). 나머지는 유효
---
# 001. Spring Boot + PostgreSQL 로 만들고 앱과 저장소를 나눈다

> 상태: 활성 · 결정일 2026-11-10

## 맥락
서버가 할 일은 차트 계산, 로그인, 글 생성(OpenAI API)·저장, 궁합 초대, 공유 조회, 알림, 나중에 구매 확인이다(이 문장은 2026-11-10 범위 확정에 맞춰 고쳤다. 계산 위치의 결정은 docs/decisions/002-chart-calculation.md). 만드는 사람은 한 명이고 주력이 Spring 이다. 언어는 사용자가 Kotlin 을 골랐다. 같은 결정의 앱 쪽 기록: 앱 저장소 `docs/decisions/004-spring-backend.md`.

## 결정
서버는 Spring Boot(Kotlin, JVM 21, Gradle) + PostgreSQL 로 만들고, 앱 저장소와 분리된 이 저장소에 둔다. 해석 생성은 OpenAI API 를 쓴다.

## 근거
- 혼자 만드는 서비스는 가장 손에 익은 도구가 가장 빠르다.
- Kotlin 은 Spring 이 공식 지원하고, null 안전성과 data class 로 요청·응답 타입이 짧아진다.
- 빌드 도구와 배포 주기가 앱과 달라 저장소를 나누면 설정·CI 가 단순해진다.
- OpenAI 는 사용자가 골랐다. 생성 호출부를 인터페이스 뒤에 두어 바꿀 수 있게 한다.

## 대안
- Supabase — 서버 코드는 줄지만 해석 생성 로직이 서버 함수로 흩어진다.
- NestJS — 앱과 타입을 공유할 수 있지만 공유할 것이 적고 주력 스택이 아니다.
- 앱과 한 저장소(모노레포) — API 계약 변경이 한 커밋에 묶이지만 사용자가 저장소 분리를 택했다.

## 결과·재검토 조건
- API 계약을 바꾸면 두 저장소에 각각 커밋한다. 계약의 원본은 docs/architecture.md 의 표다. 어긋남이 반복되면 OpenAPI 명세 생성을 다시 본다.
- 버전(2026-11-10 확정, start.spring.io 의 안정 버전): Spring Boot 4.1.2 · Kotlin 2.3.21 · Gradle 9.8.2 · JVM 21 · PostgreSQL 17(로컬·테스트 이미지 `postgres:17`). 실제 값의 원본은 `build.gradle.kts` 와 `gradle/wrapper/gradle-wrapper.properties` 다.
- 운영 호스팅은 미정이다. [확인 필요]
