---
type: decision
tags: [Spring Boot, PostgreSQL, 저장소 분리, OpenAI]
status: active
last_reviewed: 2026-11-10
superseded_by:
---
# 001. Spring Boot + PostgreSQL 로 만들고 앱과 저장소를 나눈다

> 상태: 활성 · 결정일 2026-11-10

## 맥락
서버가 할 일은 익명 계정, 해석 생성(OpenAI API)·저장, 공유 조회, 나중에 구매 확인이다. 차트 계산은 앱이 하므로 앱과 공유할 계산 코드가 없다. 만드는 사람은 한 명이고 주력이 Java·Spring 이다. 같은 결정의 앱 쪽 기록: 앱 저장소 `docs/decisions/004-spring-backend.md`.

## 결정
서버는 Spring Boot(Java 21, Gradle) + PostgreSQL 로 만들고, 앱 저장소와 분리된 이 저장소에 둔다. 해석 생성은 OpenAI API 를 쓴다.

## 근거
- 혼자 만드는 서비스는 가장 손에 익은 도구가 가장 빠르다.
- 빌드 도구와 배포 주기가 앱과 달라 저장소를 나누면 설정·CI 가 단순해진다.
- OpenAI 는 사용자가 골랐다. 생성 호출부를 인터페이스 뒤에 두어 바꿀 수 있게 한다.

## 대안
- Supabase — 서버 코드는 줄지만 해석 생성 로직이 서버 함수로 흩어진다.
- NestJS — 앱과 타입을 공유할 수 있지만 공유할 것이 적고 주력 스택이 아니다.
- 앱과 한 저장소(모노레포) — API 계약 변경이 한 커밋에 묶이지만 사용자가 저장소 분리를 택했다.

## 결과·재검토 조건
- API 계약을 바꾸면 두 저장소에 각각 커밋한다. 계약의 원본은 docs/architecture.md 의 표다. 어긋남이 반복되면 OpenAPI 명세 생성을 다시 본다.
- Spring Boot 버전은 계획 태스크 1 에서 확정해 여기에 적는다. [확인 필요]
- 운영 호스팅은 미정이다. [확인 필요]
