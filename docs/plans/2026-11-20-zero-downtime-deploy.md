---
type: plan
status: approved
tags: [무중단 배포, 블루그린, 배포, Caddy, 운영]
last_reviewed: 2026-11-20
---
# 서버 배포 · 설정 바꾸기를 끊김 없이 한다 (블루 · 그린)

> 상태: 승인(소유자 2026-11-20 "새 세션에 맡기자") · 기준 2026-11-20 · 서버 develop `8c1d8a2` · 소유자 "운영서버는 끊기면 안 되는데 무중단 배포해야 해"(2026-11-20) · 맡은 곳: 새 세션(무중단 배포 전용) · 서버 결정 번호 046 을 쓴다(045 는 약관 버전, 047~ 는 fa 세션)

## 목표
- 무엇을: 운영 · 스테이징 서버에 새 jar 를 올리거나 `.env` 를 바꿀 때 요청이 끊기지 않게 한다.
- 누구를 위해: 앱 · 웹 · 어드민을 쓰는 사람(배포 중 오류 화면 · 실패한 요청 없음), 운영하는 소유자(아무 때나 배포).
- 끝나면: 배포 중 0.1초마다 요청을 보내도 실패가 0이고, 뒤에서 쓰던 리포트 · 풀이가 배포 때문에 멈추지 않는다.

## 지금 (끊기는 까닭)
- `deploy.yml` 의 "바꿔 띄우기" 가 `docker compose up -d --force-recreate --no-deps api` — 옛 컨테이너를 내리고 새것을 띄운다. 새 JVM 이 뜨는 동안(VM 1GB · 1 OCPU 에서 30~60초) Caddy 가 502 를 준다.
- 같은 단계의 `docker compose pull` + `up -d --remove-orphans` 는 Postgres · Caddy 이미지에 새 패치가 있으면 그것들도 다시 만든다(DB 재시작 = 전부 끊김).
- 운영 `.env` 를 바꿀 때도 `--force-recreate api` 로 같은 끊김이 있다(2026-11-20 첫 관리자 만들 때 두 번, 각 1분 안팎 — 실사용자 없을 때).
- 뒤에서 쓰는 생성(리포트 · 나 탭 · 시기 · 궁합)은 서버 메모리의 일감이라, 서버가 꺼지면 그 행의 심장 박동이 멈추고 "멈춘 생성" 으로 보인다(`ReportService.heartbeat`).

## 결정된 전제 (링크만)
- 서버 결정 020(오라클 VM 한 대에 Compose 셋 · `develop` 스테이징 · `main` 운영) · 022(배포 환경) · `docs/deploy.md` · 마이그레이션은 앞으로만 호환(deploy.md "DB 까지 되돌려야 할 때").
- VM: `VM.Standard.E2.1.Micro` 1GB + 스왑 2GB. 2026-11-20 운영 측정: api 214MiB · Postgres 44MiB · Caddy 17MiB, 쓸 수 있는 메모리 약 460MiB → JVM 둘이 잠깐 같이 떠도 들어간다.

## 내 결정 (묻지 않고 고른 기본값)
- **블루 · 그린**: 서버 컨테이너를 `api-blue` · `api-green` 둘로 두고 하나만 받게 한다. 새 jar 는 쉬는 쪽에 띄우고, 살아나면 Caddy 가 받는 쪽을 바꾸고, 옛쪽은 하던 일을 끝낸 뒤 내린다.
- **받는 쪽 바꾸기 = Caddy 설정 다시 읽기**(`caddy reload`) — 다시 읽기는 연결을 끊지 않는다. 받는 쪽은 `deploy/upstream.caddy` 한 줄(가져오기)로 둔다.
- **옛쪽 내리기 = 차분한 종료**: Spring `server.shutdown=graceful` · 단계당 30초, 생성 일꾼은 종료 때 하던 일을 끝까지(`setWaitForTasksToCompleteOnShutdown(true)` · 최대 180초), Compose `stop_grace_period: 200s`. 받는 쪽을 바꾼 뒤에 내리므로 새 요청은 오지 않는다.
- **앱 배포는 서버 컨테이너만 만진다**: Postgres · Caddy 이미지는 태그를 고정하고 배포 때 받지도 다시 만들지도 않는다. 둘의 업데이트는 따로, 점검 모드를 켜고(앱 결정 060) 한다.
- **JVM 메모리 상한**: `JAVA_TOOL_OPTIONS=-Xmx320m -XX:+ExitOnOutOfMemoryError` — 둘이 겹쳐도 VM 이 스왑에 깔리지 않게.
- 스테이징도 같은 방식(운영 전에 스테이징에서 늘 한 번 해 본다).

## 범위 밖
- Postgres 무중단(복제 · 장애 넘기기) — VM 한 대 · 무료 범위라 하지 않는다. DB 재시작이 필요한 일은 점검 모드로.
- VM 두 대 · 로드 밸런서. 쿠버네티스.
- 앱 웹 · 어드민(버셀은 이미 무중단).

## 전역 제약
- 서버: Kotlin · Spring Boot · `deploy/` 의 셸은 `set -euo pipefail` · 비밀값은 `.env` 에만 · 로그는 `event=` 꼴.
- 마이그레이션은 옛 jar 와 새 jar 가 잠깐 같은 DB 를 함께 쓴다는 전제로 쓴다(열 · 표 지우기 · 이름 바꾸기는 두 번의 배포로).
- 작업 브랜치 → PR(develop) → CI → 합침 → 스테이징에서 검증 → `main` 은 소유자 확인.

## 태스크

### 1. decision-046 · 상태: 완료
- 읽을 것: 이 계획, 서버 결정 020 · 022, `deploy/compose.yaml` · `deploy/Caddyfile` · `.github/workflows/deploy.yml`
- 할 것: 서버 결정 046 "배포 · 설정 바꾸기는 블루 · 그린으로 끊김 없이" — 위 "내 결정" 여섯 줄 + 마이그레이션 규칙. 020 앞머리에 "배포 방식은 046" 표시.
- 검증: 파일 하나 · 020 앞머리.

### 2. graceful-shutdown · 상태: 완료
- 읽을 것: `application.properties`, `report/ReportService.kt`(일꾼 · 심장 박동) · `MeReportService.kt` · 시기 · `synastry/SynastryService.kt` 의 일꾼, `@Scheduled` 모두
- 할 것: `server.shutdown=graceful` · `spring.lifecycle.timeout-per-shutdown-phase=30s`. 생성 일꾼(`ThreadPoolTaskExecutor`) 모두 `setWaitForTasksToCompleteOnShutdown(true)` · `setAwaitTerminationSeconds(180)`. 끝낼 때 일꾼이 기다리는 동안에도 심장 박동이 계속 적히게(박동 일정이 먼저 멈추지 않게 — 필요하면 박동을 일꾼 종료 뒤로). 두 서버가 잠깐 같이 떠 있어도 겹치면 안 되는 `@Scheduled` 가 있는지 보고, 있으면 DB 잠금(`pg_try_advisory_lock`)으로 하나만 돌게.
- 하지 말 것: 생성 중인 일을 끊고 다시 시작하게 만들지 않는다.
- 검증: 통합 테스트 — 생성 도중 컨텍스트를 닫으면 끝까지 쓰고 저장한 뒤 닫힌다 · 닫히는 동안 새 요청은 받지 않는다. `./gradlew build`.

### 3. compose-blue-green · 상태: 완료
- 읽을 것: 태스크 1, `deploy/compose.yaml` · `deploy/Caddyfile`
- 할 것: `api` → `api-blue` · `api-green`(같은 설정, jar 는 `./app-blue.jar` · `./app-green.jar`, `stop_grace_period: 200s`, `JAVA_TOOL_OPTIONS`, 상태 검사는 Caddy 가 함). Caddyfile 은 `import upstream.caddy` 로 받는 쪽만 가져온다 — `upstream.caddy` 예: `reverse_proxy api-green:8080 { health_uri /health  health_interval 2s  lb_try_duration 15s }`. Postgres · Caddy 이미지 태그를 고정(`postgres:17.x` · `caddy:2.x` 지금 쓰는 패치).
- 주는 것: 서비스 이름 `api-blue` · `api-green`, 파일 `upstream.caddy` · `active_color`(지금 받는 색).
- 검증: 로컬에서 `docker compose config` 통과 · 두 색을 번갈아 띄워 Caddy 가 붙는지.

### 4. swap-script · 상태: 완료(스테이징 검증 전)
- 읽을 것: 태스크 3
- 할 것: `deploy/swap.sh [jar 경로 | --restart]` — ① 지금 색(`active_color`, 없으면 지금 떠 있는 것)과 쉬는 색을 정한다 ② 새 jar 를 쉬는 색 파일로(`--restart` 면 지금 jar 그대로 — `.env` 만 바꿨을 때) ③ `docker compose up -d api-<쉬는 색>` ④ Caddy 컨테이너 안에서 `wget -qO- http://api-<쉬는 색>:8080/health` 가 200 일 때까지(최대 3분, 아니면 쉬는 색을 내리고 실패로 끝 — 받는 쪽은 그대로) ⑤ `upstream.caddy` 를 쉬는 색으로 쓰고 `caddy reload` ⑥ 5초 뒤 `docker compose stop api-<옛 색>`(차분한 종료, 최대 200초) ⑦ `active_color` 기록 · 새 jar 를 `app.jar.good` 으로. 모든 단계 `event=swap_*` 로그.
- 하지 말 것: 받는 쪽을 바꾸기 전에 옛 색을 내리지 않는다. Postgres · Caddy 를 다시 만들지 않는다.
- 검증: 스테이징 VM 에서 0.1초마다 `/health` 와 가벼운 공개 경로(`GET /app-config`)를 부르며 `swap.sh` 두 번(새 jar · `--restart`) → 실패 0(로그로 센 표 첨부).

### 5. workflow-and-runbook · 상태: 완료(스테이징 검증 전)
- 읽을 것: 태스크 4, `.github/workflows/deploy.yml` · `docs/deploy.md` · `deploy/bootstrap-vm.sh`
- 할 것: "바꿔 띄우기" 를 `swap.sh /opt/byeolmyeong/app.jar.new` 로. `docker compose pull` · `up -d --remove-orphans` 를 앱 배포에서 뺀다. 되돌리기 = `swap.sh app.jar.good`(이것도 끊김 없이). 처음 한 번 옮겨 가기(지금 `api` → `api-blue`)는 끊김이 한 번 있으므로 점검 모드를 켜고 하는 손 절차를 `docs/deploy.md` 에. 운영 절차표의 "`.env` 를 바꾼 뒤" 를 `./swap.sh --restart` 로, "Postgres · Caddy 업데이트" 줄을 새로(점검 모드 → pull → up → 끔). `docs/architecture.md` 의 배포 줄.
- 검증: 스테이징 배포 한 번(Actions) 동안 태스크 4 의 요청 고리 실패 0 · 운영은 소유자가 `main` 에 합칠 때.

## 검증 (전체 완료 판정)
- 스테이징: 배포(Actions) · `swap.sh --restart` · 되돌리기 세 경우 모두 0.1초 요청 고리 실패 0, 그 사이 시작한 리포트 생성 하나가 `DONE` 으로 끝남.
- 운영: 첫 옮겨 가기(점검 모드, 소유자 확인) 뒤 다음 배포부터 실패 0.

## 리뷰 초점
- 마이그레이션이 옛 jar 를 깨는 경우(열 지우기 · NOT NULL 더하기) — 태스크 1 의 규칙과 PR 검토 항목으로.
- 두 서버가 겹친 몇 분 동안 같은 일을 두 번 하는 예약 작업(오늘의 하늘 미리 만들기 · 푸시 보내기 — S⑩ 은 이미 DB 잠금 예정) — 태스크 2.
- 메모리: 새 JVM 이 뜨는 동안 옛 JVM 이 스왑에 밀려 느려지는지 — 태스크 4 검증에서 응답 시간도 본다.
- 주소별 요청 제한 · 관리자 로그인 제한의 메모리 상태가 바뀔 때 비워지는 것(지금도 재시작 때 비워짐 — 그대로 둔다).
- Caddy 다시 읽기가 실패하면(설정 오류) 받는 쪽이 그대로인지 — 태스크 4 에서 일부러 틀린 설정으로 한 번.

## 자동 반복
- 태스크 4 · 5 의 "실패 0" 이 안 되면 원인을 고쳐 다시, 상한 3회. 사람 없이 돌리지 않는다(VM 을 만진다).

## 위험 · 되돌리기
- 처음 옮겨 가기에서 한 번 끊긴다 → 점검 모드를 켜고, 사용자가 적은 시간에.
- `swap.sh` 가 중간에 멈추면 받는 쪽은 옛 색 그대로(바꾸기 전에 확인하므로). 되돌리기는 지금 방식(`app.jar.good`)을 그대로 둔다.
- VM 메모리가 모자라면 JVM 상한을 낮추거나 스왑을 늘린다.

## 실행 기록
- 2026-11-20 (무중단 배포 세션) 태스크 1~5 를 브랜치 `feat/zero-downtime-deploy` 에서 만들었다. 계획과 다르게 고른 것(결정 046 에 근거):
  - JVM 힙 상한 `-Xmx256m`(계획 320m) — 1GB VM 에서 JVM 이 스스로 고르던 값과 같아 지금 동작을 그대로 두고, 겹칠 때 더 커지지 않게.
  - 웹 단계 `timeout-per-shutdown-phase=120s`(계획 30초) · `stop_grace_period: 320s`(계획 200s) — 요약 해석 · 직접 입력 궁합 · 방향성은 요청 안에서 모델을 부른다(한 번 최대 120초). 30초면 옛 색에 걸린 그 요청이 끊긴다. 320 = 120 + 180 + 여유.
  - 생성 기다리기는 일꾼 스스로의 종료(빈을 없앨 때)가 아니라 그 앞의 수명 단계(`ReportExecutorDrain`, 웹 서버 차분한 종료 바로 다음)에서 한다. 빈을 없앨 때는 서비스의 박동 일정이 이미 취소돼 있다. 박동 일정이 닫기 시작할 때 먼저 멈추지 않게 `spring.task.scheduling.shutdown.await-termination=true`. Boot 4.1 은 `server.shutdown` 기본값이 이미 graceful 이지만 적어 둔다.
  - swap.sh 는 지금 색을 `active_color` 가 아니라 Caddy 관리 API 의 살아 있는 설정으로 정하고, 바꾼 뒤에도 그것으로 확인한다. Caddy 가 쓰는 색인지 모르면 아무 색도 내리지 않는다.
  - 배포 워크플로는 옮기지 않은 VM 에는 아무것도 올리지 않는다. 결정 046 앞의 커밋을 올리면 jar 만 바꾼다. 처음 옮겨 가기에 Caddy 를 다시 만들어야 해서(새 마운트) 몇 초 끊긴다.
  - `@Scheduled` 중 두 서버가 겹치면 안 되는 것은 지금 develop 에 없다(박동 셋뿐). 규칙만 결정 046 의 5 에 적었다(오늘의 하늘 · 푸시는 그 계획에서 DB 잠금).
- 검증(로컬): 통합 테스트 `GracefulShutdownIntegrationTest` — 고치기 전 코드에서 "닫는 동안 살아 있다는 표시가 멈췄다" 로 실패, 고친 뒤 통과(로그 순서: 웹 서버 차분한 종료 → `report_drain_started active=5` → `report_done` → `report_drain_done`). shellcheck 경고 0.
- 리허설(로컬 Docker, VM 과 같은 배치 · 리눅스 컨테이너에서 swap.sh, 0.1초 요청 고리 — `/health` 0.1초 · `/app-config` 0.6초, 요청마다 새 연결):

  | 경우 | 요청 | 실패 | 비고 |
  |---|---|---|---|
  | 옛 방식에서 옮겨 가기(문서 1~7) | 383 | 10 | Caddy 를 다시 만드는 순간 약 1.5초(예상한 한 번 끊김) |
  | `swap.sh --restart` | 398 | 0 | 새 색 25초 만에 살아남 |
  | `swap.sh app.jar.new` | 502 | 0 | |
  | 다시 읽기 실패(Caddyfile 일부러 틀림) | 419 | 0 | `swap_failed step=reload`, 받는 쪽 그대로 · 새 색 내림 |
  | 뜨지 않는 jar | 1415 | 0 | 3분 뒤 `swap_failed step=health`, 받는 쪽 그대로 |
  | 새 VM 에 처음 띄우기(문서) | — | — | `swap_done active=green` |

  리허설에서 찾은 것: 컨테이너 안의 `localhost` 가 `::1` 로 풀려 Caddy 관리 API(127.0.0.1:2019 에만 듣는다)에 닿지 않았다 → `127.0.0.1` 로 고침.
- 독립 리뷰(reviewer, 깨끗한 컨텍스트) 반영 — 차단 0 · 수정 5 · 제안 10, 모두 코드로 확인 뒤 고침:
  - swap.sh: 다시 읽기가 실패로 끝나도 Caddy 가 실제로 쓰는 색을 다시 읽고 정한다(적용된 뒤 exec 만 실패하면 새 색을 내려 전부 502 가 될 수 있었다). 되돌리기는 `app.jar.prev`(받는 jar 가 바뀔 때만 밀려남) — `app.jar.good` 은 늘 지금 jar 라 되돌리기가 아니었다. 받는 쪽 파일은 `swap.sh --init` 한 곳에서만 쓴다.
  - 생성 기다리기를 `ReportExecutorDrain` 한 곳으로(일꾼 빈의 기다림 시간을 없앰) — 180초를 넘기면 박동 없이 한 번 더 기다렸다.
  - 워크플로: SSH keepalive, swap.sh 는 다른 이름으로 올려 `mv`, 서버 이미지(JRE)는 배포 때 받는다(실패해도 계속).
  - 문서: 두 서버가 겹치는 동안의 규칙을 `.claude/rules/two-servers-overlap.md` · architecture.md 데이터 절에, 020 의 대체 범위(1 · 2 · 4번)를 바로, 옮겨 가기에서 Caddyfile 은 Caddy 를 다시 만들기 직전에 올린다, 옮기기 전에 합쳐진 배포는 수동 실행으로 다시.
  - 가짜 docker 로 swap.sh 경우 8가지(처음 · 배포 · --restart · 뜨지 않는 jar · 다시 읽기는 적용됐는데 exec 실패 · 다시 읽기 실패 · prev 되돌리기 · --init 두 번)를 돌려 받는 쪽 · jar 기록이 맞는지 봤다.
