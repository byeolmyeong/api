---
type: decision
tags: [출생 정보, 태어난 곳, 시각 모름, placeId, 프로필, 마이그레이션]
status: active
last_reviewed: 2026-11-11
superseded_by:
---
# 007. 태어난 시각을 모르면 태어난 곳을 받지도 저장하지도 않는다 (앱 결정 020 의 2번)

> 상태: 활성 · 결정일 2026-11-11 · 앱 쪽 기록: 앱 저장소 `docs/decisions/020` 의 2번 · docs/architecture.md 의 `POST /charts`·`GET`·`PUT /profile` 계약을 바꾼다. 020 의 1번(관계 유형 `FAMILY`)은 궁합(태스크 9)을 만들 때 반영한다.

## 맥락
- 태어난 곳은 상승점·하우스 계산에만 쓰인다. 시각을 모르면 서버는 그것들을 계산하지 않고(`computeChart` 의 `timeKnown = false`), "같은 차트" 해시(`ChartInput.hash`)에도 위경도를 넣지 않는다. 글 생성 프롬프트에는 원래 장소가 들어가지 않는다.
- 그런데 `POST /charts`·`PUT /profile` 은 `time` 이 `null` 이어도 `placeId` 를 필수로 받았고(없으면 400 `UNKNOWN_PLACE`), `profiles.place_id` 는 `not null` 이었다. 쓰지 않는 개인정보를 받아 두고 있었다.

## 결정
- `POST /charts`·`PUT /profile`: `time` 이 `null`(또는 키 없음)이면 `placeId` 는 없어도 된다. 보내도 읽지 않는다 — 목록에 없는 id 여도 400 이 아니고, 저장하지 않는다. `time` 이 있으면 지금처럼 필수이고 없거나 목록에 없으면 400 `UNKNOWN_PLACE` 다.
- `GET /profile`·`PUT /profile` 의 응답은 `time` 이 `null` 이면 `placeId: null` 이다.
- 시각을 모르는 날의 기준 시각은 그대로 한국 표준시 정오다.
- `profiles.place_id` 를 비어 있을 수 있게 한다(V5). 같은 마이그레이션에서 이미 저장된 시각 모름 행의 `place_id` 를 비우고, "시각이 있으면 곳도 있고 시각이 없으면 곳도 없다" 를 check 제약으로 둔다.

## 근거
- 쓰지 않는 개인정보는 받지 않는다(앱 020).
- 이미 저장된 행을 비워도 차트·해시가 그대로다(시각 모름의 해시는 `v1|epoch|time-unknown`). 저장된 요약 해석·리포트는 계속 같은 차트의 것으로 찾아진다.
- check 제약이 있으면 "시각은 있는데 곳이 없는 행" 을 코드가 따로 다룰 일이 없다.

## 대안
- 시각을 몰라도 보낸 `placeId` 를 저장해 두기 — 앱 020 이 버린 대안이다.
- 시각 모름에 `placeId` 가 오면 400 — 옛 앱이 보내는 값을 거절하게 된다. 앱 020 은 "있어도 쓰지 않는다" 로 정했다.
- 이미 저장된 행은 그대로 두기 — `GET /profile` 이 옛 행에만 `placeId` 를 주게 되어 계약이 둘로 갈린다.

## 결과·재검토 조건
- 앱은 `GET /profile` 의 `placeId` 가 `null` 일 수 있다고 보고, 시각 모름일 때 `placeId` 를 보내지 않는다.
- 해외 출생을 받게 되면(시각 모름의 기준 시각이 곳의 시간대에 달리게 되면) 이 결정을 다시 본다.
