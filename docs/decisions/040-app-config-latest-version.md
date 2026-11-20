---
type: decision
tags: [앱 설정, app config, 최신 버전, latestVersion, 업데이트 권유, 최소 버전, 관리자]
status: active
last_reviewed: 2026-11-20
superseded_by:
---
# 040. 앱 설정에 플랫폼별 최신 버전(latestVersion)을 둔다 — 앱이 권유를 고르고, 막기는 그대로 minVersion 이다

> 상태: 활성 · 결정일 2026-11-20(앱 결정 059 "업데이트는 두 단계" — 소유자 "근데 무조건 업데이트가 필요한 상황도 있잖아") · 결정 016 에 값 하나를 더한다(016 의 나머지 — 426 막기 · 버전 규칙 · 캐시 · 감사 기록 — 는 그대로) · 계획: 앱 저장소 `docs/plans/2026-11-20-sian-all-repos.md` S8-3 · 계약은 docs/architecture.md "API 계약" 의 "앱 설정과 강제 업데이트" 와 "관리자 API" 의 "앱 설정"

## 맥락
- 016 은 플랫폼마다 최소 버전(`minVersion`)과 막기(`blockNow`)만 둔다. 막지 않는 `minVersion` 이 "권하기" 를 겸했다.
- 앱 결정 059 는 업데이트를 두 단계로 나눈다: 새 버전이 나오면 한 번 권하고(17), 최소 버전보다 낮을 때만 막는다(17-1). 권할 기준 버전이 따로 있어야 한다. 앱(A⑥)은 이미 `latestVersion` 을 읽고, 옛 서버면 `null` 로 본다.

## 결정
1. **저장**(마이그레이션 V24 — 계획은 V26 또는 V27 로 적었으나 develop 의 다음 번호): `app_configs.latest_version text null`. 더하기만 한다.
2. **공개 조회** `GET /app-config` 의 `PlatformConfig` 에 `latestVersion: string | null` 을 더한다(이 응답은 키를 빼지 않는다). 서버는 이 값으로 아무것도 막지 않는다 — 권유 창을 띄울지는 앱이 고른다(059 의 2). 426 은 016 그대로 `minVersion` · `blockNow` 로만.
3. **관리자** `GET /admin/app-config` 의 `PlatformConfig` 에 `latestVersion`. `PUT /admin/app-config/{platform}` 본문은 다섯 키 `{ minVersion, latestVersion, storeUrl, message, blockNow }`:
   - 버전 규칙은 016 의 4 와 같다(같은 함수 `web.parseAppVersion`). 틀리면 400 `INVALID_LATEST_VERSION`.
   - `minVersion` 과 `latestVersion` 이 둘 다 있고 최신이 최소보다 **낮으면** 400 `LATEST_BELOW_MIN`(같으면 된다). 최소보다 낮은 최신은 뜻이 없고, 앱이 권유와 막기를 함께 판정하며 헷갈린다.
   - `latestVersion` 키가 **없으면 지금 값을 그대로 둔다** — 네 키를 보내는 지금 관리 화면이 400 을 받지 않게(옛 화면 호환). `null` 을 보내야 지운다. 키가 없을 때의 `LATEST_BELOW_MIN` 은 저장된 최신 버전으로 본다(최소 버전만 올려 최신보다 높아지면 400). 다른 네 키는 016 그대로 모두 있어야 한다.
   - 판정 순서: `INVALID_REQUEST` → `INVALID_VERSION` → `INVALID_LATEST_VERSION` → `INVALID_STORE_URL` → `INVALID_MESSAGE` → `BLOCK_REQUIRES_MIN_VERSION` → `LATEST_BELOW_MIN`. 거절된 요청은 아무것도 바꾸지 않는다.
4. **감사 기록** `APP_CONFIG_UPDATED` 의 detail 에 `latestVersion` 을 더한다(값이 있을 때만 — `minVersion` 과 같은 방식).
5. 캐시(5초) · 이벤트 `admin.AppConfigChanged` · `GET /app-config` 의 60초 캐시는 016 그대로다.

## 근거
- 권유와 막기를 두 값으로 나누면 평소에는 `latestVersion` 만 올리고, 서버 계약이 깨지는 판에만 `minVersion` + `blockNow` 를 쓴다(059 의 4).
- 키가 없을 때 그대로 두면 서버를 먼저 배포해도 지금 관리 화면의 저장이 깨지지 않는다. 새 화면은 다섯 키를 보낸다.

## 대안
- `latestVersion` 을 필수 키로 — 네 키를 보내는 지금 관리 화면의 저장이 모두 400 이 된다.
- 최신 < 최소 를 허용 — 위 3 의 둘째 줄.
- 권유 기준을 서버가 판정해 내려 주기(`suggestUpdate: true`) — 서버는 요청한 앱의 버전을 늘 알지 못한다(웹 · 헤더 없음). 앱이 자기 버전과 견준다(059).

## 결과·재검토 조건
- 어드민 D1-2 가 플랫폼마다 "최신 버전" 칸을 더한다. 앱 A⑥ 은 이미 읽는다.
- 모든 관리 화면이 다섯 키를 보내게 되면(옛 화면이 사라지면) 키 없음 = 그대로 를 다시 볼 수 있다 — 지금은 둔다.
