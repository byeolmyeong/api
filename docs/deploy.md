---
type: runbook
tags: [배포, 운영, 오라클 클라우드, 스테이징, 백업, 복원]
status: active
last_reviewed: 2026-11-18
---
# 배포·서버 운영

> 결정 docs/decisions/020-hosting-oracle-vercel.md · 계획 docs/plans/2026-11-18-deploy-oracle.md
> 무료 한도·요금은 결정 020 의 "무료 한도·요금" 절이 단일 출처다. 이 문서에 숫자를 옮겨 적지 않는다.
> IP·키·비밀번호·URL 값은 이 문서에 적지 않는다. 이름만 적는다.

## 한눈에
| | 운영(prod) | 스테이징(staging) |
|---|---|---|
| 서버 주소 | `https://api.byeolmyeong.com` | `https://api-staging.byeolmyeong.com` |
| 앱 웹·관리 화면(버셀) | `byeolmyeong.com` · `admin.byeolmyeong.com` | `staging.byeolmyeong.com` · `admin-staging.byeolmyeong.com` |
| 오라클 VM 이름 | `byeolmyeong-prod` | `byeolmyeong-staging` |
| 올라가는 때 | `v*` 태그를 달 때(빌드·테스트를 다시 돌린 뒤) | `main` 에 합쳐지고 CI 가 통과할 때 |
| DB 백업 | 매일 04:00 → VM 7개 + Object Storage 30일 | 없음(버려도 되는 데이터) |
| 개발용 로그인 | 끔 | 켬 |

VM 안: `/opt/byeolmyeong/` 에 `compose.yaml`·`Caddyfile`·`backup.sh`·`restore.sh`·`.env`·`app.jar`·`app.jar.prev`·`backups/`. 컨테이너는 `api`(서버)·`postgres`·`caddy`(HTTPS).

## (가) 처음 한 번 — 순서대로

### 1. 오라클: 예전 자원 정리 (필요할 때만)
지운 것은 되돌릴 수 없다. 남길 데이터가 있으면 먼저 뺀다.
1. Compute → Instances: 쓰지 않는 인스턴스를 Terminate. **"Permanently delete the attached boot volume" 체크**(무료 블록 스토리지를 돌려받는다).
2. Storage → Block Storage → Boot Volumes: 주인 없이 남은 볼륨 삭제.
3. Networking → Virtual cloud networks: 쓰지 않는 VCN 삭제(서브넷·인터넷 게이트웨이·보안 목록이 같이 정리된다).
4. Networking → Reserved public IPs: 남은 예약 IP 삭제.

### 2. 오라클: 한도 확인
- 홈 리전이 춘천(`ap-chuncheon-1`) 또는 서울인지 본다. 무료 자원은 홈 리전에서만 나온다.
- Governance → Limits, Quotas and Usage → Compute 에서 `VM.Standard.A1.Flex` 의 OCPU·메모리 한도를 본다. VM 한 대는 그 **절반**으로 만든다.
- 무료 계정은 쉬는 VM 이 회수될 수 있다. 종량제(Pay As You Go)로 올리면 회수 대상에서 빠지고 무료 범위 안이면 0원이다. 올리면 Billing → Budgets 에 예산 알림(예: 1달러)을 건다.

### 3. 오라클: 네트워크
1. Networking → Virtual cloud networks → Start VCN Wizard → "Create VCN with Internet Connectivity". 이름 `byeolmyeong-vcn`.
2. 만든 VCN → Public subnet → 보안 목록(Default Security List) → Add Ingress Rules: 소스 `0.0.0.0/0`, TCP, 대상 포트 `80` 하나, `443` 하나. 22 는 기본으로 열려 있다. **5432 는 열지 않는다.**

### 4. 오라클: VM 두 대
이 맥에서 키를 먼저 만든다(사람이 쓰는 키와 배포용 키를 나눈다).
- 사람이 접속할 키: `ssh-keygen -t ed25519 -f ~/.ssh/byeolmyeong-oci`
- GitHub Actions 배포용 키: `ssh-keygen -t ed25519 -f ~/.ssh/byeolmyeong-deploy -N ""`

Compute → Instances → Create instance 를 두 번(`byeolmyeong-staging`, `byeolmyeong-prod`):
- Image: Canonical Ubuntu 24.04 (A1 을 고르면 aarch64 판이 잡힌다)
- Shape: Ampere → `VM.Standard.A1.Flex`, OCPU·메모리는 2번에서 정한 절반
- Networking: `byeolmyeong-vcn` 의 public subnet, "Assign a public IPv4 address" 켬
- SSH keys: `~/.ssh/byeolmyeong-oci.pub` 올리기
- "Out of capacity" 가 나면 시간을 바꿔 다시 시도한다(무료 ARM 은 자리가 자주 찬다).

만든 뒤 각 VM 에 배포용 키를 더한다: `ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<IP> "cat >> ~/.ssh/authorized_keys" < ~/.ssh/byeolmyeong-deploy.pub`

### 5. DNS (`byeolmyeong.com` 을 관리하는 곳)
- A 레코드 `api` → 운영 VM 의 공인 IP
- A 레코드 `api-staging` → 스테이징 VM 의 공인 IP
- 버셀 쪽 레코드(`@`·`admin`·`staging`·`admin-staging`)는 버셀이 알려 주는 값으로 넣는다(11번).
- Cloudflare 를 쓰면 `api`·`api-staging` 은 처음엔 프록시를 끈다(회색 구름). Caddy 가 인증서를 직접 받아야 한다.

### 6. 오라클 Object Storage (운영 백업)
1. Storage → Buckets → Create Bucket: `byeolmyeong-backups`, Standard, 공개 안 함.
2. 수명 규칙이 돌려면 정책이 하나 있어야 한다. Identity & Security → Policies → 루트 구획에 `Allow service objectstorage-ap-chuncheon-1 to manage object-family in tenancy` (홈 리전이 서울이면 `ap-seoul-1`).
3. 버킷 → Lifecycle Policy Rules → 객체를 30일 뒤 Delete.
4. 버킷 → Pre-Authenticated Requests → Create: 대상 Bucket, 접근 "Permit object writes", 만료는 1년쯤. 만든 URL(끝이 `/o/`)은 한 번만 보인다 → 운영 VM `.env` 의 `BACKUP_UPLOAD_URL`. **만료일을 달력에 적는다**(만료되면 백업 업로드가 실패한다).

### 7. Anthropic
- Console 에서 워크스페이스를 둘(운영·스테이징) 만들고 각각 API 키를 발급한다. 스테이징 워크스페이스에는 지출 한도를 건다. 스테이징은 주소를 아는 누구나 개발용 계정으로 모델을 부를 수 있다(결정 020).

### 8. VM 초기 설정 (Claude 가 SSH 로)
두 VM 의 IP 와 `~/.ssh/byeolmyeong-oci` 를 알려 주면 Claude 가 한다. 손으로 하려면 저장소 루트에서:
- `scp -i ~/.ssh/byeolmyeong-oci deploy/bootstrap-vm.sh deploy/.env.example ubuntu@<IP>:~/`
- `ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<IP> "sudo ./bootstrap-vm.sh staging"` (운영 VM 은 `prod`)
- 끝에 `event=bootstrap_done` 한 줄이 나오면 된다. 두 번 돌려도 같다.

### 9. VM 의 `.env` 채우기 (소유자가 직접)
- `ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<IP>`
- `cp ~/.env.example /opt/byeolmyeong/.env && chmod 600 /opt/byeolmyeong/.env && nano /opt/byeolmyeong/.env`
- 이름과 환경별 값의 모양은 `deploy/.env.example` 의 주석에 있다. **값이 빈 줄(`이름=`)을 남기지 않는다** — 빈 값이 서버의 기본값을 덮는다.
- DB 비밀번호는 VM 마다 새로 만든다: `openssl rand -base64 24`.

### 10. GitHub (저장소 Settings → Secrets and variables → Actions)
- Secrets: `STAGING_SSH_HOST`·`PROD_SSH_HOST`(VM 공인 IP), `STAGING_SSH_KEY`·`PROD_SSH_KEY`(`~/.ssh/byeolmyeong-deploy` 파일 내용, 둘 다 같은 키), `STAGING_SSH_KNOWN_HOSTS`·`PROD_SSH_KNOWN_HOSTS`(`ssh-keyscan -t ed25519 <IP>` 의 출력 한 줄).
- Variables: `STAGING_BASE_URL` = `https://api-staging.byeolmyeong.com`, `PROD_BASE_URL` = `https://api.byeolmyeong.com`.

### 11. 버셀 (앱·관리 화면 저장소의 일)
- 서버 주소: 앱 `EXPO_PUBLIC_API_URL`, 관리 화면 `VITE_API_URL` 을 운영 배포에는 `https://api.byeolmyeong.com`, 스테이징 도메인이 붙은 배포에는 `https://api-staging.byeolmyeong.com` 으로.
- 서버는 출처를 정확히 일치로만 허용한다. 미리보기마다 바뀌는 주소가 아니라 위 "한눈에" 표의 고정 도메인을 버셀에 붙이고, 서버 `.env` 에 같은 값을 넣는다:
  - 운영: `ALLOWED_ORIGINS=https://byeolmyeong.com`, `ADMIN_ALLOWED_ORIGINS=https://admin.byeolmyeong.com`
  - 스테이징: `ALLOWED_ORIGINS=https://staging.byeolmyeong.com`, `ADMIN_ALLOWED_ORIGINS=https://admin-staging.byeolmyeong.com`
- 버셀 Hobby 는 비상업용만이다(결정 020). 결제·수익이 생기기 전에 다시 본다.

## (나) 평소 운영
VM 에서 하는 명령은 모두 `cd /opt/byeolmyeong` 뒤에 한다. 접속: `ssh -i ~/.ssh/byeolmyeong-oci ubuntu@<IP>`.

| 하려는 일 | 방법 |
|---|---|
| 스테이징에 올리기 | PR → CI 통과 확인 → 합치기. Actions 의 `deploy` 가 알아서 올린다 |
| 운영에 올리기 | `git tag v0.1.0 <커밋> && git push origin v0.1.0` (태그를 다는 것이 "사람의 확인"이다) |
| 되돌리기(자동) | 배포 뒤 `/health` 가 3분 안에 200 이 아니면 직전 jar 로 돌아가고 작업이 실패로 끝난다 |
| 되돌리기(더 이전 버전) | Actions → `deploy` → Run workflow → 대상·이전 태그 |
| 상태 | `docker compose ps` (셋 다 Up) · `curl -s https://<서버 주소>/health` |
| 로그 | `docker compose logs --tail 200 api` (`caddy`·`postgres` 도 같다) |
| `.env` 를 바꾼 뒤 | `docker compose up -d --force-recreate api` (다시 만들어야 새 값을 읽는다) |
| 첫 관리자 만들기(운영) | `.env` 의 `ADMIN_BOOTSTRAP_*` 주석을 풀고 값 → 위 명령 → 관리 화면 로그인 확인 → 다시 주석 → 위 명령 |
| 백업 확인(주 1회) | `tail -n 3 backups/backup.log` 에 `event=backup_done`, 버킷에 오늘 덤프. 실패 알림은 없다 |
| 손으로 백업 | `./backup.sh` |
| 복원 연습 | `./restore.sh backups/<파일>` → 표별 행 수. 실제 DB 와 몇 표만 견준다: `docker compose exec -T postgres psql -U <DB 사용자> -d byeolmyeong -tAc "select count(*) from accounts"` |
| 비상 복원 | `./restore.sh backups/<파일> --into-live` (확인 문구를 받고, 그동안 서버를 멈춘다. 되돌릴 수 없다) |
| 재부팅 뒤 | `docker compose ps` 로 셋 다 다시 떴는지 본다(`restart: unless-stopped`) |

- 운영 덤프는 운영 VM 밖으로 옮기지 않는다(출생 정보). VM 을 잃었을 때만 버킷에서 내려받아 새 VM 에 올리고, 옮긴 컴퓨터의 사본은 바로 지운다.
- 스테이징 VM 이 회수되면 (가) 4·8·9 를 다시 하고 합치기 한 번으로 올린다. 데이터는 버린다.
