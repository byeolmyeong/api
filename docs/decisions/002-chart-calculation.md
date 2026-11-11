---
type: decision
tags: [차트 계산, astronomy-engine, Swiss Ephemeris, 라이선스]
status: active
last_reviewed: 2026-11-10
superseded_by:
---
# 002. 차트는 서버에서 astronomy-engine 위에 직접 계산한다

> 상태: 활성 · 결정일 2026-11-10 · 앱 쪽 기록: 앱 저장소 `docs/decisions/006-server-side-chart.md` · 일부 대체: "로그인 없이 받는다"는 docs/decisions/003-follow-app-decisions-2026-11-11.md 가 대체

## 맥락
궁합 초대에서는 상대의 생년월일이 나에게 보이면 안 되고, 리포트는 사용자가 화면을 떠난 뒤 서버가 쓴다. 그래서 계산은 서버에 있어야 한다. 업계 표준인 Swiss Ephemeris 는 AGPL 이라 무료로 쓰면 서버 코드를 공개해야 하고, 상용 라이선스는 유료다(2026-11 기준 6년에 700 스위스 프랑으로 확인, 구매 전 재확인 필요).

## 결정
행성 위치는 `astronomy-engine` 의 Kotlin 판(`io.github.cosinekitty:astronomy`, JitPack, MIT)으로 구하고, 상승점·중천·플라시더스 하우스·각도·교점·릴리스·행운점·변곡점 연도는 `chart` 패키지에 직접 구현한다. 1.0.0 은 이 구성으로 낸다.

## 근거
- MIT 라 비용과 소스 공개 의무가 없다.
- 하우스·각도는 공식이 공개된 구면 삼각법 계산이라 기준값 테스트로 검증할 수 있다.
- 계산을 `chart` 패키지 한 곳에 모으면 나중에 Swiss Ephemeris 로 바꿀 때 그 패키지만 고친다.

## 대안
- Swiss Ephemeris 상용 라이선스 — 하우스·키론까지 검증된 계산을 바로 쓴다. 키론이 꼭 필요하거나 직접 구현의 정확도가 기준에 못 미치면 산다.
- Swiss Ephemeris 무료판 — 서버 코드를 공개해야 한다.
- 기기 안 계산 — 궁합 초대의 개인정보 규칙을 지킬 수 없다.

## 결과·재검토 조건
- 정확도는 우리 책임이다. 기준값: NASA JPL Horizons(앱 저장소 `docs/raw/2026-11-10-jpl-horizons-reference.md`). 허용 오차: 행성 황경 0.01도, 하우스 경계 0.1도.
- 키론은 astronomy-engine 에 없다. 1.0.0 에서는 넣지 않고 [확인 필요]로 둔다.
- 플라시더스는 위도 약 66도 이상에서 계산되지 않는다. 국내 출생지만 받는 동안은 해당 없다.
- 하우스 계산이 세 번 고쳐도 기준에 못 미치면 Swiss Ephemeris 상용 라이선스로 바꾼다.
