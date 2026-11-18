---
type: decision
tags: [배포, 호스팅, 오라클 클라우드, 버셀, 스테이징, 도메인, 백업]
status: active
last_reviewed: 2026-11-18
superseded_by:
---
# 020. 서버는 오라클 무료 VM 두 대(스테이징·운영), 프론트는 버셀에 올린다

> 상태: 활성 · 결정일 2026-11-18(소유자) · 실행 계획 docs/plans/2026-11-18-deploy-oracle.md · 결정 001 의 "운영 호스팅은 미정이다" 를 대체

## 맥락
- 혼자 운영하고, 출시 전까지 호스팅에 돈을 쓰지 않는다.
- 서버는 늘 켜져 있어야 한다. 심층 리포트는 응답을 돌려준 뒤 서버 메모리의 일꾼이 계속 쓰고(`byeolmyeong.report.*`), 요청 속도 제한도 서버 메모리에서 센다. 요청이 없으면 잠드는 무료 서비스에서는 쓰던 리포트가 끊기고, JVM 이 다시 뜨는 데 수십 초가 걸린다.
- 환경은 셋이다: 로컬(이 맥) · 스테이징(테스트 서버) · 운영. DB 도 환경마다 따로다.
- 도메인 `byeolmyeong.com` 을 소유자가 샀다.

## 결정
1. **서버·DB**: 오라클 클라우드 Always Free 의 `VM.Standard.E2.1.Micro`(AMD x86_64, 1 OCPU 버스트, 메모리 1GB, Ubuntu 24.04 Minimal) 두 대 — 스테이징 한 대, 운영 한 대. 홈 리전 춘천(`ap-chuncheon-1`). 메모리가 작아 스왑 2GB 를 붙인다(`deploy/bootstrap-vm.sh`). 처음엔 ARM(A1) 을 골랐으나 이 테넌시(무료 계정)의 A1 한도가 0 이라 바꿨다(2026-11-18 조회).
   - 실측(로컬, JVM 을 1GB 서버 기준으로 맞춤, 요청 300번 뒤): 서버 314MB · Postgres 43MB · Caddy 19MB. OS·Docker 를 더해도 1GB 안이다. CPU 가 약해 서버 시작이 수십 초 걸린다(배포의 상태 확인은 3분까지 기다린다).
2. **VM 한 대의 구성**: Docker Compose 로 세 컨테이너 — 서버(`eclipse-temurin:21-jre` 이미지에서 `app.jar` 실행) · PostgreSQL 17(`postgres:17`, 호스트 포트를 열지 않음) · Caddy 2(80·443, Let's Encrypt 인증서 자동). 파일은 저장소의 `deploy/`, VM 의 `/opt/byeolmyeong`. 비밀값은 VM 의 `.env` 에만 둔다.
3. **주소**: 서브도메인은 한 단계에 하이픈을 붙인다(와일드카드 인증서·Cloudflare 무료 인증서가 한 단계까지만 덮는다).

   | | 운영 | 스테이징 |
   |---|---|---|
   | 앱 웹(버셀) | `byeolmyeong.com` | `staging.byeolmyeong.com` |
   | 관리 화면(버셀) | `admin.byeolmyeong.com` | `admin-staging.byeolmyeong.com` |
   | 서버(오라클) | `api.byeolmyeong.com` | `api-staging.byeolmyeong.com` |

4. **배포**(GitHub Actions `deploy`): `main` 에 합쳐지고 CI 가 통과하면 스테이징으로, `v*` 태그를 달면 그 커밋으로 빌드·테스트를 다시 돌린 뒤 운영으로. 수동 실행(대상·ref 지정)으로 되돌린다. 배포 뒤 `/health` 가 200 이 아니면 직전 jar 로 자동으로 되돌린다. 배포물은 jar 하나이고 이미지 저장소는 쓰지 않는다.
5. **백업**: 운영 DB 만 매일 04:00(KST) `pg_dump` → VM 에 7개, 오라클 Object Storage 버킷에 30일. 복원 연습은 운영 VM 안의 일회용 컨테이너에서 한다. 스테이징 DB 는 백업하지 않는다.
6. **스테이징 설정**: 개발용 로그인을 켠다(`AUTH_DEV_ENABLED=true`, 소셜 로그인이 아직 없다). 모델은 `claude-sonnet-5-5`, API 키는 스테이징 전용으로 따로 발급하고 지출 한도를 건다. 주소를 아는 누구나 같은 계정으로 들어와 모델을 부를 수 있으므로 손해를 한도 안에 가둔다.
7. **운영 설정**: 개발용 로그인은 켜지 않는다. 켜진 채로 뜨지 못하게 하는 서버 쪽 안전장치는 별도 계획(`docs/plans/2026-11-18-pre-deploy-hardening.md`)이 만들고, 그 계획이 끝나기 전에는 어느 서버에도 첫 배포를 하지 않는다.
8. **프론트**: 앱 웹(Expo 웹 내보내기)과 관리 화면(Vite)은 버셀에 올린다. 버셀 운영 배포 ↔ 운영 서버, 스테이징 브랜치에 고정 도메인을 붙인 배포 ↔ 스테이징 서버. 서버는 출처를 정확히 일치로만 허용하므로(`web/CorsConfig.kt`) 미리보기의 바뀌는 주소가 아니라 위 표의 고정 주소를 `ALLOWED_ORIGINS`·`ADMIN_ALLOWED_ORIGINS` 에 넣는다.

## 무료 한도·요금 (2026-11-18 확인, 이 문서가 단일 출처)
- 오라클 Always Free A1: 2026-06 에 4 OCPU·24GB 에서 2 OCPU·12GB 로 줄었다는 보도가 있다(공식 공지 없음). 실제 값은 콘솔의 한도 화면이 원본이다. **이 테넌시(Free Tier, 개인)는 2026-11-18 조회에서 `standard-a1-core-count`·`standard-a1-memory-count` 가 0, `vm-standard-e2-1-micro-count` 가 2 다.** 쉬는 인스턴스는 7일 기준으로 회수될 수 있다(무료 계정). Object Storage 는 무료 범위 안에서 쓴다.
- 버셀 Hobby: 무료, **비상업적 개인 용도만**. 결제·광고 등 수익이 생기면 Pro 이상이 필요하다.
- Claude API(입력/출력, 1M 토큰당): `claude-opus-5-5` $4/$20 · `claude-sonnet-5-5` $2/$10 · `claude-haiku-5-5` $0.10/$0.50.
- 도메인: 소유자가 이미 샀다. 호스팅 쪽에서 드는 돈은 0 이다.

## 대안
- Render 무료 + Neon 무료: 관리가 없지만 서버가 15분 뒤 잠들고 깨는 데 1분쯤 걸린다. Render 무료 DB 는 30일 뒤 만료된다.
- GCP e2-micro(평생 무료): 메모리 1GB, 미국 리전만. 서버와 DB 를 같이 올리기 빠듯하고 한국에서 멀다.
- Northflank 무료: 잠들지 않지만 자원이 작고 운영 용도로 쓰지 말라고 한다.
- Koyeb 무료: 1시간 뒤 잠들고 미국·유럽만이며 인수 후 신규 가입이 제한됐다.
- AWS·Azure·GCP 크레딧: 6~12개월 또는 90일 뒤 끝난다.
- Supabase 무료(서울): 일주일 활동이 적으면 멈춘다. 외부 DB 는 대개 해외 리전이라 쿼리마다 왕복이 붙는다 → DB 를 서버와 같은 VM 에 둔다.

## 결과·재검토 조건
- 운영체제 업데이트·백업 확인·인증서는 소유자가 맡는다(Caddy 가 인증서를 갱신하고 Ubuntu 자동 보안 업데이트가 켜져 있다). 절차: docs/deploy.md.
- 다시 본다: **출시가 가까워질 때 운영 서버를 더 큰 서버로 옮긴다**(종량제로 올려 A1 이 생기면 A1, 아니면 유료 VPS — 같은 compose 와 백업 복원으로 옮긴다), 오라클이 VM 을 회수하거나 한도를 또 바꿀 때, 결제·수익이 시작될 때(버셀 Pro 또는 Cloudflare Pages), 동시 리포트가 늘어 메모리·CPU 가 모자랄 때(스왑을 계속 쓰거나 응답이 느려짐), 복원 연습이 실패할 때.
