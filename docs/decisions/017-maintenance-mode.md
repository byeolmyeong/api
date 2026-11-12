---
type: decision
tags: [점검, maintenance, 503, MAINTENANCE, 앱 설정, 관리자]
status: active
last_reviewed: 2026-11-12
superseded_by:
---
# 017. 점검 중에는 앱의 모든 요청을 503 으로 답한다 (관리자가 켜고 끈다)

> 상태: 활성 · 결정일 2026-11-12(소유자) · 결정 016(앱 설정·강제 업데이트)에 얹는다 · 계약은 docs/architecture.md "앱 설정과 강제 업데이트"·"관리자 API" 의 "앱 설정"

## 맥락
- DB 작업이나 장애 때 앱에 "점검 중" 을 알릴 길이 없다. 서버를 내리면 앱은 이유 없는 오류만 본다.
- 결정 016 으로 앱이 시작할 때 읽는 공개 설정(`GET /app-config`)과, 모든 요청 앞에 서는 인터셉터가 이미 있다.

## 결정
1. **테이블 `app_maintenance`(V15)**: 행 하나(켜짐 여부·안내 글·고친 때·고친 관리자). 마이그레이션이 꺼진 채로 넣는다. V14 는 이미 커밋돼 있어 고치지 않고 새 번호로 더한다(마이그레이션은 고쳐 쓰지 않는다).
2. **공개 조회**: `GET /app-config` 응답에 `maintenance: { active, message }` 를 더한다.
3. **켜져 있으면 앱의 모든 경로가 503 `{ "error": "MAINTENANCE" }`** 다. 빼는 곳은 강제 업데이트와 같다: `GET /app-config`(앱이 안내 글을 읽는다) · `/health` · `/admin/**`(관리자가 꺼야 한다).
   - 강제 업데이트와 달리 **웹에도 걸린다**(버전 헤더를 보지 않는다).
   - 순서: 점검(503) → 강제 업데이트(426) → 세션 검사(401). 같은 인터셉터(`appconfig.AppUpdateInterceptor`)가 점검을 먼저 본다. 인터셉터라 503 에도 CORS 허용 헤더가 붙고 사전 요청은 건드리지 않는다.
4. **관리자 API**: `GET /admin/app-config` 에 `maintenance: { active, message, updatedAt, updatedBy }` 를 더하고, `PUT /admin/app-config/maintenance`(`{ active, message }` 두 키 모두)로 고친다. 경로의 `maintenance` 는 플랫폼 이름(`ios`·`android`)과 같은 자리지만 글자 그대로의 경로가 먼저 맞는다.
5. 캐시는 앱 설정과 같은 것을 같이 쓴다(5초, 고치면 `admin.AppConfigChanged` 로 바로 비운다).
6. 고칠 때마다 감사 기록 `MAINTENANCE_UPDATED`(켰는지 껐는지만, 안내 글은 남기지 않는다 — 결정 015).

## 버린 대안
- `app_configs` 에 세 번째 행(`platform = 'maintenance'`) — 열의 뜻이 다르고(최소 버전·스토어 주소가 없다) 플랫폼 check 를 풀어야 한다.
- 플랫폼별 점검 — 요청에 없다. 점검은 서버의 상태다.
- 예약(시작·끝 시각) — 요청에 없다. 관리자가 켜고 끈다.

## 영향
- 켜져 있는 동안 뒤에서 쓰던 리포트는 계속 쓰인다(요청만 막는다). 공유 카드의 공개 조회(`GET /shared/*`)도 막힌다.
- [확인 필요] 소유자: 서버가 실제로 내려가 있는 점검(배포·DB 정지)에는 이 답이 나가지 않는다 — 그때는 앞단(프록시·호스팅)의 점검 화면이 필요하다. 이 장치는 서버가 떠 있는 동안의 점검용이다.
