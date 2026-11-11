---
type: architecture
tags: [서버 아키텍처, API 계약, 차트 계산, 해석 생성, 로그인]
status: active
last_reviewed: 2026-11-11
---
# 서버 아키텍처

> 상태: 활성 · 기준 2026-11-11 · 무엇을 만드는지의 원본은 앱 저장소의 `../byeolmyeong-app/docs/release-1.0.0.md` 다. 2026-11-11 결정 반영: docs/decisions/003-follow-app-decisions-2026-11-11.md

## 서버가 하는 일
- 차트 계산: 출생 정보를 받아 행성 위치·하우스·각도·추가 지점(버텍스 포함, 키론 제외)을 계산한다. 세션이 있어야 받는다. 결정: docs/decisions/002-chart-calculation.md, docs/decisions/003-follow-app-decisions-2026-11-11.md
- 출생 시각 변환과 출생지: 국내 시·군·구 목록을 갖고, 현지 시각을 당시 표준시·서머타임 이력으로 UTC 로 바꾼다.
- 로그인: 구글·애플·카카오 토큰을 검증하고 자체 세션 토큰을 준다. 익명 계정은 없다. 가입 때 만 14세 이상인지 판정하고, 미만이면 계정을 만들지 않는다.
- 글 생성: 계산한 차트로 OpenAI API 를 호출해 요약 해석·심층 리포트·궁합 글·방향성 제시를 만들고 저장한다. 계산은 AI 에게 맡기지 않는다.
- 변곡점: 정해진 마디(토성 사각·귀환·두 번째 귀환, 천왕성 대립)와 변화·기회·운명적 만남 구간을 계산하고, 구간마다 기간·영역·크기를 붙인다. 규칙: 앱 저장소 release-1.0.0 F4.
- 궁합: 직접 입력과 초대 링크. 두 차트를 겹치는 계산과 나 기준 점수(40~100, 관계 유형별)는 서버에서만 한다. 나 기준으로 여러 명과 1:1 매칭한 결과를 랭킹으로 돌려준다(인원 제한 없음). 규칙: 앱 저장소 release-1.0.0 F5.
- 공유 조회, 요청 속도 제한(남용 방지), 푸시 알림 발송, 계정 삭제.

## 모듈 (패키지)
- `chart` — 계산. 순수 함수만 둔다(입력: UTC 시각·위경도 → 출력: 차트). DB·HTTP·AI 를 모른다. 이유: 계산 정확도가 제품의 신뢰이고 기준값 테스트로 혼자 검증돼야 한다.
- `birth` — 출생지 목록과 시각 변환. `java.time` 의 시간대 이력을 쓴다.
- `web` — `POST /charts`·`GET /places` 의 HTTP 층(입력 검증·응답 변환)과 요청 속도 제한. `chart`·`birth` 를 부른다.
- `auth` — 구글·애플·카카오 토큰 검증, 만 14세 판정, 세션, 계정 삭제.
- `reading` — 요약 해석·리포트·방향성 제시 생성과 저장, 근거 대조.
- `synastry` — 직접 입력, 초대, 수락, 나 기준 점수, 랭킹, 궁합 글.
- `share` — 공유 조회.
- `push` — 기기 토큰과 알림 발송.
- 의존 방향: `reading`·`synastry` → `chart`·`birth`. `chart` 는 아무것도 의존하지 않는다.

## API 계약 (앱 저장소와 맞춘다. 바꾸면 이 표를 같은 커밋에서 고친다)
| 메서드·경로 | 인증 | 요청 | 응답 |
|---|---|---|---|
| `GET /places?q=` | 없음 | 검색어 | `[{ id, name, lat, lon }]` |
| `POST /charts` | 세션 | `{ date, time \| null, placeId }` | `Chart` |
| `POST /auth/login` | 없음 | `{ provider: GOOGLE \| APPLE \| KAKAO, idToken }` | `{ token }` · 만 14세 미만 403 `UNDER_AGE` |
| `GET /profile` · `PUT /profile` | 세션 | `{ date, time \| null, placeId }` | 같은 형태 |
| `DELETE /account` | 세션 | 없음 | 204 |
| `POST /readings` | 세션 | 없음(프로필의 출생 정보를 쓴다) | `Reading` |
| `POST /reports` | 세션 | 없음 | 202 `{ status }` |
| `GET /reports/me` | 세션 | 없음 | `{ status: NONE \| WRITING \| DONE, report? }` |
| `POST /synastry/direct` | 세션 | `{ name, relation: LOVE \| FRIEND \| WORK, date, time \| null, placeId }` | `Synastry` |
| `POST /synastry/invites` | 세션 | `{ name }` | `{ inviteId, expiresAt }` |
| `GET /invites/{inviteId}` | 없음 | 없음 | `{ inviterName, status }` |
| `POST /invites/{inviteId}/accept` | 세션 | `{ name }` | `Synastry` |
| `GET /synastry` · `GET /synastry/{id}` | 세션 | 없음 | 목록 · `Synastry` |
| `GET /synastry/ranking?relation=` | 세션 | 관계 유형 | `[{ id, name, score, keywords[3] }]` 점수순, 인원 제한 없음 |
| `POST /directions` | 세션 | `{ topic: FUTURE \| LOVE \| SYNASTRY, synastryId? }` | `Direction` |
| `POST /synastry/{id}/report` | 세션 | 없음 | 202 `{ status }` |
| `GET /shared/{shareId}` | 없음 | 없음 | 요약 해석·궁합 점수·랭킹·다음 변곡점 카드 중 하나(글과 숫자만) |
| `PUT /devices/push-token` | 세션 | `{ token }` | 204 |

- `Chart` = `{ timeKnown, positions[{ body, sign, degree, minute, house?, retrograde }], points[{ point, sign, degree, minute, house? }], houses?[12 × { house, sign, degree, minute }], angles?{ asc{ sign, degree, minute }, mc{ sign, degree, minute } }, aspects[{ a, b, type, orb, applying }] }`
  - `body`: `SUN`·`MOON`·`MERCURY`·`VENUS`·`MARS`·`JUPITER`·`SATURN`·`URANUS`·`NEPTUNE`·`PLUTO`. `point`: `NORTH_NODE`·`LILITH`·`FORTUNE`·`VERTEX`. `sign`: `ARIES`…`PISCES`. `type`: `CONJUNCTION`·`SEXTILE`·`SQUARE`·`TRINE`·`OPPOSITION`.
  - `degree`(0~29)·`minute`(0~59)는 별자리 안 도수이고 올리지 않고 버린다. `house` 는 1~12. `orb` 는 도(소수 둘째 자리). `aspects` 는 행성 10개 사이만.
  - 시각이 `null`(모름)이면 `timeKnown: false` 이고 `house`·`houses`·`angles` 키와 `FORTUNE`·`VERTEX` 점이 없다(값이 `null` 인 키를 보내지 않는다).
- `POST /charts` 요청: `date` 는 `YYYY-MM-DD`, `time` 은 `HH:mm` 또는 `null`, `placeId` 는 `GET /places` 의 `id`(정수).
- 오류 응답: `{ error }`. `POST /charts` 는 400 `INVALID_REQUEST`(본문 형식)·`INVALID_DATE`(형식, 1900-01-01 이전, 오늘 이후)·`INVALID_TIME`·`UNKNOWN_PLACE`. 요청 수 한도를 넘으면 429 `RATE_LIMITED`.
- `GET /places?q=`: 검색어를 공백으로 나눈 낱말이 이름에 모두 든 곳을 20개까지. `q` 가 없으면 400.
- `Reading` = `{ headline, sections[{ title, body, basis[] }], strengths[], cautions[], shareId }`
- `Synastry` = `{ id, names[2], relation, score(40~100, 나 기준), headline, keywords[3], overlay(나의 차트와 상대 점의 화면 좌표용 황경), shareId, reportStatus }`.
- `Direction` = `{ topic, title, items[3] }` (조언형, 단정 금지).
- `Report` 의 시기 영역 = `{ milestones[{ kind, year, age }], intervals[{ kind: CHANGE \| OPPORTUNITY \| FATED, from, to, area: PEOPLE \| WORK \| MONEY \| SELF, size: BIG \| SMALL, title, body }], current? }`. `FATED` 는 시각을 알 때만. 초대로 연결된 궁합의 `overlay` 에서 상대 쪽 황경은 내려보내지 않는다. 이유: 행성 위치로 생일을 역산할 수 있다.

## 데이터
- `accounts`(공급자, 공급자 사용자 식별자 unique) · `sessions`(토큰 해시) · `profiles`(출생 정보) · `readings`(소유자, 종류, 차트 해시, 본문, 공유 식별자) · `reports`(소유자·차트 해시 unique, 상태, 본문) · `synastries`(두 참여자 또는 한 참여자 + 직접 입력, 관계 유형, 나 기준 점수, 부를 이름, 키워드, 본문, 공유 식별자) · `invites`(초대 식별자, 만료, 상태) · `directions`(소유자, 주제, 대상, 본문) · `push_tokens`.
- 직접 입력한 궁합 상대의 출생 정보는 저장하지 않는다. 계산한 뒤 버리고 글만 남긴다.
- 랭킹은 `synastries` 의 점수로 만든다. 따로 저장하지 않는다.

## 글 생성 규칙
- 프롬프트는 리소스 파일로 둔다. 본문에 점성술 용어를 쓰지 않고, 문단마다 근거(차트 요소)를 별도 필드로 받는다. 건강·투자·미래 사건을 단정하지 않는다.
- 근거 대조: AI 가 돌려준 `basis` 의 차트 요소가 서버가 계산한 차트에 없으면 그 응답을 버리고 다시 생성한다(상한 2회). 이유: 없는 요소를 근거로 든 글은 신뢰를 깬다.
- 같은 소유자·차트·종류의 글은 한 번만 생성한다. 실패·빈 응답·구조가 틀린 응답은 저장하지 않는다.
- 생성 호출부는 인터페이스 하나 뒤에 둔다. 이유: 공급자(OpenAI)와 모델을 바꿀 수 있어야 한다.

## 요청 속도 제한 (남용 방지, 서버에서만 판정한다)
- 리포트·궁합·방향성 제시에 기능 횟수 제한은 없다(앱 결정 011). 같은 차트·같은 두 사람의 글은 한 번만 만든다.
- 계정·주소(IP)별 분당 요청 수를 제한한다. 한도는 `application.properties` 의 `byeolmyeong.rate-limit.*`(분당, 서버 메모리).
- 광고 시청 판정은 1.1.0 에 더한다(앱 결정 015).

## 외부 시스템
| 시스템 | 용도 | 상태 |
|---|---|---|
| PostgreSQL | 저장 | 로컬은 컨테이너. 운영 호스팅은 [확인 필요] |
| OpenAI API | 글 생성 | [확인 필요] 키 미발급. 모델은 구현 시 확정 |
| 구글·애플·카카오 로그인 | 토큰 검증 | [확인 필요] 클라이언트 등록, 만 14세 확인 방법(생년월일 / 카카오 연령대) |
| 푸시 발송 | 초대 수락·리포트 완성 알림 | [확인 필요] 발송 경로는 구현 시 Expo 공식 문서로 확정 |

## 관련
- 결정: docs/decisions/
- 계획: docs/plans/2026-11-10-api-mvp.md
