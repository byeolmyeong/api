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
- 스테이징 모델은 `claude-sonnet-5-5`($2/$10 per MTok, 운영의 `claude-opus-5-5` 는 $4/$20). 지금 코드가 보내는 `output_config.effort`·JSON 출력 형식·`fallbacks: "default"` 를 그대로 받는다. `claude-haiku-5-5`($0.10/$0.50)는 서버 쪽 거절 대체(fallbacks)를 받지 않아 `BYEOLMYEONG_CLAUDE_REFUSAL_FALLBACK=false` 가 더 필요하고 글 검사 통과율을 재 보지 않았다 → 나중에. 틀리면 비용: 스테이징 `.env` 한 줄.
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
- "배포 전 확인" 3건: 별도 계획 `docs/plans/2026-11-18-pre-deploy-hardening.md`(이 계획 승인 뒤 작성).
- 버셀 프로젝트 설정(앱·관리 화면 저장소의 일). 이 계획은 서버가 허용할 주소(`ALLOWED_ORIGINS`·`ADMIN_ALLOWED_ORIGINS`)만 정한다.
- 가동 감시·알림(백업 실패 알림 포함), 무중단 배포, 서버 여러 대, 관리형 DB, 소셜 로그인.

## 전역 제약
- JVM 21 · PostgreSQL 17 · Caddy 2 · Ubuntu 24.04 aarch64.
- 환경 이름은 `staging`·`prod` 두 개다. GitHub 비밀값·변수 이름은 `STAGING_*`·`PROD_*`.
- 저장소에 도메인 외의 IP·비밀값을 적지 않는다. 호스트·키·URL 은 GitHub 비밀값과 VM 의 `.env` 에만 둔다.
- VM 의 배포 디렉터리는 `/opt/byeolmyeong` 이다. 그 안: `compose.yaml`·`Caddyfile`·`backup.sh`·`restore.sh`·`.env`·`app.jar`·`app.jar.prev`·`backups/`.
- 커밋: 한국어 + Conventional Commits, 한 태스크 = 한 커밋, 작업 브랜치 `chore/deploy-oracle`.
- 스크립트는 `set -euo pipefail` 로 시작하고, 실패하면 0 이 아닌 값으로 끝나며 `event=<이름>` 한 줄을 남긴다.

## 태스크

### 1. adr-020-hosting · 상태: 대기
- 읽을 것: docs/decisions/001-spring-boot-postgres.md, docs/decisions/019-delete-synastry.md(형식), 이 계획의 "인터뷰 결과"·"내 결정"
- 할 것: `docs/decisions/020-hosting-oracle-vercel.md`. 맥락(무료·혼자 운영·JVM 은 늘 켜진 서버가 필요: 리포트 일꾼·속도 제한이 서버 메모리), 결정(오라클 VM 두 대·compose 구성·도메인 표·배포 흐름·백업·스테이징 설정·버셀), 대안과 버린 이유(Render 무료는 15분 뒤 잠듦, GCP e2-micro 는 1GB·미국만, Northflank 는 운영 금지, Koyeb 는 미국·유럽·신규 가입 제한, AWS 는 6개월), 재검토 조건(오라클이 VM 회수·한도 변경, 결제 시작 → 버셀 Hobby 는 비상업용만이라 Pro 또는 Cloudflare Pages, 동시 리포트가 늘어 1 OCPU 가 모자람, 복원 연습 실패). 결정 001 은 front matter 의 `superseded_by` 에 "020 (운영 호스팅만)" 만 덧붙인다.
- 주는 것: 결정 020 경로. 뒤 태스크 문서가 이 번호를 가리킨다.
- 하지 말 것: 결정 001 본문 고쳐 쓰기. 이유: 기존 ADR 은 대체 표시만 한다(golden rules).
- 검증: `grep -n "020" docs/decisions/001-spring-boot-postgres.md` → front matter 한 줄. `grep -c "staging" docs/decisions/020-hosting-oracle-vercel.md` → 1 이상.
- 실행 패턴: 혼자
- 결과 요약:

### 2. deploy-compose · 상태: 대기
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
- 결과 요약:

### 3. backup-restore · 상태: 대기
- 읽을 것: `deploy/compose.yaml`(태스크 2), 결정 020 의 백업 절
- 할 것:
  - `deploy/backup.sh`: `/opt/byeolmyeong` 에서 `postgres` 컨테이너로 `pg_dump -Fc` → `backups/byeolmyeong-<UTC 시각>.dump`, 7개 넘는 오래된 파일 삭제, `BACKUP_UPLOAD_URL`(사전 인증 URL, `.env` 에서 읽음)에 파일 이름을 붙여 HTTP PUT. 성공·실패마다 `event=backup_done|backup_failed` 한 줄(파일 이름·크기·단계, URL 은 남기지 않음).
  - `deploy/restore.sh <덤프 파일> [대상]`: 기본 대상은 일회용 컨테이너(`postgres:17`)에 복원하고 표마다 행 수를 출력한 뒤 컨테이너를 지운다. 실제 DB 에 덮어쓰기는 `--into-live` 를 줄 때만 하고, 그때는 `api` 를 멈추고 확인 문구 입력을 받는다.
- 주는 것: 두 스크립트 경로와 인자. 태스크 4(cron 등록)·9(복원 연습)가 쓴다.
- 하지 말 것: 백업 URL·DB 비밀번호를 로그에 남기기. 이유: 비밀값이다. 확인 없이 실제 DB 덮어쓰기. 이유: 되돌릴 수 없다.
- 검증(이 맥): 태스크 2 의 확인용 compose 에 행을 조금 넣고 `backup.sh`(업로드 URL 을 로컬에서 PUT 을 받는 임시 서버로) → 덤프 생성·업로드 200·`event=backup_done`. `restore.sh <덤프>` → 표별 행 수가 원본 DB 의 `select count(*)` 와 같다. 틀린 업로드 URL → 종료 코드 ≠ 0, `event=backup_failed`. 8번 돌려 로컬 파일이 7개.
- 실행 패턴: 혼자
- 결과 요약:

### 4. vm-bootstrap · 상태: 대기
- 읽을 것: `deploy/`(태스크 2·3), 결정 020
- 할 것: `deploy/bootstrap-vm.sh <staging|prod>`, VM 에서 `sudo` 로 한 번 돌린다. 여러 번 돌려도 결과가 같아야 한다(멱등). 하는 일: `docker.io`·`docker-compose-v2` 설치와 부팅 시 시작, `ubuntu` 사용자를 docker 그룹에, 오라클 Ubuntu 이미지의 iptables 에 80·443 허용 추가와 영구 저장(`netfilter-persistent`), `/opt/byeolmyeong`·`backups/` 생성(소유자 `ubuntu`), 시간대 `Asia/Seoul`, 자동 보안 업데이트 켜짐 확인. `prod` 일 때만 `backup.sh` 를 매일 04:00 에 cron 등록(출력은 `backups/backup.log`).
- 주는 것: 부트스트랩 뒤 VM 상태(디렉터리·cron·포트). 태스크 7 이 VM 에 실행한다.
- 하지 말 것: SSH 설정을 바꾸기(비밀번호 로그인은 오라클 이미지가 이미 끈다). 이유: 잘못 바꾸면 VM 에 못 들어간다. 원격 설치 스크립트를 파이프로 실행. 이유: 내용을 보지 않은 코드를 root 로 돈다.
- 검증(이 맥): `docker run --rm -v "$PWD/deploy:/d:ro" koalaman/shellcheck:stable /d/*.sh` → 지적 0. 실제 실행 검증은 태스크 7.
- 실행 패턴: 혼자
- 결과 요약:

### 5. deploy-workflow · 상태: 대기
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
- 결과 요약:

### 6. deploy-docs · 상태: 대기
- 읽을 것: 결정 020, `deploy/`, `.github/workflows/deploy.yml`, CLAUDE.md
- 할 것:
  - `docs/deploy.md`: (가) 소유자가 할 일 순서. 오라클 콘솔(홈 리전·한도 확인, VM 두 대 만들기, 보안 목록에 80·443 열기, SSH 키), DNS(`api`·`api-staging` A 레코드 → 각 VM IP), Object Storage(버킷·30일 수명 규칙·쓰기 전용 사전 인증 URL), Anthropic(스테이징·운영 키를 따로, 스테이징 지출 한도), GitHub 비밀값·변수 등록, VM 의 `.env` 채우기(이름은 `deploy/.env.example`), 버셀에 넣을 주소와 서버의 `ALLOWED_ORIGINS`·`ADMIN_ALLOWED_ORIGINS` 값. (나) 운영 명령: 스테이징 배포(합치기), 운영 배포(태그), 되돌리기(수동 실행에 이전 태그), 로그 보기, 백업·복원, VM 재부팅 뒤 확인.
  - CLAUDE.md: 디렉터리 절에 `deploy/`, 상황별 절에 "배포·서버 운영 → docs/deploy.md". 80줄 이하 유지.
- 주는 것: docs/deploy.md 의 절 제목(태스크 7~9 가 가리킨다).
- 하지 말 것: 비밀값·IP 를 문서에 쓰기. 이유: golden rules.
- 검증: `wc -l CLAUDE.md` → 80 이하. `grep -nE "[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}" docs/deploy.md` → 출력 없음.
- 실행 패턴: 혼자
- 결과 요약:

### 7. provision-vms · 상태: 대기 (소유자의 콘솔 작업이 먼저다)
- 읽을 것: docs/deploy.md (가), `deploy/bootstrap-vm.sh`
- 할 것: 소유자가 docs/deploy.md (가) 의 콘솔·DNS·Object Storage 단계를 끝내고 두 VM 의 IP 와 SSH 키 경로를 알려 주면, Claude 가 각 VM 에 `deploy/` 를 복사하고 `bootstrap-vm.sh` 를 돌린다(두 번 돌려 멱등 확인). `.env` 틀을 올리고 소유자가 값을 채운다. 소유자가 GitHub 비밀값·변수를 등록한다.
- 주는 것: 부트스트랩된 VM 두 대, 등록된 비밀값.
- 하지 말 것: VM 의 `.env` 내용 읽기·출력. 이유: 비밀값.
- 검증: 각 VM 에서 `docker --version`·`systemctl is-enabled docker` → enabled. 이 맥에서 `nc -zv <IP> 443` 열림, `nc -zv <IP> 5432` 닫힘. `sudo reboot` 뒤 SSH 로 다시 들어가 docker 가 떠 있다. 운영 VM `crontab -l` 에 백업 한 줄.
- 실행 패턴: 혼자 (SSH 명령마다 사람 확인)
- 결과 요약:

### 8. first-deploy-staging · 상태: 대기 (`2026-11-18-pre-deploy-hardening` 계획이 끝나야 시작)
- 읽을 것: docs/deploy.md (나), `.github/workflows/deploy.yml`
- 할 것: 이 브랜치를 PR → CI 통과 확인 → 소유자가 합친다 → `deploy` 워크플로가 스테이징에 배포. 되돌리기 연습: 시작하자마자 끝나는 버림용 브랜치를 `workflow_dispatch`(staging) 로 배포해 직전 jar 로 돌아오는지 보고 브랜치를 지운다.
- 주는 것: 스테이징 주소가 살아 있음. 버셀 스테이징이 붙을 수 있다.
- 하지 말 것: CI 실패한 커밋 합치기. 이유: 소유자 규칙.
- 검증: `curl -s -o /dev/null -w "%{http_code}" https://api-staging.byeolmyeong.com/health` → 200. 인증서 발급자 Let's Encrypt. 스테이징에서 `POST /auth/dev` 동작. 되돌리기 연습 뒤에도 `/health` 200 이고 워크플로는 실패로 끝남.
- 실행 패턴: 혼자
- 결과 요약:

### 9. first-deploy-prod · 상태: 대기 (태스크 8 과 별도 계획 완료 뒤)
- 읽을 것: docs/deploy.md (나), `deploy/restore.sh`
- 할 것: 소유자가 `v0.1.0` 태그를 단다 → 운영 배포. 운영 `.env` 에 `ADMIN_BOOTSTRAP_ID`·`ADMIN_BOOTSTRAP_PASSWORD` 로 첫 관리자를 만들고 지운다. `backup.sh` 를 한 번 손으로 돌리고, 그 덤프를 `restore.sh`(일회용 컨테이너)로 복원 연습.
- 주는 것: 운영 주소. 버셀 운영이 붙을 수 있다.
- 하지 말 것: 운영에서 `AUTH_DEV_ENABLED` 켜기. 이유: 누구나 같은 계정으로 들어온다. 운영 덤프를 스테이징이나 이 맥으로 옮기기. 이유: 개인정보.
- 검증: `https://api.byeolmyeong.com/health` → 200. `POST /auth/dev` → 404(또는 닫힘 응답). 버킷에 덤프 하나, 복원 행 수 = 운영 DB 행 수.
- 실행 패턴: 혼자
- 결과 요약:

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
