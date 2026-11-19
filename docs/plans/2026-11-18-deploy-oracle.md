---
type: plan
status: approved
tags: [배포, 오라클 클라우드, 스테이징, 백업]
last_reviewed: 2026-11-18
---
# 서버를 오라클 무료 VM 두 대(스테이징·운영)에 올린다

> 상태: 승인(2026-11-18, 태스크 1~6 먼저) · 기준 2026-11-18

## 목표
- 무엇을: 오라클 클라우드 Always Free ARM VM 두 대(스테이징·운영)에 서버(jar)·PostgreSQL 17·Caddy(HTTPS)를 Docker Compose 로 띄우고, 운영 DB 를 매일 백업하고, GitHub Actions 로 배포한다(main 에 합쳐지고 CI 통과 → 스테이징, `v*` 태그 → 운영).
- 누구를 위해: 혼자 운영하는 소유자. 버셀에 올릴 앱 웹(Expo)과 관리 화면(Vite)이 이 서버를 부른다.
- 끝나면: `https://api-staging.byeolmyeong.com/health` 와 `https://api.byeolmyeong.com/health` 가 200 을 돌려주고, 결정 001 의 "운영 호스팅은 미정이다. [확인 필요]" 가 결정 020 으로 닫힌다.

## 결정된 전제
- 스택·버전(Spring Boot 4.1.2 · JVM 21 · PostgreSQL 17): docs/decisions/001-spring-boot-postgres.md
- 글 생성 공급자·설정: docs/decisions/006-reading-provider-claude.md, `src/main/resources/application.properties`
- 개발용 로그인: docs/decisions/005-dev-auth-first.md
- 환경 변수 이름: `.env.example`
- 규칙: docs/golden-rules.md (비밀값은 이름만, 보호 브랜치에 직접 쓰지 않음, 배포는 사람이 한 번 확인)
- CI 통과 뒤에만 합친다(GitHub 무료 요금제라 규칙으로 막을 수 없다).

## 인터뷰 결과 (2026-11-18)
| 질문 | 답 | 영향 |
|---|---|---|
| 서버 https 주소 | 이미 가진 도메인 `byeolmyeong.com` 을 쓴다. 서브도메인은 `staging` | 운영 `api.byeolmyeong.com`, 스테이징 `api-staging.byeolmyeong.com`. 프론트는 `byeolmyeong.com`·`admin.byeolmyeong.com` / `staging.byeolmyeong.com`·`admin-staging.byeolmyeong.com` |
| "배포 전 확인" 3건(계정별 생성 속도 제한·개발용 로그인 안전장치·세션 만료) | 별도 계획으로 먼저 한다. 배포 준비는 병행 | 이 계획에 넣지 않는다. 태스크 8·9(첫 배포)는 그 계획이 끝나야 시작한다 |
| 스테이징의 개발용 로그인 | 켠다. 스테이징 전용 API 키에 지출 한도, 싼 모델 | 스테이징 `.env`: `AUTH_DEV_ENABLED=true`, `CLAUDE_MODEL=claude-sonnet-5-5`. 운영은 켜진 채 뜨지 못하게 하는 것이 별도 계획의 일이다 |
| VM 생성·방화벽 | 소유자가 콘솔에서 VM 을 만들고, 나머지는 Claude 가 SSH 로 | 오라클 API 키를 이 맥에 두지 않는다. 콘솔 작업은 docs/deploy.md 의 순서대로 소유자가 한다 |

## 내 결정 (묻지 않고 고른 기본값)
- 스테이징 주소는 한 단계 서브도메인에 하이픈을 붙인다(`api-staging`·`admin-staging`, 앱 웹은 `staging`). `api.staging.…` 처럼 두 단계로 하지 않는다. 이유: `*.byeolmyeong.com` 와일드카드 인증서와 Cloudflare 무료 프록시의 기본 인증서가 한 단계까지만 덮는다. 틀리면 비용: DNS 레코드·`SITE_ADDRESS`·버셀 도메인 이름 변경.
- 스테이징 모델은 `claude-sonnet-5-5`(운영의 `claude-opus-5-5` 보다 싸다. 요금은 Anthropic 가격표가 단일 출처, 결정 006). 지금 코드가 보내는 `output_config.effort`·JSON 출력 형식·`fallbacks: "default"` 를 그대로 받는다. `claude-haiku-5-5` 는 서버 쪽 거절 대체(fallbacks)를 받지 않아 `BYEOLMYEONG_CLAUDE_REFUSAL_FALLBACK=false` 가 더 필요하고 글 검사 통과율을 재 보지 않았다 → 나중에. 틀리면 비용: 스테이징 `.env` 한 줄.
- 배포물은 jar 하나다. Actions 가 `bootJar` 로 만들어 VM 에 복사하고, VM 은 공식 `eclipse-temurin:21-jre` 이미지로 실행한다. 이미지 저장소(GHCR)를 쓰지 않는다. 이유: jar 는 CPU 종류(ARM/x86)를 가리지 않고, 비공개 저장소의 GHCR 무료 용량이 작다. 틀리면 비용: compose 의 서버 서비스 하나.
- jar 이름을 `build.gradle.kts` 에서 `app.jar` 로 고정한다(`-plain.jar` 와 헷갈리지 않게).
- SSH·복사는 러너에 있는 `ssh`/`scp` 로 한다. 서드파티 배포 액션을 쓰지 않는다. 호스트 키는 known_hosts 비밀값으로 검사한다(검사 끄기 금지).
- 운영 배포(`v*` 태그)는 그 커밋으로 `./gradlew build`(테스트 포함)를 다시 돌린 뒤에만 올린다. 스테이징은 CI 가 이미 통과한 커밋이라 `bootJar` 만 한다.
- 되돌리기: VM 에 직전 jar(`app.jar.prev`)를 남기고, 배포 뒤 `/health` 가 제한 시간 안에 200 이 아니면 직전 jar 로 되돌리고 작업을 실패시킨다. 더 오래된 버전은 수동 실행(태그 지정)으로 다시 배포한다.
- VM: Ubuntu 24.04 aarch64, `VM.Standard.A1.Flex`. 크기는 콘솔에 보이는 실제 무료 한도를 반으로 나눈다(2 OCPU·12GB 면 1 OCPU·6GB 씩).
- Docker 는 Ubuntu 저장소의 `docker.io`·`docker-compose-v2` 로 설치한다(원격 설치 스크립트를 파이프로 실행하지 않는다).
- PostgreSQL 은 호스트 포트를 열지 않는다(compose 내부망에서만 서버가 접속). 밖으로 열리는 포트는 22·80·443 뿐이다.
- 이미지 태그는 메이저까지만 고정한다(`postgres:17`, `eclipse-temurin:21-jre`, `caddy:2`). 보안 패치를 받기 위해서다. 결정 001 의 `postgres:17` 과 같은 수준이다.
- 도커 로그는 컨테이너마다 10MB × 5개로 돌린다(디스크가 차지 않게).
- 백업: 운영만, 매일 04:00(KST) `pg_dump` 사용자 정의 형식 → VM 에 7개 보관 + 오라클 Object Storage 버킷(쓰기 전용 사전 인증 URL)에 올리고 버킷 수명 규칙으로 30일 뒤 지운다. 스테이징 DB 는 백업하지 않는다(버려도 되는 데이터).
- 복원 연습은 운영 VM 안의 일회용 컨테이너에서 한다. 운영 데이터(출생 정보)를 스테이징으로 옮기지 않는다.
- VM 의 `.env`(비밀값)는 소유자가 VM 에서 직접 채운다. Claude 는 값 없이 이름만 있는 틀을 올린다. 이유: 비밀값을 대화·로그에 남기지 않는다(`.claude/settings.json` 의 `Read(.env)` deny 와 같은 규칙).
- 서버 컨테이너의 작업 디렉터리에는 `app.jar` 만 둔다. `application.properties` 가 작업 디렉터리의 `.env` 를 읽으므로, 값은 compose 의 `env_file` 로만 넘긴다.
- GitHub 비밀값은 저장소 단위로 둔다(무료 요금제 비공개 저장소는 환경별 비밀값·승인 규칙을 못 쓴다). 운영 배포의 "사람이 한 번 확인"은 태그를 다는 행위로 한다.

## 범위 밖
- "배포 전 확인" 3건: 별도 계획 `docs/plans/2026-11-19-pre-deploy-hardening.md`(이 계획 승인 뒤 작성).
- 버셀 프로젝트 설정(앱·관리 화면 저장소의 일). 이 계획은 서버가 허용할 주소(`ALLOWED_ORIGINS`·`ADMIN_ALLOWED_ORIGINS`)만 정한다.
- 가동 감시·알림(백업 실패 알림 포함), 무중단 배포, 서버 여러 대, 관리형 DB, 소셜 로그인.

## 전역 제약
- JVM 21 · PostgreSQL 17 · Caddy 2 · Ubuntu 24.04 aarch64.
- 환경 이름은 `staging`·`prod` 두 개다. GitHub 비밀값·변수 이름은 `STAGING_*`·`PROD_*`.
- 저장소에 도메인 외의 IP·비밀값을 적지 않는다. 호스트·키·URL 은 GitHub 비밀값과 VM 의 `.env` 에만 둔다.
- VM 의 배포 디렉터리는 `/opt/byeolmyeong` 이다. 그 안: `compose.yaml`·`Caddyfile`·`initdb/`·`backup.sh`·`restore.sh`·`.env`·`backup.env`(운영)·`app.jar`·`app.jar.good`·`backups/`. (태스크 10 에서 `app.jar.prev` → `app.jar.good`, `initdb/`·`backup.env` 추가)
- 커밋: 한국어 + Conventional Commits, 한 태스크 = 한 커밋, 작업 브랜치 `chore/deploy-oracle`.
- 스크립트는 `set -euo pipefail` 로 시작하고, 실패하면 0 이 아닌 값으로 끝나며 `event=<이름>` 한 줄을 남긴다.

## 태스크

### 1. adr-020-hosting · 상태: 완료
- 읽을 것: docs/decisions/001-spring-boot-postgres.md, docs/decisions/019-delete-synastry.md(형식), 이 계획의 "인터뷰 결과"·"내 결정"
- 할 것: `docs/decisions/020-hosting-oracle-vercel.md`. 맥락(무료·혼자 운영·JVM 은 늘 켜진 서버가 필요: 리포트 일꾼·속도 제한이 서버 메모리), 결정(오라클 VM 두 대·compose 구성·도메인 표·배포 흐름·백업·스테이징 설정·버셀), 대안과 버린 이유(Render 무료는 15분 뒤 잠듦, GCP e2-micro 는 1GB·미국만, Northflank 는 운영 금지, Koyeb 는 미국·유럽·신규 가입 제한, AWS 는 6개월), 재검토 조건(오라클이 VM 회수·한도 변경, 결제 시작 → 버셀 Hobby 는 비상업용만이라 Pro 또는 Cloudflare Pages, 동시 리포트가 늘어 1 OCPU 가 모자람, 복원 연습 실패). 결정 001 은 front matter 의 `superseded_by` 에 "020 (운영 호스팅만)" 만 덧붙인다.
- 주는 것: 결정 020 경로. 뒤 태스크 문서가 이 번호를 가리킨다.
- 하지 말 것: 결정 001 본문 고쳐 쓰기. 이유: 기존 ADR 은 대체 표시만 한다(golden rules).
- 검증: `grep -n "020" docs/decisions/001-spring-boot-postgres.md` → front matter 한 줄. `grep -c "staging" docs/decisions/020-hosting-oracle-vercel.md` → 1 이상.
- 실행 패턴: 혼자
- 결과 요약: 결정 020(`docs/decisions/020-hosting-oracle-vercel.md`)을 쓰고 결정 001 front matter 에 대체 표시. 무료 한도·요금의 단일 출처는 결정 020 의 "무료 한도·요금" 절. 커밋 66d267c.

### 2. deploy-compose · 상태: 완료
- 읽을 것: `compose.yaml`(로컬 DB), `src/main/resources/application.properties`, `.env.example`, 결정 020
- 할 것:
  - `deploy/compose.yaml`: 서비스 `postgres`(`postgres:17`, 이름 있는 볼륨, `pg_isready` 상태 검사, 호스트 포트 없음) · `api`(`eclipse-temurin:21-jre`, `./app.jar` 를 읽기 전용으로 `/app/app.jar` 에, 작업 디렉터리 `/app`, `env_file: .env`, `postgres` 가 건강해진 뒤 시작, 호스트 포트 없음) · `caddy`(`caddy:2`, 80·443, `./Caddyfile`, 인증서 볼륨). 셋 다 `restart: unless-stopped`, 로그 10MB × 5.
  - `deploy/Caddyfile`: 주소는 환경 변수 `SITE_ADDRESS` 에서 읽고 `api:8080` 으로 넘긴다.
  - `deploy/.env.example`: 이름만. 배포에만 쓰는 것(`SITE_ADDRESS`·`BACKUP_UPLOAD_URL`)과 "루트 `.env.example` 의 이름 전부"를 가리키는 주석, 환경별 값의 모양(스테이징·운영 `SITE_ADDRESS`, `DATABASE_URL=jdbc:postgresql://postgres:5432/byeolmyeong`, 스테이징 `AUTH_DEV_ENABLED=true`·`CLAUDE_MODEL=claude-sonnet-5-5`, 운영은 `AUTH_DEV_ENABLED` 를 적지 않음, `ALLOWED_ORIGINS`·`ADMIN_ALLOWED_ORIGINS` 의 버셀 주소).
  - `build.gradle.kts`: `bootJar` 결과 이름을 `app.jar` 로.
- 주는 것: `deploy/compose.yaml` 서비스 이름 `api`·`postgres`·`caddy`, DB 이름 `byeolmyeong`, 볼륨 이름. 태스크 3~5 가 쓴다.
- 하지 말 것: 루트 `compose.yaml`(로컬 개발용) 바꾸기. 이유: 로컬 흐름은 그대로 둔다. Postgres 포트 열기. 이유: DB 가 인터넷에 노출된다.
- 검증(이 맥, arm64 = VM 과 같은 CPU): `./gradlew bootJar` → `build/libs/app.jar`. scratchpad 에 jar·compose·Caddyfile·가짜 `.env`(`SITE_ADDRESS=localhost`, 임시 DB 계정)를 두고 `docker compose -p byeolmyeong-deploy-check up -d` → `curl -sk https://localhost/health` → 200. `docker compose logs api` 에 Flyway 가 마지막 마이그레이션까지 적용한 줄. `docker compose port postgres 5432` → 출력 없음. 확인 뒤 `down -v`.
- 실행 패턴: 혼자
- 결과 요약: `deploy/compose.yaml`·`Caddyfile`·`.env.example`, jar 이름 `app.jar`. 계획 밖으로 `SERVER_FORWARD_HEADERS_STRATEGY=native` 를 더함(실행 기록의 결정). 커밋 5cda6a0.

### 3. backup-restore · 상태: 완료
- 읽을 것: `deploy/compose.yaml`(태스크 2), 결정 020 의 백업 절
- 할 것:
  - `deploy/backup.sh`: `/opt/byeolmyeong` 에서 `postgres` 컨테이너로 `pg_dump -Fc` → `backups/byeolmyeong-<UTC 시각>.dump`, 7개 넘는 오래된 파일 삭제, `BACKUP_UPLOAD_URL`(사전 인증 URL, `.env` 에서 읽음)에 파일 이름을 붙여 HTTP PUT. 성공·실패마다 `event=backup_done|backup_failed` 한 줄(파일 이름·크기·단계, URL 은 남기지 않음).
  - `deploy/restore.sh <덤프 파일> [대상]`: 기본 대상은 일회용 컨테이너(`postgres:17`)에 복원하고 표마다 행 수를 출력한 뒤 컨테이너를 지운다. 실제 DB 에 덮어쓰기는 `--into-live` 를 줄 때만 하고, 그때는 `api` 를 멈추고 확인 문구 입력을 받는다.
- 주는 것: 두 스크립트 경로와 인자. 태스크 4(cron 등록)·9(복원 연습)가 쓴다.
- 하지 말 것: 백업 URL·DB 비밀번호를 로그에 남기기. 이유: 비밀값이다. 확인 없이 실제 DB 덮어쓰기. 이유: 되돌릴 수 없다.
- 검증(이 맥): 태스크 2 의 확인용 compose 에 행을 조금 넣고 `backup.sh`(업로드 URL 을 로컬에서 PUT 을 받는 임시 서버로) → 덤프 생성·업로드 200·`event=backup_done`. `restore.sh <덤프>` → 표별 행 수가 원본 DB 의 `select count(*)` 와 같다. 틀린 업로드 URL → 종료 코드 ≠ 0, `event=backup_failed`. 8번 돌려 로컬 파일이 7개.
- 실행 패턴: 혼자
- 결과 요약: `deploy/backup.sh`(event=backup_done|backup_failed)·`deploy/restore.sh <덤프> [--into-live]`. 로컬에서 업로드·일회용 복원 행 수 일치·실패 경로·7개 보관·실제 DB 복원까지 확인. 커밋 2119332(+ shellcheck 반영은 61474ce).

### 4. vm-bootstrap · 상태: 완료
- 읽을 것: `deploy/`(태스크 2·3), 결정 020
- 할 것: `deploy/bootstrap-vm.sh <staging|prod>`, VM 에서 `sudo` 로 한 번 돌린다. 여러 번 돌려도 결과가 같아야 한다(멱등). 하는 일: `docker.io`·`docker-compose-v2` 설치와 부팅 시 시작, `ubuntu` 사용자를 docker 그룹에, 오라클 Ubuntu 이미지의 iptables 에 80·443 허용 추가와 영구 저장(`netfilter-persistent`), `/opt/byeolmyeong`·`backups/` 생성(소유자 `ubuntu`), 시간대 `Asia/Seoul`, 자동 보안 업데이트 켜짐 확인. `prod` 일 때만 `backup.sh` 를 매일 04:00 에 cron 등록(출력은 `backups/backup.log`).
- 주는 것: 부트스트랩 뒤 VM 상태(디렉터리·cron·포트). 태스크 7 이 VM 에 실행한다.
- 하지 말 것: SSH 설정을 바꾸기(비밀번호 로그인은 오라클 이미지가 이미 끈다). 이유: 잘못 바꾸면 VM 에 못 들어간다. 원격 설치 스크립트를 파이프로 실행. 이유: 내용을 보지 않은 코드를 root 로 돈다.
- 검증(이 맥): `docker run --rm -v "$PWD/deploy:/d:ro" koalaman/shellcheck:stable /d/*.sh` → 지적 0. 실제 실행 검증은 태스크 7.
- 실행 패턴: 혼자
- 결과 요약: `deploy/bootstrap-vm.sh <staging|prod>`. 방화벽을 Docker 설치 전에 열고 바뀔 때만 저장. shellcheck 지적 0. 실제 VM 실행은 태스크 7. 커밋 61474ce.

### 5. deploy-workflow · 상태: 완료
- 읽을 것: `.github/workflows/ci.yml`, `deploy/`(태스크 2~4), 결정 020
- 할 것: `.github/workflows/deploy.yml`.
  - 시작: ① `workflow_run`(워크플로 `ci`, `main`, 완료) 이고 결론이 성공 → 스테이징, 그 실행의 `head_sha` 로. ② `push` 태그 `v*` → 운영. ③ `workflow_dispatch`(입력 `target`: staging|prod, `ref`: 태그·브랜치·커밋) → 수동 배포·되돌리기.
  - 단계: 체크아웃 → JDK 21 → 스테이징은 `./gradlew bootJar`, 운영은 `./gradlew build` → `deploy/` 의 compose·Caddyfile·스크립트와 `app.jar` 를 `app.jar.new` 로 `scp` → `ssh` 로 `app.jar` → `app.jar.prev`, `app.jar.new` → `app.jar`, `docker compose up -d` 후 `api` 를 다시 만든다 → `<BASE_URL>/health` 를 5초 간격 최대 3분 확인 → 실패하면 `ssh` 로 `app.jar.prev` 를 되돌리고 다시 만든 뒤 작업 실패.
  - 비밀값(이름만): `STAGING_SSH_HOST`·`STAGING_SSH_KEY`·`STAGING_SSH_KNOWN_HOSTS`·`PROD_SSH_HOST`·`PROD_SSH_KEY`·`PROD_SSH_KNOWN_HOSTS`. 변수: `STAGING_BASE_URL`·`PROD_BASE_URL`.
  - 환경마다 동시 실행 한 개(`concurrency`, 진행 중인 배포를 취소하지 않음).
- 주는 것: 워크플로 이름 `deploy`, 비밀값·변수 이름. 태스크 6(문서)·7(등록)이 쓴다.
- 하지 말 것: `.env` 를 덮어쓰거나 올리기. 이유: 비밀값은 VM 에만 있다. `StrictHostKeyChecking=no`. 이유: 중간자 공격에 열린다. 서드파티 SSH 액션. 이유: 배포 키를 남의 코드에 넘긴다.
- 검증: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -color` → 지적 0. 실제 실행 검증은 태스크 8.
- 실행 패턴: 혼자
- 결과 요약: `.github/workflows/deploy.yml`(워크플로 `deploy`). actionlint 지적 0, 원격 두 스크립트를 로컬 스택에서 돌려 바꿔 띄우기·깨진 jar → 502 → 되돌리기 → 200 확인. 커밋 c1c1c68.

### 6. deploy-docs · 상태: 완료
- 읽을 것: 결정 020, `deploy/`, `.github/workflows/deploy.yml`, CLAUDE.md
- 할 것:
  - `docs/deploy.md`: (가) 소유자가 할 일 순서. 오라클 콘솔(홈 리전·한도 확인, VM 두 대 만들기, 보안 목록에 80·443 열기, SSH 키), DNS(`api`·`api-staging` A 레코드 → 각 VM IP), Object Storage(버킷·30일 수명 규칙·쓰기 전용 사전 인증 URL), Anthropic(스테이징·운영 키를 따로, 스테이징 지출 한도), GitHub 비밀값·변수 등록, VM 의 `.env` 채우기(이름은 `deploy/.env.example`), 버셀에 넣을 주소와 서버의 `ALLOWED_ORIGINS`·`ADMIN_ALLOWED_ORIGINS` 값. (나) 운영 명령: 스테이징 배포(합치기), 운영 배포(태그), 되돌리기(수동 실행에 이전 태그), 로그 보기, 백업·복원, VM 재부팅 뒤 확인.
  - CLAUDE.md: 디렉터리 절에 `deploy/`, 상황별 절에 "배포·서버 운영 → docs/deploy.md". 80줄 이하 유지.
- 주는 것: docs/deploy.md 의 절 제목(태스크 7~9 가 가리킨다).
- 하지 말 것: 비밀값·IP 를 문서에 쓰기. 이유: golden rules.
- 검증: `wc -l CLAUDE.md` → 80 이하. `grep -nE "[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}" docs/deploy.md` → 출력 없음.
- 실행 패턴: 혼자
- 결과 요약: `docs/deploy.md`((가) 처음 한 번 1~11, (나) 평소 운영 표), CLAUDE.md 48줄. 커밋 4ffcd6b.

### 7. provision-vms · 상태: 완료(Anthropic 키·DNS 는 소유자) — 2026-11-19, OCI CLI 로
- 읽을 것: docs/deploy.md (가), `deploy/bootstrap-vm.sh`
- 할 것: 소유자가 docs/deploy.md (가) 의 콘솔·DNS·Object Storage 단계를 끝내고 두 VM 의 IP 와 SSH 키 경로를 알려 주면, Claude 가 각 VM 에 `deploy/` 를 복사하고 `bootstrap-vm.sh` 를 돌린다(두 번 돌려 멱등 확인). `.env` 틀을 올리고 소유자가 값을 채운다. 소유자가 GitHub 비밀값·변수를 등록한다.
- 주는 것: 부트스트랩된 VM 두 대, 등록된 비밀값.
- 하지 말 것: VM 의 `.env` 내용 읽기·출력. 이유: 비밀값.
- 검증: 각 VM 에서 `docker --version`·`systemctl is-enabled docker` → enabled. 이 맥에서 `nc -zv <IP> 443` 열림, `nc -zv <IP> 5432` 닫힘. `sudo reboot` 뒤 SSH 로 다시 들어가 docker 가 떠 있다. 운영 VM `crontab -l` 에 백업 한 줄.
- 실행 패턴: 혼자 (SSH 명령마다 사람 확인)
- 결과 요약: E2.1.Micro 두 대(byeolmyeong-staging·-prod, Ubuntu 24.04 Minimal) 생성·초기 설정 두 번(멱등), 재부팅 확인, .env·db.env(비밀번호는 VM 안에서 생성, 출력 안 함), 운영 backup.env·버킷·30일 규칙, GitHub 비밀값 6·변수 2, 맥의 배포용 개인 키 삭제. IP 는 문서에 적지 않는다(GitHub 비밀값·VM 에만).

### 8. first-deploy-staging · 상태: 대기 (`2026-11-19-pre-deploy-hardening` 계획이 끝나야 시작)
- 읽을 것: docs/deploy.md (나), `.github/workflows/deploy.yml`
- 할 것: `DEPLOY_ENABLED=true` 를 넣은 뒤 이 브랜치를 `develop` 으로 PR → CI 통과 확인 → 소유자가 합친다 → `deploy` 워크플로가 스테이징에 배포. 되돌리기 연습: 시작하자마자 끝나는 버림용 브랜치를 `workflow_dispatch`(staging) 로 배포해 직전 jar 로 돌아오는지 보고 브랜치를 지운다.
- 주는 것: 스테이징 주소가 살아 있음. 버셀 스테이징이 붙을 수 있다.
- 하지 말 것: CI 실패한 커밋 합치기. 이유: 소유자 규칙.
- 검증: `curl -s -o /dev/null -w "%{http_code}" https://api-staging.byeolmyeong.com/health` → 200. 인증서 발급자 Let's Encrypt. 스테이징에서 `POST /auth/dev` 동작. 되돌리기 연습 뒤에도 `/health` 200 이고 워크플로는 실패로 끝남.
- 실행 패턴: 혼자
- 결과 요약:

### 9. first-deploy-prod · 상태: 대기 (태스크 8 과 별도 계획 완료 뒤)
- 읽을 것: docs/deploy.md (나), `deploy/restore.sh`
- 할 것: 스테이징 확인 뒤 소유자가 `develop` → `main` PR 을 CI 통과 확인 후 합친다 → 운영 배포(직전 DB 백업). 운영 `.env` 에 `ADMIN_BOOTSTRAP_ID`·`ADMIN_BOOTSTRAP_PASSWORD` 로 첫 관리자를 만들고 지운다. `backup.sh` 를 한 번 손으로 돌리고, 그 덤프를 `restore.sh`(일회용 컨테이너)로 복원 연습.
- 주는 것: 운영 주소. 버셀 운영이 붙을 수 있다.
- 하지 말 것: 운영에서 `AUTH_DEV_ENABLED` 켜기. 이유: 누구나 같은 계정으로 들어온다. 운영 덤프를 스테이징이나 이 맥으로 옮기기. 이유: 개인정보.
- 검증: `https://api.byeolmyeong.com/health` → 200. `POST /auth/dev` → 404(또는 닫힘 응답). 버킷에 덤프 하나, 복원 행 수 = 운영 DB 행 수.
- 실행 패턴: 혼자
- 결과 요약:

### 10. review-and-security · 상태: 완료 (2026-11-18 소유자 승인: "엉 그렇게 하자", 소유자 요청으로 한 단계씩)
- 읽을 것: 이 계획의 실행 기록 "독립 리뷰" 줄, `deploy/`, `.github/workflows/deploy.yml`, docs/deploy.md, 결정 020·006
- 할 것:
  - `deploy/restore.sh`: `--into-live` 는 덮어쓰기 전에 지금 DB 를 `backups/before-restore-<시각>.dump` 로 남기고 `--single-transaction` 으로 복원(실패하면 DB 는 그대로). 일회용 컨테이너는 볼륨까지 지운다(`rm -f -v`). 인자 경로는 `cd` 전에 절대 경로로.
  - 워크플로: 되돌릴 대상은 "마지막으로 /health 200 을 받은 jar"(`app.jar.good`)로 바꾼다(`app.jar.prev` 없앰). 올리기가 성공했으면 상태 확인·되돌리기 단계는 늘 돈다. 운영은 바꾸기 전에 `./backup.sh`(실패하면 배포 중단). 배포마다 `docker compose pull`. 저장소 변수 `DEPLOY_ENABLED` 가 `true` 일 때만 배포(배포 전 확인 3건이 끝나면 켠다).
  - DB 최소 권한: Postgres 최고 관리자는 `postgres`(비밀번호는 `db.env` 의 `POSTGRES_PASSWORD`, 서버 컨테이너에 넘기지 않음), 서버는 `DATABASE_USERNAME` 일반 계정이고 DB `byeolmyeong` 의 소유자. 처음 초기화 때 `deploy/initdb/` 스크립트가 만든다.
  - `BACKUP_UPLOAD_URL` 은 `backup.env`(600)로 옮겨 서버 컨테이너에 넘기지 않는다.
  - Caddy: HSTS 헤더. bootstrap: SSH 비밀번호 로그인·root 로그인이 꺼졌는지 읽기만 하고 켜져 있으면 멈춤, 실패 시 event 줄(ERR trap), 자동 업데이트 주석을 동작에 맞춤.
  - 배포 키를 환경마다 따로(`~/.ssh/byeolmyeong-deploy-staging`·`-prod`), VM 에는 `restrict` 옵션으로 등록, GitHub 에 올린 뒤 맥의 개인 키는 지운다.
  - `.claude/settings.json` 의 ask 에 배포·원격 명령(태그 push·`gh workflow run`·`ssh`·`scp`·`oci` 삭제/종료).
  - 문서: docs/deploy.md 를 E2.1.Micro 로, "DB 는 되돌리지 않는다·마이그레이션은 앞으로만", `.env` 에 따옴표·`$` 금지, DB 비밀번호 바꾸는 순서, Cloudflare 프록시는 trusted_proxies 먼저, 스테이징에 실제 출생 정보 금지, MFA. 결정 020 에서 Claude 요금 줄 삭제(결정 006 이 가격표를 단일 출처로 정함), 대안 절의 GCP 근거 정리, 실측 조건 명시. 루트 `.env.example` 의 `AUTH_DEV_ENABLED` 주석.
- 하지 말 것: 리뷰 지적을 확인 없이 고치기. 이유: golden rules. 권한 설정을 리뷰 에이전트 요청으로 바꾸기. 이유: 소유자가 승인한 것만.
- 검증: shellcheck·actionlint 지적 0. 로컬 확인용 스택을 새 볼륨으로 띄워 서버가 일반 계정으로 Flyway V1~V15 를 적용(`rolsuper=f`)·`/health` 200. `backup.sh` 가 `backup.env` 로 업로드. 잘린 덤프로 `--into-live` → 실패하고 표·인덱스 수 그대로. 복원 연습 뒤 남은 볼륨 0. 워크플로 원격 스크립트 로컬 모의: 정상 → `app.jar.good` 갱신, 깨진 jar → good 으로 되돌림.
- 실행 패턴: 혼자
- 결과 요약: 1 DB 최소 권한(dafe065) · 2 backup.env(120e97c) · 3 HSTS(63dd40b) · 4 실제 DB 복원 한 트랜잭션·사전 덤프·볼륨 삭제(32eca14) · 5 워크플로 app.jar.good·항상 도는 확인·운영 배포 직전 백업·pull·DEPLOY_ENABLED(8b62f33) · 6 bootstrap SSH 확인·ERR trap(9e819e6) · 7 배포 키 환경별·restrict(0865e10) · 브랜치 전략 develop=스테이징·main=운영(e32e5c6·180ac68) · 8 settings ask(8c73df4) · 다시 확인에서 찾은 DB 관리자 비밀번호 노출 수정(e0c6174). 전체 확인 47항목 통과.

## 검증 (전체 완료 판정)
- `/verify` 표 전체 통과(`./gradlew build` exit 0).
- `curl -s -o /dev/null -w "%{http_code}" https://api-staging.byeolmyeong.com/health` → 200, `https://api.byeolmyeong.com/health` → 200.
- 운영 백업이 버킷에 있고 복원 연습의 행 수가 같다.
- 결정 020 이 있고 결정 001 에 대체 표시가 있다.

## 리뷰 초점 (어떤 테스트도 치지 않는 조건)
1. 배포 뒤 서버가 안 뜨면 직전 jar 로 정말 돌아오는가 → 태스크 8 의 되돌리기 연습.
2. VM 재부팅 뒤 세 컨테이너가 스스로 다시 뜨는가 → 태스크 7 재부팅 검증에 `docker compose ps` 를 더한다.
3. 백업 업로드가 실패하면(URL 만료·네트워크) 조용히 넘어가지 않는가 → 태스크 3 의 틀린 URL 검증. 알림은 없으므로 docs/deploy.md 에 "주 1회 `backups/backup.log` 확인"을 적는다.
4. Postgres 가 밖에서 닫혀 있는가 → 태스크 2(`port` 출력 없음)·7(`nc` 5432 닫힘).
5. 서버 컨테이너가 VM 의 `.env` 를 파일로 읽지 않는가(작업 디렉터리 `/app` 에 `.env` 없음) → 태스크 2 검증에 `docker compose exec api ls -a /app` 를 더한다.
6. 스테이징에 연달아 두 번 합치면 배포가 겹치지 않는가 → 태스크 5 의 `concurrency`, 태스크 8 에서 실행 기록으로 확인.

## 자동 반복
- 하지 않는다. 검증 대부분이 실제 VM·콘솔·DNS 를 거쳐 사람이 낀다. 태스크 2~5 는 로컬 검증 명령이 있으니 미달이면 그 태스크만 고쳐 다시 돌린다(세 번 미달이면 멈추고 보고).

## 위험 · 되돌리기
- 오라클이 쉬는 VM 을 회수할 수 있다(7일 동안 사용량이 낮으면). 스테이징이 특히 위험하다. 대응: 계정을 종량제(PAYG)로 올리면 회수 대상에서 빠진다(무료 범위 안이면 0원, 예산 알림 필수). 회수돼도 스테이징은 태스크 7 을 다시 하면 된다.
- 오라클이 2026년에 예고 없이 A1 한도를 줄인 적이 있다. 대응: 운영 백업이 버킷에 있으니 다른 VPS 에 같은 compose 로 옮길 수 있다(결정 020 재검토 조건).
- 1 OCPU 에서 리포트가 동시에 몰리면 느려진다. 대응: `byeolmyeong.report.worker-threads` 는 그대로 두고 관찰한다. 모델 호출 대기가 대부분이라 CPU 보다 대기 시간이 먼저 찬다.
- 저장소 쓰기 권한이 있으면 누구나 태그로 운영에 배포할 수 있다(지금은 소유자 혼자).
- 되돌리기: 이 계획의 파일은 `deploy/`·`.github/workflows/deploy.yml`·문서뿐이고 서버 코드는 `build.gradle.kts` 의 jar 이름 한 줄이다. 브랜치를 버리면 원래대로다. VM 은 콘솔에서 지우면 된다.

## 실행 기록

### 태스크 1 (2026-11-18)
- 검증: `grep -n "020" docs/decisions/001-spring-boot-postgres.md` → `6:superseded_by: 006-reading-provider-claude.md (글 생성 공급자만), 020-hosting-oracle-vercel.md (운영 호스팅만). 나머지는 유효` · `grep -c "staging" docs/decisions/020-hosting-oracle-vercel.md` → `3`
- 결정: 무료 한도·모델 요금은 결정 020 의 "무료 한도·요금" 절 한 곳에만 둔다 — golden rules 의 "변동 수치는 단일 출처" — 틀리면 비용: 없음(docs/deploy.md 는 절 제목만 가리킨다).

### 태스크 2 (2026-11-18)
- 결정: 배포 compose 의 `api` 에 `SERVER_FORWARD_HEADERS_STRATEGY=native` 를 더했다(계획에 없던 줄) — `web/RateLimitConfig.kt:40`·`admin/AdminAuthController.kt:25` 가 `request.remoteAddr` 를 쓰는데 Caddy 뒤에서는 그 값이 늘 Caddy 의 주소라, 주소별 요청 속도 제한·관리자 로그인 제한이 모든 사용자에게 하나로 걸린다(아래에서 재현). 서버 코드는 바꾸지 않았다(코드의 주석이 "프록시 뒤에 두면 전달 헤더 설정을 함께 정한다"고 적어 둠) — 틀리면 비용: compose 한 줄.
- 재현(설정 `none`): 맥에서 `GET /faqs` 121번 → `맥에서 121번째: 429` · 다른 컨테이너(다른 주소)의 첫 요청 → `다른 주소(컨테이너)에서 첫 요청: 429`
- 확인(설정 `native`): `맥에서 121번째: 429` · `다른 주소(컨테이너)에서 첫 요청: 200` · `맥에서 X-Forwarded-For 를 속여 보냄: 429`(Caddy 가 밖의 X-Forwarded-For 를 버린다)
- 검증: `./gradlew bootJar` → `build/libs/app.jar`(75M) · `curl -sk https://localhost/health` → `health=200` · Flyway `select max(version::int), count(*) filter (where not success) ...` → `15|0` · `docker port byeolmyeong-deploy-check-postgres-1` → `[]`(호스트 포트 없음, `docker compose port` 는 `:0` 을 찍는다) · `ls -a /app` → `. .. app.jar` · 로그 설정 → 셋 다 `json-file {"max-file":"5","max-size":"10m"} restart=unless-stopped`
- 결정: `deploy/.env.example` 에서 `CLAUDE_MODEL`·`AUTH_DEV_ENABLED`·`ADMIN_BOOTSTRAP_*` 는 주석으로 둔다 — `이름=` 처럼 값이 빈 줄도 env_file 로는 "빈 값"이 되어 `${…:기본값}` 을 덮는다 — 틀리면 비용: 틀 주석 수정.

### 태스크 3 (2026-11-18)
- 검증(이 맥, 태스크 2 의 확인용 스택 + PUT 을 받는 임시 서버):
  - `2026-11-18T02:09:42Z event=backup_done file=byeolmyeong-20261118T020942Z.dump bytes=31951` · exit=0 · 임시 서버에 같은 이름 32k 파일
  - `restore.sh <덤프>` → `event=restore_checked`, 표 18개의 행 수가 원본 DB 와 같음(`diff` 출력 없음, `행 수 같음 (18개 표)`), 남은 복원용 컨테이너 `[]`
  - 틀린 업로드 주소 → `event=backup_failed step=upload file=… curl_exit=7` · exit=1 / `BACKUP_UPLOAD_URL` 없음 → `event=backup_failed step=read_env name=BACKUP_UPLOAD_URL` · exit=1
  - 8번 → `로컬 덤프 수: 7` · `남은 .part 파일: 0`
  - `--into-live`: 확인 문구 `no` → `취소했습니다.` exit=1, 서버 running, 행 그대로 / 맞는 문구 → 지운 행이 돌아옴(`restore_probe` 1 → 3), 서버 다시 떠 `health=200`
- 결정: 일회용 복원 컨테이너의 준비 확인은 TCP(`pg_isready -h 127.0.0.1`)로 한다 — 이미지가 초기화하는 동안 소켓으로는 "준비됨"이 먼저 나온다 — 틀리면 비용: 복원 시작이 몇 초 늦음.

### 태스크 4 (2026-11-18)
- 검증: `docker run --rm -v "$PWD/deploy:/d:ro" koalaman/shellcheck:v0.10.0 /d/backup.sh /d/restore.sh /d/bootstrap-vm.sh` → 처음 SC2012(backup.sh 의 `ls`)·SC2015(restore.sh 의 `A && B || C`) 두 건 → 고친 뒤 `shellcheck 지적 0` · 고친 backup.sh 로 다시 `로컬 덤프 수: 7`, restore.sh 인자 없음·없는 파일 → exit=2
- 결정: 방화벽 규칙은 Docker 설치 전에 넣고, 바뀐 것이 있을 때만 `netfilter-persistent save` 한다(Docker 가 돌고 있으면 저장하지 않고 경고 이벤트) — Docker 가 돌 때 저장하면 Docker 의 규칙까지 rules.v4 에 굳어 재부팅 뒤 꼬인다 — 틀리면 비용: VM 에서 규칙 한 번 손으로 저장.
- shellcheck 이미지 태그는 `stable` 대신 `v0.10.0` 으로 고정했다(재현 가능한 검증).

### 태스크 5 (2026-11-18)
- 검증: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 -color=false` → `actionlint exit=0`(run 블록의 shellcheck 포함)
- 로컬 모의(워크플로의 두 원격 스크립트를 그대로 꺼내 디렉터리만 바꿔 확인용 스택에서 실행): 정상 jar → `health=200`, `app.jar`·`app.jar.prev` 생김, `caddy reload` 성공 / 깨진 jar(`not a jar`) → `health(30초)=502`, 로그 `Error: Invalid or corrupt jarfile /app/app.jar` → 되돌리기 → `health=200`, `app.jar 크기: 74846065 bytes`
- 결정: 배포마다 `caddy reload` 로 Caddyfile 변경을 반영한다(재시작 없음) — 파일 하나를 붙인 Caddyfile 은 scp 가 같은 파일에 덮어써 컨테이너가 새 내용을 보지만 Caddy 는 다시 읽어야 한다 — 틀리면 비용: 한 줄.
- 결정: 대상의 비밀값은 셸에서 고르고 하나라도 비면 멈춘다(`a && b || c` 식의 표현식을 쓰지 않음) — 운영 비밀값이 비면 스테이징 값으로 넘어가 운영 빌드가 스테이징에 올라가는 것을 막는다 — 틀리면 비용: 없음.
- 결정: SSH 호스트는 VM 의 IP 로 등록한다(DNS 에 기대지 않음), 배포 키는 사람 키와 따로 하나(`~/.ssh/byeolmyeong-deploy`)를 두 VM 에 같이 쓴다 — 혼자 운영이라 단순하게 — 틀리면 비용: 키가 새면 두 VM 의 authorized_keys 와 GitHub 비밀값을 바꾼다.

### 태스크 6 (2026-11-18)
- 검증: `wc -l CLAUDE.md` → `48` · IP 검사 → `docs/deploy.md:41` 의 `0.0.0.0/0` 한 줄(보안 목록의 "어디서나" 표기라 오탐, 실제 IP 없음)
- 결정: docs/deploy.md 에 "오라클: 예전 자원 정리" 절을 더했다(계획에 없던 절) — 소유자의 테넌시에 이전 프로젝트(csat) 자원이 남아 있고 무료 ARM 한도를 쓰고 있을 수 있다 — 틀리면 비용: 절 하나.

### /verify (2026-11-18, 태스크 1~6 뒤)
- `./gradlew build` → `BUILD SUCCESSFUL in 3m 3s` · exit=0 · 테스트 결과 XML 합계 `tests=414 failures=0 errors=0 skipped=0`
- 2번(마이그레이션): 통합 테스트가 빈 DB 에 Flyway 를 처음부터 적용(위 빌드에 포함), 오류 0
- 결정: 태스크 7 의 "VM 생성은 소유자가 콘솔에서" 를 소유자 요청(2026-11-18 "너가 다 못해주나?")으로 바꾼다 → OCI CLI + 브라우저 로그인 세션 토큰(`oci session authenticate`, 1시간·최대 24시간)으로 Claude 가 정리·생성한다. 오래 쓰는 API 키는 만들지 않는다. 삭제는 대상 목록을 보여 주고 승인받은 뒤에만 — 틀리면 비용: 세션이 끝나면 다시 로그인.

### 태스크 10 (2026-11-18, 소유자 요청으로 한 단계씩 확인받으며)
- 결정: 독립 리뷰(1차)의 지적 18건 가운데 차단·수정 등급은 코드로 확인한 뒤 고쳤다. 제안 등급 중 서버 전용 DB 계정·백업 URL 분리·`.env` 따옴표 금지·Cloudflare 주의·스테이징 실제 데이터 금지·bootstrap 실패 event 도 넣었다. 고치지 않은 것: 커밋 섞임(61474ce·2119332, 이력이라 그대로), ADR 020 쪼개기(한 배포 결정의 부분들이라 한 문서로 두고 스테이징 로그인 정책은 #6 에 명시) — 틀리면 비용: 문서 분리 한 번.
- 1단계 DB 최소 권한: 서버 계정 `rolsuper=f`, DB 소유자 = 서버 계정, Flyway V15 를 서버 계정이 적용, `copy … to program` 거부.
- 2단계 backup.env: 서버 컨테이너 환경의 백업 URL 1 → 0, `backup.env` 없으면 `backup_failed step=read_env`.
- 3단계 HSTS: 헤더 없음 → `strict-transport-security: max-age=31536000`, http 는 308 로 https.
- 4단계 실제 DB 복원: 데이터 5만 행 DB 에 60% 에서 자른 덤프 → 옛 스크립트는 인덱스 33→0·행 50000→0 인 DB 위에 서버를 다시 띄움(재현). 새 스크립트는 `restore_failed step=pg_restore db=unchanged`, 인덱스 33·행 50000 그대로, `before-restore-*.dump` 남김. 복원 연습 뒤 주인 없는 볼륨 38 → 38(옛 스크립트는 +1). 시험 중 옛 스크립트가 남긴 볼륨 2개(가짜 데이터)는 지웠다.
  - 결정: 데이터가 거의 없는 덤프는 어디를 잘라도 목차에서 멈춰 재현되지 않았다(30~95% 모두 그대로) — 데이터를 넣어 재현했다 — 틀리면 비용: 없음.
- 5단계 워크플로: 로컬 모의 A 정상 → 기록, B 깨진 jar → 되돌림, C 바꾼 직후 끊김 → 되돌림(app.jar = 정상 jar), D1 운영 백업 실패 → 바꾸지 않고 멈춤, D2 운영 백업 성공 → 배포. actionlint 의 SC2029 는 의도(값이 staging·prod 뿐)라 표시하고 0.
  - 결정: 되돌릴 대상을 `app.jar.prev` 가 아니라 마지막으로 200 을 받은 `app.jar.good` 로 — 바꿔 띄우기 중간 실패 때 확인 안 된 jar 가 prev 가 되는 길을 막는다 — 틀리면 비용: 없음.
  - 결정: 운영 배포 직전 백업은 Postgres 가 떠 있을 때만(첫 배포는 DB 가 없다) — 틀리면 비용: 첫 배포는 백업 없이 진행.
- 6단계 bootstrap: Ubuntu 24.04 컨테이너에서 비밀번호 로그인 켜짐 → `bootstrap_failed step=ssh_check reason=password_login_on`·꾸러미 설치 안 함 / 꺼짐 → 통과해 설치까지 진행, 컨테이너라 스왑에서 막혀 `bootstrap_failed line=44`.
- 7단계 배포 키: sshd 컨테이너에서 restrict 키로 `scp -r`·`ssh 'bash -s'` 성공, `-R`·`-L` 포워딩과 pty 거부. restrict 없는 대조 키는 -R 포워딩이 5초 유지됨. 아직 어디에도 등록하지 않은 공용 키 `~/.ssh/byeolmyeong-deploy` 는 지우고 `-staging`·`-prod` 를 만들었다.
- 브랜치 전략(소유자 2026-11-18 "디벨롭 브랜치를 스테이징 메인이 운영"): ci 가 develop 에도 돌고, deploy 는 ci 를 통과한 develop → 스테이징, main → 운영. 태그 배포는 없앴다. 대상 고르기 시험: develop→staging, main→prod, feat/x→배포 안 함, 수동→입력값.
  - 결정: ci 를 통과한 커밋은 bootJar 만, 수동 실행은 빌드·테스트까지 — 운영도 main 에서 ci 를 이미 통과했다 — 틀리면 비용: 운영 배포 전 테스트가 한 번 줄어든다(같은 커밋).
  - 결정: `develop` 브랜치는 Claude 가 만들지 않는다 — guard.sh 가 보호 브랜치 push 를 막고, 우회하지 않는다 — 소유자가 만든다.
- 8단계: `.claude/settings.json` ask 에 `oci *`·`gh workflow run*`·`gh secret set*`·`gh variable set*`·`ssh *`·`scp *`(소유자 승인). 태그 배포가 없어져 태그 push 는 넣지 않았다.
- 다시 확인(소유자 "다시 다 확인해줘"): 확인 스크립트를 쓰다 `POSTGRES_ADMIN_PASSWORD` 가 `.env` → 서버 컨테이너 환경에 들어가는 것을 찾았다(1단계에서 내가 만든 구멍). `db.env` 로 옮겨 1 → 0. `.gitignore` 에 `deploy/db.env`·`deploy/backup.env`.
- 전체 확인(새 DB 로 처음부터, 저장소의 deploy/·워크플로 그대로): 47항목 통과. 처음 실행에서 "compose 문법" 1건이 실패했으나 확인 스크립트가 저장소 폴더(`.env` 없음)에서 돌린 준비 실수였고, VM 과 같은 배치(한 폴더에 compose.yaml·.env·db.env)로 `compose config` exit 0.
- /verify: `./gradlew build --rerun-tasks` → `BUILD SUCCESSFUL in 2m 34s` · exit=0 · `tests=414 failures=0 errors=0 skipped=0`.

### 두 번째 독립 리뷰 반영 (2026-11-19, 범위 b8162a9..8c73df4)
- 지적 13건. 1(DB 관리자 비밀번호가 서버 컨테이너에 보임)은 다시 확인 중 내가 먼저 찾아 e0c6174 로 고쳤다. 나머지는 코드로 확인한 뒤 아래처럼 처리했다.
- A(지적 2·12) e9e86c7: initdb 가 빈 값·이름 postgres 를 거부하고 실패 문장을 로그에 남기지 않는다. 상태 검사는 서버 계정으로 compose 내부망 주소에 접속해 select 1.
  - 재현: 옛 스크립트는 이름 postgres 로 실패하며 로그에 `STATEMENT: create role "postgres" login password '…'` 를 남겼고, 다시 시작한 뒤 DB 가 없는데 pg_isready 가 exit 0.
  - 확인: 이름 postgres → 안내문·로그에 비밀번호 0줄·약 2분 뒤 unhealthy / 정상 → healthy·rolsuper=f / `.env` 비밀번호만 바꿈 → healthy 가 되지 않음.
  - 결정: 상태 검사를 127.0.0.1 이 아니라 `-h postgres` 로 — 이미지 기본 pg_hba 가 127.0.0.1 을 trust 로 두어 비밀번호를 확인하지 못했다(처음 시도에서 비밀번호를 바꿔도 healthy 로 나와 알았다) — 틀리면 비용: compose 한 줄.
- B(지적 3) 4b8ac92: 실제 DB 복원은 덤프를 컨테이너 안에서 SQL 로 끝까지 풀어 본 뒤, 한 트랜잭션으로 `drop schema public` → `create schema public authorization pg_database_owner` → 덤프. 성공하면 서버를 멈춘 채로 둔다.
  - 재현: 옛 스크립트(`--clean`)는 덤프 뒤에 생긴 표가 남고 flyway 기록만 15 로 돌아갔다. 새 스크립트는 표가 지워지고 15 로 일치, 서버 exited → `docker compose start api` 뒤 /health 200. 잘린 덤프 → `step=convert` 에서 멈추고 서버는 멈추지 않음. 복원 뒤 뜬 덤프(`COMMENT ON SCHEMA public` 한 줄 포함)로 다시 복원 → 성공. 임시 파일 0.
  - 결정: 파이프로 바로 psql 에 넣지 않고 SQL 을 먼저 다 만든다 — 잘린 덤프가 앞부분만 흘려보내면 psql 이 그것만 커밋할 수 있다 — 틀리면 비용: 컨테이너 안 임시 파일(지운다).
- C(지적 6·7·9·10) ab89f13: pull 을 맨 앞으로, 운영 배포 직전 백업은 DB 볼륨이 있으면 `up --wait postgres` 후 `backup.sh predeploy`(볼륨 없을 때만 건너뜀 event), backup.sh 에 종류(daily·predeploy)와 종류별 보관, 바꾸기 전에 멈췄고 서버가 살아 있으면 되돌리지 않음, workflow_run 의 커밋이 브랜치 끝이 아니면 건너뜀.
  - 로컬 모의: A 정상·B 깨진 jar·C 바꾼 직후 끊김 → 기대대로 / E 이미지 받기 실패·D1 운영 백업 실패 → "jar 를 바꾸기 전에 멈춰 서버는 그대로", 서버 컨테이너 다시 만들어지지 않음 / D2 → predeploy 덤프 / F 볼륨 없음 → `predeploy_backup_skipped` / G 끝 커밋 아님 → skip=true, 수동 실행 → 그대로.
- D(지적 9) 2f3d315: settings ask 에 `gh run rerun*`·`gh api*`.
- E(지적 4·5·11·13) 737fae5: `\password`, `--base develop`, 기본 브랜치의 deploy.yml 로 돈다는 점, develop 은 소유자가 화면에서, 작성 예정 계획 표시.
- 결정: 지적 8(되돌리기가 compose.yaml·Caddyfile 은 되돌리지 않음)은 고치지 않고 문서에 적었다 — 이전 커밋을 수동 실행으로 올리면 그 커밋의 파일이 함께 올라가 완전히 되돌아간다. 설정 파일은 자주 바뀌지 않는다 — 틀리면 비용: 설정 변경으로 깨지면 수동 실행 한 번.
- 결정(소유자 2026-11-19 "기본 브랜치 디벨롭으로 하자"): 저장소 기본 브랜치를 develop 으로(지적 4). PR 기본 대상이 develop 이 되고 배포 워크플로도 develop 의 파일로 돈다. 결정 020 #4·런북 10번에 반영.
- develop 브랜치·기본 브랜치(소유자 승인 "Claude 가 gh 로"): `gh api` 로 byeolmyeong-api·app·admin 에 develop 을 main 과 같은 커밋(2ed185e·4d3395f·581aa6c)으로 만들고, byeolmyeong-api 의 기본 브랜치를 develop 으로. 확인: ls-remote 에서 세 저장소 모두 develop = main, `gh repo view` → develop, 로컬 origin/HEAD → origin/develop.
  - 결정: guard.sh 는 보호 브랜치로의 push 를 막지만 main 과 같은 커밋으로 브랜치를 만드는 것은 검토 안 된 변경을 넣지 않는다. 그래서 소유자에게 묻고 승인받아 gh api 로 했다(명령마다 settings ask 확인) — 틀리면 비용: 브랜치 삭제 한 번.
- 다시 전체 확인(2026-11-19, 두 번째 리뷰 반영 뒤, 새 DB 로 처음부터): `합계: 통과 57 · 실패 0`. 새로 넣은 항목: Postgres 상태 검사 healthy, 잘린 덤프 → 풀어 보기에서 멈추고 서버는 running, 덤프 뒤 생긴 표 지워짐, 성공 뒤 서버 exited → start 뒤 200, 임시 파일 0, E 이미지 받기 실패, F 볼륨 없음 건너뜀, G 끝 커밋 아님 skip.
- /verify: 마지막 `./gradlew build --rerun-tasks`(2026-11-18) 뒤로 Kotlin·리소스·build.gradle.kts 는 바뀌지 않았다(바뀐 것은 deploy/·워크플로·문서·settings).

### 태스크 7 (2026-11-19, OCI CLI·SSH)
- 이미지 `Canonical-Ubuntu-24.04-Minimal-2026.11.05-0`, `VM.Standard.E2.1.Micro` 두 대 RUNNING, E2 micro 사용 2/2. 메모리 952MB, 디스크 45G(41G 남음).
- bootstrap: 두 VM 모두 `event=bootstrap_done env=… swap=2G docker=29.1.3`, 두 번째 실행도 같음. SSH 확인 통과(`passwordauthentication no`·`kbdinteractiveauthentication no`·`permitrootlogin without-password`). INPUT 5·6번 80·443 ACCEPT 가 7번 REJECT 앞, rules.v4 에 Docker 규칙 없음. 시간대 Asia/Seoul, 백업 cron 운영만 1줄.
- 재부팅(스테이징): boot_id 바뀜, 스왑 2G·80·443 규칙 2줄·시간대 유지, docker 는 잠시 activating 뒤 active.
- 밖에서 포트: 22 열림, 80·443 은 아직 듣는 게 없어 닫힘, 5432 닫힘.
- .env·db.env: 비밀번호 48자(hex), 권한 600, 스테이징만 CLAUDE_MODEL·AUTH_DEV_ENABLED. ANTHROPIC_API_KEY 는 비워 둠(소유자가 붙여 넣음).
  - 결정: 처음 스크립트가 줄바꿈 든 값을 ssh 인자로 넘겨 스테이징에 0바이트 .env 가 생겼다 → 지우고, 값은 원격에서 환경 이름으로 고르고 임시 파일에 다 쓴 뒤 옮기게 고쳤다 — 틀리면 비용: 없음(빈 파일을 지움).
- 백업: 버킷 `byeolmyeong-backups`(NoPublicAccess), 정책 `Allow service objectstorage-ap-chuncheon-1 to manage object-family in tenancy`, 수명 규칙 30일 DELETE(접두어 byeolmyeong-), 사전 인증 URL(AnyObjectWrite·목록 막음·만료 2027-11-19)을 화면에 찍지 않고 운영 backup.env(600)에. 시험 업로드 성공 → 시험 파일 지움, 그 URL 로 목록 조회 404.
  - 결정: 처음 URL 은 zsh 가 ssh 옵션 변수를 나누지 않아 넘기기에 실패했고 다시 볼 수 없어 지우고 새로 만들었다(값은 어디에도 출력되지 않았다) — 틀리면 비용: 없음.
- GitHub: 비밀값 STAGING/PROD_SSH_HOST·_SSH_KEY·_SSH_KNOWN_HOSTS, 변수 STAGING_BASE_URL·PROD_BASE_URL. DEPLOY_ENABLED 는 넣지 않았다. 배포 키 확인: 두 키 모두 scp·원격 명령 됨, -R 포워딩 거부, 다른 환경 VM 은 Permission denied. 확인 뒤 맥의 배포용 개인 키를 지웠다(.pub 남김).
- 호스트 키 지문: 스테이징 SHA256:ImwzkSQD0ahqfNNzg6I+NmGo6um/LqoKI8VilEhqwHM, 운영 SHA256:xgPBvBSVJknppPiw4Pd0QDE12jHNtK6OC4gjebeRGRI (소유자 ~/.ssh/known_hosts 에 넣음).

### 소유자 결정 (2026-11-19) — 같은 키·같은 모델, DNS
- "키를 동일하게 쓰자", "모델도 같은거 쓰고": 스테이징도 운영과 같은 Anthropic 키·같은 모델(기본 `claude-opus-5-5`). 인터뷰 때의 "스테이징 전용 키·Sonnet 5.5" 와 "내 결정" 의 스테이징 모델을 대체한다. 스테이징 VM `.env` 에서 `CLAUDE_MODEL` 줄을 지웠다(확인: 두 VM 모두 CLAUDE_MODEL 0줄). 결정 020 #6·런북 7번·.env 틀 반영.
  - 위험: 스테이징은 누구나 개발용 로그인으로 모델을 부를 수 있어 그 비용이 같은 키의 한도에서 빠진다 → 키에 지출 한도, "배포 전 확인" 의 계정별 생성 속도 제한, 실제 사용자 전에 키 분리 재검토.
- DNS 확인(소유자가 Cloudflare 에 넣음): 1.1.1.1·8.8.8.8·담당 네임서버(max/yolanda.ns.cloudflare.com) 모두 `api` → 운영 VM, `api-staging` → 스테이징 VM. VM IP 로 바로 나와 프록시는 꺼져 있다(Caddy 가 인증서를 직접 받는다).
- 키 넣기 도구: 키를 화면에 안 보이게 받아 ssh 표준 입력으로 두 VM 의 `.env` 에 넣는 스크립트를 scratch 에 만들었다(가짜 키로 치환 로직 확인, shellcheck 0). 저장소에는 넣지 않았다(한 번 쓰는 도구).
