---
type: decision
tags: [궁합, 궁합 삭제, DELETE synastry, 방향성 제시, 공유 카드, 이벤트, SynastryDeleted]
status: active
last_reviewed: 2026-11-12
superseded_by:
---
# 019. 사용자가 궁합 한 건을 지울 수 있다 (리포트와 그 궁합의 방향성 제시도 함께, 공유 카드는 그대로)

> 상태: 활성 · 결정일 2026-11-12(소유자) · 계약은 docs/architecture.md "API 계약" 의 `DELETE /synastry/{id}`

## 맥락
- 궁합은 만들 수만 있고 지울 수 없다. 랭킹에서 빼고 싶은 사람이 있어도 계정을 지우는 수밖에 없다.

## 결정
1. `DELETE /synastry/{id}`(세션, 소유자만) → 204. 남의 것·없는 것·UUID 가 아닌 값은 같은 404 `NOT_FOUND`.
2. **함께 지워지는 것**: 궁합 행, 그 궁합의 리포트(`synastry_reports` — 외래 키의 `on delete cascade`), 그 궁합에 붙은 방향성 제시(`directions` 의 `topic = SYNASTRY`·`target = 궁합 id` 행 — 글자로만 가리켜 외래 키가 없다).
3. **방향성 제시는 이벤트로 지운다**: `synastry` 가 `synastry.SynastryDeleted(ownerId, synastryId)` 를 내고 `direction` 이 듣고 자기 표의 행을 지운다. 궁합 삭제와 같은 트랜잭션 안이다(듣는 쪽이 실패하면 궁합도 지워지지 않는다). ArchUnit 의 이벤트 목록에 더한다.
   - `synastry` 가 `directions` 표를 자기 SQL 로 지우는 길도 규칙은 통과하지만 택하지 않았다: 지금까지 다른 기능의 표는 **읽기만** 했다(`direction` → `synastries`, `admin` → 수치). 남의 표를 쓰기 시작하면 `directions` 의 모양을 바꿀 때 `synastry` 가 조용히 깨진다.
4. **공유 카드는 건드리지 않는다**: 이미 만든 궁합 점수·랭킹 카드는 공유한 순간의 스냅샷이고(결정 012) 궁합을 지워도 그대로 열린다. 지우려면 `DELETE /shares/{shareId}` 로 따로 지운다.
5. **쓰는 중인 리포트는 되살아나지 않는다**: 뒤쪽 일꾼은 모델을 부르기 전과 저장할 때 "그 행이 아직 자기 생성의 것인지" 를 보는데(이미 있는 규칙), 행이 없으니 그만두고 아무것도 넣지 않는다.
6. 감사 기록 `SYNASTRY_DELETED`(대상 = 궁합 id, 결정 015).

## 영향·남은 틈
- 삭제와 같은 순간에 그 궁합의 방향성 제시를 **처음 만드는 중인** 요청이 있으면, 삭제가 끝난 뒤에 글이 저장돼 가리키는 궁합이 없는 `directions` 행이 하나 남을 수 있다. 그 행은 어떤 경로로도 읽히지 않고(궁합이 없어 404 가 먼저다) 계정을 지우면 함께 지워진다. 막으려면 방향성 제시의 저장이 궁합 행을 잠가야 하는데, 드문 일에 비해 무겁다.
- 궁합을 지우면 그 궁합으로 만든 점수는 랭킹에서 바로 빠진다(랭킹은 저장하지 않고 `synastries` 로 만든다).
