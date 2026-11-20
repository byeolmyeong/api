---
type: runbook
tags: [배포, 운영, 오라클 클라우드, 스테이징, 백업, 복원, 브랜치]
status: active
last_reviewed: 2026-11-18
---
# 배포·서버 운영

> 결정 docs/decisions/020-hosting-oracle-vercel.md · 046-blue-green-deploy.md(끊김 없는 배포) · 계획 docs/plans/2026-11-18-deploy-oracle.md · 2026-11-20-zero-downtime-deploy.md
> 오라클·버셀 무료 한도는 결정 020 의 "무료 한도" 절, Claude 요금은 Anthropic 가격표가 단일 출처다. 이 문서에 숫자를 옮겨 적지 않는다.
> IP·키·비밀번호·URL 값은 이 문서에 적지 않는다. 이름만 적는다.

## 한눈에
| | 운영(prod) | 스테이징(staging) |
|---|---|---|
| 브랜치 | `main` | `develop` |
| 올라가는 때 | `main` 에 합쳐지고 CI 가 통과할 때(jar 를 바꾸기 직전에 DB 백업). 그 커밋이 아직 `main` 의 끝일 때만 | `develop` 에 합쳐지고 CI 가 통과할 때. 그 커밋이 아직 `develop` 의 끝일 때만 |
| 서버 주소 | `https://api.byeolmyeong.com` | `https://api-staging.byeolmyeong.com` |
| 앱 웹(버셀, 앱 결정 037) | `app.byeolmyeong.com` | `app.staging.byeolmyeong.com` |
| 관리 화면(버셀) | `admin.byeolmyeong.com` | 없음 — 어드민은 운영만(소유자 2026-11-20). 버셀 프로젝트 `byeolmyeong-admin` 이 `main` 만 올린다 |
| 오라클 VM 이름 | `byeolmyeong-prod` | `byeolmyeong-staging` |
| DB 백업 | 매일 04:00 + 배포 직전 → VM 7개 + Object Storage 30일 | 없음(버려도 되는 데이터) |
| `.env` 의 `DEPLOY_ENV`(결정 022) | `prod` | `staging` — 없거나 비면 운영으로 본다 |
| 개발용 로그인 | 끔 | 켬(실제 출생 정보는 넣지 않는다). `DEPLOY_ENV` 가 `staging` 이 아니면 서버가 시작하지 않는다 |

흐름: 작업 브랜치 → PR(`gh pr create --base develop`) → `develop`(스테이징에서 확인) → `develop` → `main` PR → `main`(운영). 두 브랜치 모두 직접 push 하지 않는다(`.claude/hooks/guard.sh` 가 막는다). GitHub 무료 요금제라 보호 규칙이 없으니 **CI 가 통과했는지 보고 합친다**.

VM 안: `/opt/byeolmyeong/` 에 `compose.yaml`·`Caddyfile`·`initdb/`·`swap.sh`·`backup.sh`·`restore.sh`·`.env`(서버 컨테이너가 봄)·`db.env`(Postgres 만)·`backup.env`(운영, 백업 스크립트만)·`app-blue.jar`·`app-green.jar`(색마다 jar)·`upstream.caddy`(Caddy 가 보내는 색, `swap.sh` 가 씀)·`active_color`(지금 받는 색)·`app.jar.good`(지금 받는 jar)·`app.jar.prev`(그 앞에 받던 jar, 되돌리기용)·`backups/`. 컨테이너는 `api-blue`·`api-green`(서버, **늘 하나만 받는다**)·`postgres`·`caddy`(HTTPS).

서버를 바꿔 띄우는 것은 `swap.sh` 만 한다(결정 046): 쉬는 색에 jar 를 띄워 `/health` 200 을 본 뒤 Caddy 가 보내는 색을 바꾸고(`caddy reload`, 연결을 끊지 않는다), 옛 색은 받던 요청(최대 120초) · 쓰던 생성(최대 180초)을 끝낸 뒤 내린다. 새 색이 5분 안에 살아나지 않으면 받는 쪽은 옛 색 그대로다. Postgres · Caddy 는 만지지 않는다. **VM 에서 `docker compose up -d` 를 서비스 이름 없이 부르지 않는다**(두 색이 다 뜬다).

## (가) 처음 한 번 — 순서대로

### 0. 계정 보안 (가장 먼저)
- 오라클·GitHub·Anthropic·버셀·도메인 업체 계정에 2단계 인증(MFA)을 켠다. 이 구성에서 가장 큰 위험은 계정을 빼앗기는 것이다.

### 1. 오라클: 예전 자원 정리 (필요할 때만)
지운 것은 되돌릴 수 없다. 남길 데이터가 있으면 먼저 뺀다.
1. Compute → Instances: 쓰지 않는 인스턴스를 Terminate. **"Permanently delete the attached boot volume" 체크**(무료 블록 스토리지를 돌려받는다).
2. Storage → Block Storage → Boot Volumes: 주인 없이 남은 볼륨 삭제.
3. Networking → Virtual cloud networks: 쓰지 않는 VCN 삭제(서브넷·인터넷 게이트웨이·보안 목록이 같이 정리된다).
4. Networking → Reserved public IPs: 남은 예약 IP 삭제.

### 2. 오라클: 한도 확인
- 홈 리전이 춘천(`ap-chuncheon-1`)인지 본다. 무료 자원은 홈 리전에서만 나온다.
- Governance → Limits, Quotas and Usage → Compute 에서 `vm-standard-e2-1-micro-count` 가 2 인지 본다(이 테넌시는 ARM `A1` 한도가 0 이라 E2.1.Micro 를 쓴다, 결정 020).
- 무료 계정은 쉬는 VM 이 회수될 수 있다. 종량제(Pay As You Go)로 올리면 회수 대상에서 빠지고 무료 범위 안이면 0원이다. 올리면 Billing → Budgets 에 예산 알림(예: 1달러)을 건다.

### 3. 오라클: 네트워크 (2026-11-18 Claude 가 OCI CLI 로 만들었다)
- VCN `byeolmyeong-vcn`(10.0.0.0/16) · 인터넷 게이트웨이 `byeolmyeong-igw` · 공개 서브넷 `byeolmyeong-public-subnet`(10.0.0.0/24) · 보안 목록 `byeolmyeong-public`(인바운드 TCP 22·80·443 과 ICMP). **5432 는 열지 않는다.**
- 콘솔로 다시 만들 때: Networking → Virtual cloud networks → Start VCN Wizard → "Create VCN with Internet Connectivity" → 공개 서브넷의 보안 목록에 TCP 80·443 인바운드를 더한다.

### 4. 오라클: VM 두 대
이 맥에서 키를 먼저 만든다. 사람이 쓰는 키 하나, 배포용 키는 환경마다 하나씩(하나가 새도 다른 VM 은 안전하다).
- 사람이 접속할 키: `ssh-keygen -t ed25519 -f ~/.ssh/byeolmyeong-oci` — 비밀번호를 건다(이미 만들었으면 `ssh-keygen -p -f ~/.ssh/byeolmyeong-oci`).
- GitHub Actions 배포용 키: `ssh-keygen -t ed25519 -N "" -f ~/.ssh/byeolmyeong-deploy-staging`, `ssh-keygen -t ed25519 -N "" -f ~/.ssh/byeolmyeong-deploy-prod`

Compute → Instances → Create instance 를 두 번(`byeolmyeong-staging`, `byeolmyeong-prod`):
- Image: Canonical Ubuntu 24.04 **Minimal**(x86_64)
- Shape: `VM.Standard.E2.1.Micro`(Always Free, 1 OCPU·1GB)
- Networking: `byeolmyeong-vcn` 의 공개 서브넷, "Assign a public IPv4 address" 켬
- SSH keys: `~/.ssh/byeolmyeong-oci.pub` 올리기

만든 뒤 각 VM 에 **그 환경의** 배포용 키를 `restrict` 를 붙여 더한다. 파일 복사·명령 실행만 되고 포트 포워딩·터미널은 막힌다:
`echo "restrict $(cat ~/.ssh/byeolmyeong-deploy-staging.pub)" | ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<스테이징 IP> "cat >> ~/.ssh/authorized_keys"` (운영 VM 은 `-prod` 키와 운영 IP)

### 5. DNS (`byeolmyeong.com` 을 관리하는 곳)
- A 레코드 `api` → 운영 VM 의 공인 IP
- A 레코드 `api-staging` → 스테이징 VM 의 공인 IP
- 버셀 쪽 레코드(`app`·`app.staging`·`admin`)는 버셀이 알려 주는 값으로 넣는다(11번). 프록시는 끈다.
- Cloudflare 를 쓰면 `api`·`api-staging` 은 프록시를 끈다(회색 구름). Caddy 가 인증서를 직접 받아야 하고, 켜려면 먼저 Caddyfile 에 `trusted_proxies` 를 정한다(안 하면 주소별 요청 제한이 Cloudflare 주소로 뭉친다).

### 6. 오라클 Object Storage (운영 백업)
1. Storage → Buckets → Create Bucket: `byeolmyeong-backups`, Standard, 공개 안 함.
2. 수명 규칙이 돌려면 정책이 하나 있어야 한다. Identity & Security → Policies → 루트 구획에 `Allow service objectstorage-ap-chuncheon-1 to manage object-family in tenancy`.
3. 버킷 → Lifecycle Policy Rules → 객체를 30일 뒤 Delete.
4. 버킷 → Pre-Authenticated Requests → Create: 대상 Bucket, 접근 "Permit object writes", 만료는 1년쯤. 만든 URL(끝이 `/o/`)은 한 번만 보인다 → 운영 VM `backup.env` 의 `BACKUP_UPLOAD_URL`(9번). **만료일을 달력에 적는다**(만료되면 백업 업로드가 실패한다).

### 7. Anthropic
- API 키 하나를 운영·스테이징이 같이 쓴다(결정 020 #6). 그 키(워크스페이스)에 **지출 한도를 꼭 건다** — 스테이징은 주소를 아는 누구나 개발용 계정으로 모델을 부를 수 있고, 그 비용이 같은 한도에서 빠진다.
- 두 VM 의 `.env` 에 넣을 때는 맥의 터미널 앱에서 키를 화면에 보이지 않게 받아 넣는 방법을 쓴다(키가 셸 기록·명령줄에 남지 않게). 손으로 하려면 각 VM 에서 `nano /opt/byeolmyeong/.env` 의 `ANTHROPIC_API_KEY=` 뒤에 붙인다.

### 8. VM 초기 설정 (Claude 가 SSH 로)
두 VM 의 IP 와 `~/.ssh/byeolmyeong-oci` 를 알려 주면 Claude 가 한다. 손으로 하려면 저장소 루트에서:
- `scp -i ~/.ssh/byeolmyeong-oci deploy/bootstrap-vm.sh deploy/.env.example deploy/db.env.example deploy/backup.env.example ubuntu@<IP>:~/`
- `ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<IP> "sudo ./bootstrap-vm.sh staging"` (운영 VM 은 `prod`)
- 끝에 `event=bootstrap_done` 한 줄이 나오면 된다. 두 번 돌려도 같다. SSH 가 키로만 로그인되는 상태가 아니면 아무것도 바꾸지 않고 `bootstrap_failed step=ssh_check` 로 멈춘다.
- 9번(`.env`) 뒤에 "새 VM 에 처음 띄우기"(아래 (다))를 한다. 배포 워크플로는 `active_color` 가 없는 VM 에는 올리지 않는다.

### 9. VM 의 `.env` 채우기 (소유자가 직접)
- `ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<IP>`
- `cp ~/.env.example /opt/byeolmyeong/.env && chmod 600 /opt/byeolmyeong/.env && nano /opt/byeolmyeong/.env`
- 이름과 환경별 값의 모양은 `deploy/.env.example` 의 주석에 있다. 기본값이 있는 이름(`CLAUDE_MODEL`·`AUTH_DEV_ENABLED`·`SESSION_COOKIE_SECURE` 등)은 **값이 빈 줄(`이름=`)로 남기지 않는다** — 빈 값이 서버의 기본값을 덮는다. 값에 따옴표·`$` 를 쓰지 않는다(공백·`\n` 은 그대로 넘어간다).
- 소셜 로그인(결정 021) 값은 공급자 콘솔에서 받아 넣는다. 없으면 서버는 뜨고 그 공급자 로그인만 안 된다(스테이징은 개발용 로그인으로 쓸 수 있다). 공급자 콘솔에는 운영·스테이징 웹 주소를 둘 다 등록한다.
- 비밀번호는 VM 마다 새로 만든다: `openssl rand -hex 24`. `DATABASE_USERNAME` 은 `postgres` 가 아닌 이름(예: `byeolmyeong_app`).
- DB 최고 관리자 비밀번호는 `.env` 가 아니라 `db.env` 에 둔다(서버 컨테이너가 보지 않게): `cp ~/db.env.example /opt/byeolmyeong/db.env && chmod 600 /opt/byeolmyeong/db.env && nano /opt/byeolmyeong/db.env`
- 운영 VM 만: 백업 업로드 URL 은 `.env` 가 아니라 `backup.env` 에 둔다(서버 컨테이너가 보지 않게). `cp ~/backup.env.example /opt/byeolmyeong/backup.env && chmod 600 /opt/byeolmyeong/backup.env && nano /opt/byeolmyeong/backup.env`

### 10. GitHub (저장소 Settings → Secrets and variables → Actions)
- `develop` 브랜치가 없으면 소유자가 GitHub 화면에서 `main` 으로부터 만든다(Branches → New branch). Claude 는 보호 브랜치로 push 하지 않는다(`guard.sh`).
- 배포 워크플로(`workflow_run`)는 **저장소 기본 브랜치에 있는 `deploy.yml`** 로 돈다. 그래서 이 파일이 기본 브랜치에 들어가기 전에는 배포가 일어나지 않고, 기본 브랜치가 `main` 이면 스테이징 배포도 `main` 의 워크플로로 돈다. **기본 브랜치는 `develop` 으로 둔다**(결정 020 #4, Settings → General → Default branch). PR 의 기본 대상도 `develop` 이 되어 실수로 운영에 바로 합치는 일이 줄어든다. 워크플로 변경은 `develop` 에 합치면 바로 적용된다.
- Secrets: `STAGING_SSH_HOST`·`PROD_SSH_HOST`(VM 공인 IP), `STAGING_SSH_KEY`(`~/.ssh/byeolmyeong-deploy-staging` 파일 내용)·`PROD_SSH_KEY`(`~/.ssh/byeolmyeong-deploy-prod`), `STAGING_SSH_KNOWN_HOSTS`·`PROD_SSH_KNOWN_HOSTS`(`ssh-keyscan -t ed25519 <IP>` 의 출력 한 줄). 명령으로 넣으면 값이 화면에 나오지 않는다: `gh secret set STAGING_SSH_KEY < ~/.ssh/byeolmyeong-deploy-staging`.
- 등록한 뒤 이 맥의 배포용 **개인 키는 지운다**: `rm ~/.ssh/byeolmyeong-deploy-staging ~/.ssh/byeolmyeong-deploy-prod`(`.pub` 은 남긴다). 다시 필요하면 새로 만들어 VM 의 authorized_keys 와 GitHub 비밀값을 함께 바꾼다.
- Variables: `STAGING_BASE_URL` = `https://api-staging.byeolmyeong.com`, `PROD_BASE_URL` = `https://api.byeolmyeong.com`.
- Variables: `DEPLOY_ENABLED` = `true` 는 **"배포 전 확인" 계획(`docs/plans/2026-11-19-pre-deploy-hardening.md`)이 끝난 뒤에** 넣는다. 없거나 `true` 가 아니면 배포 워크플로는 건너뜀으로 끝난다(결정 020 #7).

### 11. 버셀 (앱·관리 화면 저장소의 일)
- 브랜치: 버셀의 운영 브랜치(Production Branch)는 `main`. `develop` 브랜치에 스테이징 도메인(앱 `app.staging.byeolmyeong.com`)을 붙인다(Settings → Domains → 도메인의 Git Branch 를 `develop` 으로). 관리 화면은 스테이징이 없다 — `main` 만 빌드한다(관리 화면 저장소 `vercel.json`). 앱·관리 화면 저장소에도 `develop` 브랜치가 있다(2026-11-19 만듦).
- 서버 주소: 앱 `EXPO_PUBLIC_API_URL` 을 운영(Production)에는 `https://api.byeolmyeong.com`, `develop` 브랜치(Preview, 브랜치 지정)에는 `https://api-staging.byeolmyeong.com` 으로. 관리 화면 `VITE_API_URL` 은 운영 `https://api.byeolmyeong.com` 하나.
- 서버는 출처를 정확히 일치로만 허용한다. 미리보기마다 바뀌는 주소가 아니라 위 "한눈에" 표의 고정 도메인을 서버 `.env` 에 넣는다:
  - 운영: `ALLOWED_ORIGINS=https://app.byeolmyeong.com`, `ADMIN_ALLOWED_ORIGINS=https://admin.byeolmyeong.com`
  - 스테이징: `ALLOWED_ORIGINS=https://app.staging.byeolmyeong.com`, `ADMIN_ALLOWED_ORIGINS` 는 관리 화면 스테이징이 없어 쓰는 곳이 없다(이미 넣은 값은 그대로 둬도 된다)
  - 웹은 `bm_session` 쿠키로 자격 증명을 보내므로 정확한 출처가 필요하다(버셀 임시 주소 `*.vercel.app` 는 넣지 않는다). `app.*` ↔ `api.*` 는 같은 사이트(`byeolmyeong.com`)라 쿠키가 오간다(결정 021).
- 버셀 Hobby 는 비상업용만이다(결정 020). 결제·수익이 생기기 전에 다시 본다.

## (나) 평소 운영
VM 에서 하는 명령은 모두 `cd /opt/byeolmyeong` 뒤에 한다. 접속: `ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<IP>`.

| 하려는 일 | 방법 |
|---|---|
| 스테이징에 올리기 | 작업 브랜치 → `develop` 으로 PR → CI 통과 확인 → 합치기. Actions 의 `deploy` 가 알아서 올린다 |
| 운영에 올리기 | 스테이징에서 확인 → `develop` → `main` PR → CI 통과 확인 → 합치기(합치는 것이 "사람의 확인"이다). jar 를 바꾸기 직전에 DB 를 백업한다 |
| 배포가 실패하면 | 새 색이 5분 안에 `/health` 200 이 아니거나 Caddy 다시 읽기가 실패하면 `swap.sh` 가 새 색을 내리고 실패로 끝난다. **받는 쪽은 옛 색 그대로라 되돌릴 것이 없다**(사용자는 끊김을 보지 않는다). 배포 직전 백업이 실패해도 서버는 그대로 두고 끝난다(설정 파일은 이미 올라가 있다). `step=reload` 면 올라간 Caddyfile 이 틀린 것이다 — 고쳐 다시 배포한다(그대로 두면 Caddy 가 다시 시작할 때 뜨지 못한다). 원인은 `docker compose logs --tail 100 api-<새 색>` 과 Actions 로그의 `event=swap_failed step=…` |
| 되돌리기(바로 앞 버전) | `./swap.sh app.jar.prev` (끊김 없음). `app.jar.prev` 는 받는 jar 가 바뀔 때만 밀려난다 — `--restart` 나 실패한 배포는 건드리지 않는다. 다시 한 번 하면 되돌리기 전 버전으로 돌아간다 |
| 되돌리기(더 이전 버전) | Actions → `deploy` → Run workflow → 대상·이전 커밋(또는 브랜치). 수동 실행은 빌드·테스트를 다시 돌린다. 끊김 없다. **DB 는 되돌리지 않는다.** 결정 046 앞의 커밋이면 jar 만 바꾸고 그 커밋의 설정 파일은 올리지 않는다(블루 · 그린 VM 을 깬다) |
| DB 까지 되돌려야 할 때(운영) | ① `./restore.sh backups/byeolmyeong-predeploy-<시각>.dump --into-live` — 한 트랜잭션 안에서 public 스키마를 비우고 덤프를 넣으므로 새 마이그레이션이 만든 표도 남지 않는다. 끝나면 서버는 **멈춘 채로** 둔다(지금 jar 로 뜨면 그 마이그레이션이 다시 적용된다). ② Actions → `deploy` → Run workflow → 대상 `prod`·배포 전 커밋 → 그 jar 로 서버가 뜬다. 마이그레이션은 앞으로만 호환되게 쓴다(열·표를 지우거나 이름을 바꾸는 일은 두 번의 배포로 나눈다) |
| 상태 | `docker compose ps` (`postgres`·`caddy`·받는 색 하나가 Up, 쉬는 색은 Exited) · `cat active_color` · `curl -s https://<서버 주소>/health` |
| 로그 | `docker compose logs --tail 200 api-$(cat active_color)` (`caddy`·`postgres` 도 같다) |
| `.env` 를 바꾼 뒤 | `./swap.sh --restart` — 지금 jar 를 쉬는 색으로 새로 만들어(새 값을 읽는다) 받는 쪽을 바꾼다. 끊김 없다 |
| 서버가 계속 다시 시작한다 | `docker compose logs --tail 50 api-$(cat active_color)` 에 `event=startup_refused reason=dev_login_outside_local_staging` 이면 개발용 로그인(`AUTH_DEV_ENABLED=true`)이 켜졌는데 `DEPLOY_ENV` 가 `staging` 이 아니다(결정 022). 운영이면 `AUTH_DEV_ENABLED` 를 지우고, 스테이징이면 `DEPLOY_ENV=staging` → `./swap.sh --restart`(새 색이 뜨지 않으면 받는 쪽은 그대로다) |
| DB 비밀번호 바꾸기 | 점검 모드를 켠다(결정 017, Postgres 를 다시 만들면 끊긴다) → `docker compose exec postgres psql -U postgres` 로 들어가 `\password <DB 사용자>` (새 값이 셸 기록·프로세스 목록에 남지 않는다) → `.env` 의 `DATABASE_PASSWORD` 를 같은 값으로 → `docker compose up -d --no-deps --force-recreate postgres` (Postgres 의 상태 검사도 이 값으로 접속한다. `.env` 만 바꾸면 Postgres 는 처음 값을 그대로 쓴다) → `./swap.sh --restart` → 점검 모드를 끈다 |
| 첫 관리자 만들기(운영) | `.env` 의 `ADMIN_BOOTSTRAP_*` 주석을 풀고 값 → `./swap.sh --restart` → 관리 화면 로그인 확인 → 다시 주석 → `./swap.sh --restart` |
| Postgres · Caddy 업데이트 | 앱 배포는 둘을 받지도 다시 만들지도 않는다(결정 046, 패치까지 태그 고정). 올릴 때: `compose.yaml` 의 태그를 PR 로 바꿔 합친다(배포가 파일을 올린다) → VM 에서 `docker compose pull postgres caddy` → 점검 모드를 켠다(결정 017) → `docker compose up -d --no-deps postgres caddy` → `docker compose ps` · `curl -s https://<서버 주소>/health` → 점검 모드를 끈다. 서버 이미지(JRE)는 배포가 받는다(쉬는 색만 새로 만들어 끊기지 않는다). 배포를 기다리지 않으려면 `docker compose pull api-blue && ./swap.sh --restart` |
| 백업 확인(주 1회) | `tail -n 3 backups/backup.log` 에 `event=backup_done kind=daily`, 버킷에 오늘 덤프. 실패 알림은 없다. 덤프 이름은 매일 `byeolmyeong-daily-<시각>.dump`, 배포 직전 `byeolmyeong-predeploy-<시각>.dump` 이고 VM 에는 종류마다 7개 남는다 |
| 손으로 백업 | `./backup.sh` (매일 백업과 같은 종류) |
| 복원 연습 | `./restore.sh backups/<파일>` → 표별 행 수(일회용 컨테이너·볼륨은 지워진다). 실제 DB 와 몇 표만 견준다: `docker compose exec -T postgres psql -U <DB 사용자> -d byeolmyeong -tAc "select count(*) from accounts"` |
| 비상 복원 | `./restore.sh backups/<파일> --into-live` (확인 문구 → 지금 DB 를 `backups/before-restore-*.dump` 로 남김 → 덤프를 끝까지 풀어 봄(잘렸으면 여기서 멈추고 아무것도 안 바뀜) → 서버 두 색을 멈추고 한 트랜잭션으로 교체. 실패하면 DB 는 그대로이고 받던 색이 다시 뜬다. 성공하면 서버는 멈춰 있으니 같은 jar 면 `docker compose start api-$(cat active_color)`) |
| 재부팅 뒤 | `docker compose ps` 로 `postgres`·`caddy`·받는 색이 다시 떴는지 본다(`restart: unless-stopped` — 손으로 내린 쉬는 색은 뜨지 않는다) |
| 처음 초기화가 실패하면 | `docker compose ps` 에서 postgres 가 unhealthy, `docker compose logs postgres` 에 `initdb:` 안내가 있다. 데이터가 아직 없으니 `docker compose down && docker volume rm byeolmyeong_postgres-data` 후 `.env` 를 고치고 다시 띄운다. **데이터가 들어간 뒤에는 볼륨을 지우지 않는다** |
| 서버가 일반 계정으로 붙는지 확인 | `docker compose exec postgres psql -U postgres -tAc "select rolsuper from pg_roles where rolname = '<DB 사용자>'"` → `f`. `t` 이거나 오류면 예전 방식(서버 계정 = 최고 관리자)으로 만든 볼륨이다. 운영 데이터가 없으면 위처럼 다시 만들고, 있으면 덤프 → 새 볼륨 → 복원으로 옮긴다 |

- 운영 덤프는 운영 VM 밖으로 옮기지 않는다(출생 정보). VM 을 잃었을 때만 버킷에서 내려받아 새 VM 에 올리고, 옮긴 컴퓨터의 사본은 바로 지운다. `before-restore-*.dump` 는 자동으로 지워지지 않으니 필요 없어지면 지운다.
- 스테이징 VM 이 회수되면 (가) 4·8·9 와 (다)의 "새 VM 에 처음 띄우기" 를 다시 한다. 데이터는 버린다.

## (다) 블루 · 그린 처음 한 번 (결정 046)
`upstream.caddy`(Caddy 가 보내는 색)는 VM 에만 있다(배포가 올리지 않는다). 처음 한 번 `./swap.sh --init` 이 `api-blue` 로 만든다. 그 뒤로는 `swap.sh` 만 고쳐 쓴다.

### 블루 · 그린으로 처음 옮겨 가기 (옛 방식 `api` 하나에서) — VM 마다 한 번, Caddy 를 다시 만들 때 몇 초 끊긴다
운영은 **소유자 확인을 받고**, 사용자가 적은 시간에 점검 모드를 켜고 한다. 스테이징은 점검 모드 없이 해도 된다(관리 화면이 운영뿐이다). 옮기기 전의 배포(이 절차보다 먼저 결정 046 이 develop 에 합쳐진 뒤의 `develop` · `main` 합치기)는 워크플로가 "옮기지 않은 VM" 으로 아무것도 바꾸지 않고 멈춘다 — 옮긴 뒤 Actions → `deploy` → Run workflow(대상 · 그 브랜치)로 다시 올린다.
1. (맥, 저장소 `develop`) `scp -i ~/.ssh/byeolmyeong-oci deploy/compose.yaml deploy/swap.sh deploy/restore.sh ubuntu@<IP>:/opt/byeolmyeong/` — 돌고 있는 컨테이너는 그대로다(옛 `api` 는 "고아" 경고가 뜬다). **Caddyfile 은 아직 올리지 않는다**(옛 Caddy 가 그 사이 다시 시작하면 `upstream.caddy` 가 붙어 있지 않아 뜨지 못한다).
2. (VM) 지금 jar 로 블루를 옛 서버 옆에 띄운다: `chmod +x swap.sh restore.sh && cp app.jar app-blue.jar && docker compose up -d --no-deps api-blue` → `until docker compose exec -T caddy wget -q -O /dev/null http://api-blue:8080/health; do sleep 2; done`
3. `./swap.sh --init`(받는 쪽 파일을 `api-blue` 로) · `docker compose pull caddy`(고정한 태그를 미리 받아 끊김을 줄인다).
4. 운영: 관리 화면에서 점검 모드를 켠다. 쓰는 중인 생성이 끝날 때까지 기다린다 — 아래가 모두 `| 0` 이면 된다(옛 jar 는 끌 때 생성을 기다리지 않는다). 옛 DB 에 아직 없는 표(예: `detail_reports` 는 V22)는 건너뛴다:
   `for t in reports detail_reports synastry_reports; do docker compose exec -T postgres psql -U <DB 사용자> -d byeolmyeong -tAc "select '$t', count(*) from $t where status = 'WRITING' and active_at > now() - interval '1 minute'" 2> /dev/null; done`
5. (맥) `scp -i ~/.ssh/byeolmyeong-oci deploy/Caddyfile ubuntu@<IP>:/opt/byeolmyeong/` → (VM) Caddy 를 새 마운트 · 태그로 다시 만든다(여기서 몇 초 끊긴다): `docker compose up -d --no-deps caddy` → `curl -s https://<서버 주소>/health` 200 · `docker compose exec -T caddy wget -qO- http://127.0.0.1:2019/config/apps/http/servers | grep -o 'api-blue:8080'`
6. `echo blue > active_color && cp app-blue.jar app.jar.good`
7. 옛 서버를 내리고 지운다: `docker stop -t 60 byeolmyeong-api-1 && docker rm byeolmyeong-api-1 && rm -f app.jar app.jar.new`
8. 운영: 점검 모드를 끈다. `./swap.sh --restart` 한 번으로 끊김 없이 바뀌는지 본다(`event=swap_done active=green`).

### 새 VM 에 처음 띄우기
(가) 8·9 뒤에:
1. (맥) `./gradlew bootJar && scp -i ~/.ssh/byeolmyeong-oci -r deploy/compose.yaml deploy/Caddyfile deploy/swap.sh deploy/backup.sh deploy/restore.sh deploy/initdb build/libs/app.jar ubuntu@<IP>:/opt/byeolmyeong/`
2. (VM) `chmod +x swap.sh backup.sh restore.sh initdb/*.sh && ./swap.sh --init && docker compose up -d postgres caddy`
3. `./swap.sh app.jar && rm app.jar` → `event=swap_done`. 그 뒤로는 배포 워크플로가 올린다.
