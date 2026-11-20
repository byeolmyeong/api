---
# 아래 경로의 파일을 Read/Edit/Write 할 때만 로드된다.
paths:
  - "src/main/resources/db/migration/**"
  - "src/main/kotlin/**/*Service.kt"
  - "src/main/kotlin/**/*Config.kt"
  - "src/main/kotlin/**/*Job*.kt"
---
# 배포 중 두 서버가 함께 뜬다 (결정 046)

배포 · `.env` 바꾸기는 블루 · 그린이다. 새 jar 가 뜨는 동안과 옛 jar 가 일을 끝내는 동안(보통 1분, 최대 약 8분) 두 서버가 같은 DB 를 쓴다.

- 마이그레이션은 옛 jar 를 깨지 않게 쓴다. 열 · 표를 지우거나 이름을 바꾸는 일, 기존 열에 NOT NULL 을 더하는 일은 두 번의 배포로 나눈다(먼저 새 jar 가 옛 것을 안 쓰게 → 다음 배포에서 지운다). 새 열은 NULL 허용이거나 기본값을 둔다.
- 두 번 돌면 안 되는 `@Scheduled`(미리 만들기 · 푸시 보내기 등)는 `pg_try_advisory_lock` 으로 한 서버만 돌게 한다. 박동처럼 서버마다 제 것만 하는 일은 그대로 둔다.
- 뒤에서 하는 생성은 `reportExecutor` 에 맡긴다. 서버를 닫을 때 그 일꾼의 일만 끝까지 기다린다(`ReportExecutorDrain`). 다른 스레드 · 풀에서 시작한 긴 일은 배포 때 끊긴다.
- 서버 메모리의 상태(요청 속도 제한 · 생성 한도 · 캐시)는 바꿀 때 비워진다. 꼭 남아야 하는 상태는 DB 에 둔다.
