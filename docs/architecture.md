---
type: architecture
tags: [서버 아키텍처, API 계약, 차트 계산, 해석 생성, 로그인]
status: active
last_reviewed: 2026-11-10
---
# 서버 아키텍처

> 상태: 활성 · 기준 2026-11-10 · 구현 전 설계다. 무엇을 만드는지의 원본은 앱 저장소의 `../byeolmyeong-app/docs/release-1.0.0.md` 다.

## 서버가 하는 일
- 차트 계산: 출생 정보를 받아 행성 위치·하우스·각도·추가 지점을 계산한다. 로그인 없이 받는다. 결정: docs/decisions/002-chart-calculation.md
- 출생 시각 변환과 출생지: 국내 시·군·구 목록을 갖고, 현지 시각을 당시 표준시·서머타임 이력으로 UTC 로 바꾼다.
- 로그인: 구글·애플 ID 토큰을 검증하고 자체 세션 토큰을 준다. 익명 계정은 없다.
- 글 생성: 계산한 차트로 OpenAI API 를 호출해 요약 해석·심층 리포트·궁합 글을 만들고 저장한다. 계산은 AI 에게 맡기지 않는다.
- 변곡점 연도: 토성 사각·귀환, 천왕성 대립이 오는 해를 계산한다.
- 궁합: 직접 입력과 초대 링크. 두 차트를 겹치는 계산은 서버에서만 한다.
- 공유 조회, 횟수 제한, 푸시 알림 발송, 계정 삭제.

## 모듈 (패키지)
- `chart` — 계산. 순수 함수만 둔다(입력: UTC 시각·위경도 → 출력: 차트). DB·HTTP·AI 를 모른다. 이유: 계산 정확도가 제품의 신뢰이고 기준값 테스트로 혼자 검증돼야 한다.
- `birth` — 출생지 목록과 시각 변환. `java.time` 의 시간대 이력을 쓴다.
- `auth` — ID 토큰 검증, 세션, 계정 삭제.
- `reading` — 요약 해석·리포트 생성과 저장, 근거 대조, 횟수 제한.
- `synastry` — 직접 입력, 초대, 수락, 궁합 글.
- `share` — 공유 조회.
- `push` — 기기 토큰과 알림 발송.
- 의존 방향: `reading`·`synastry` → `chart`·`birth`. `chart` 는 아무것도 의존하지 않는다.

## API 계약 (앱 저장소와 맞춘다. 바꾸면 이 표를 같은 커밋에서 고친다)
| 메서드·경로 | 인증 | 요청 | 응답 |
|---|---|---|---|
| `GET /places?q=` | 없음 | 검색어 | `[{ id, name, lat, lon }]` |
| `POST /charts` | 없음 | `{ date, time \| null, placeId }` | `Chart` |
| `POST /auth/login` | 없음 | `{ provider: GOOGLE \| APPLE, idToken }` | `{ token }` |
| `GET /profile` · `PUT /profile` | 세션 | `{ date, time \| null, placeId }` | 같은 형태 |
| `DELETE /account` | 세션 | 없음 | 204 |
| `POST /readings` | 세션 | 없음(프로필의 출생 정보를 쓴다) | `Reading` |
| `POST /reports` | 세션 | 없음 | 202 `{ status }` |
| `GET /reports/me` | 세션 | 없음 | `{ status: NONE \| WRITING \| DONE, report? }` |
| `POST /synastry/direct` | 세션 | `{ name, date, time \| null, placeId }` | `Synastry` |
| `POST /synastry/invites` | 세션 | `{ name }` | `{ inviteId, expiresAt }` |
| `GET /invites/{inviteId}` | 없음 | 없음 | `{ inviterName, status }` |
| `POST /invites/{inviteId}/accept` | 세션 | `{ name }` | `Synastry` |
| `GET /synastry` · `GET /synastry/{id}` | 세션 | 없음 | 목록 · `Synastry` |
| `POST /synastry/{id}/report` | 세션 | 없음 | 202 `{ status }` |
| `GET /shared/{shareId}` | 없음 | 없음 | 요약 해석 또는 궁합 키워드(글만) |
| `PUT /devices/push-token` | 세션 | `{ token }` | 204 |

- `Chart` = `{ timeKnown, positions[{ body, sign, degree, minute, house?, retrograde }], points[...], houses?[12], angles?{ asc, mc }, aspects[{ a, b, type, orb, applying }] }`
- `Reading` = `{ headline, sections[{ title, body, basis[] }], strengths[], cautions[], shareId }`
- `Synastry` = `{ id, names[2], headline, keywords[3], overlay(나의 차트와 상대 점의 화면 좌표용 황경), shareId, reportStatus }`. 초대로 연결된 궁합의 `overlay` 에서 상대 쪽 황경은 내려보내지 않는다. 이유: 행성 위치로 생일을 역산할 수 있다.

## 데이터
- `accounts`(공급자, 공급자 사용자 식별자 unique) · `sessions`(토큰 해시) · `profiles`(출생 정보) · `readings`(소유자, 종류, 차트 해시, 본문, 공유 식별자) · `reports`(소유자 unique, 상태, 본문) · `synastries`(두 참여자 또는 한 참여자 + 직접 입력, 본문, 공유 식별자) · `invites`(초대 식별자, 만료, 상태) · `push_tokens` · `usage`(계정·날짜·종류별 횟수).
- 직접 입력한 궁합 상대의 출생 정보는 저장하지 않는다. 계산한 뒤 버리고 글만 남긴다.
- 로그인하지 않은 `POST /charts` 요청의 출생 정보는 저장하지 않는다.

## 글 생성 규칙
- 프롬프트는 리소스 파일로 둔다. 본문에 점성술 용어를 쓰지 않고, 문단마다 근거(차트 요소)를 별도 필드로 받는다. 건강·투자·미래 사건을 단정하지 않는다.
- 근거 대조: AI 가 돌려준 `basis` 의 차트 요소가 서버가 계산한 차트에 없으면 그 응답을 버리고 다시 생성한다(상한 2회). 이유: 없는 요소를 근거로 든 글은 신뢰를 깬다.
- 같은 소유자·차트·종류의 글은 한 번만 생성한다. 실패·빈 응답·구조가 틀린 응답은 저장하지 않는다.
- 생성 호출부는 인터페이스 하나 뒤에 둔다. 이유: 공급자(OpenAI)와 모델을 바꿀 수 있어야 한다.

## 횟수 제한 (서버에서만 판정한다)
- 심층 리포트 계정당 1편, 궁합 리포트 계정당 1편, 궁합 키워드 계정당 하루 5번.
- 로그인 없는 `POST /charts`·`GET /places` 는 주소(IP)별 요청 수를 제한한다.

## 외부 시스템
| 시스템 | 용도 | 상태 |
|---|---|---|
| PostgreSQL | 저장 | 로컬은 컨테이너. 운영 호스팅은 [확인 필요] |
| OpenAI API | 글 생성 | [확인 필요] 키 미발급. 모델은 구현 시 확정 |
| 구글·애플 로그인 | ID 토큰 검증용 공개 키 | [확인 필요] 클라이언트 등록 |
| 푸시 발송 | 초대 수락·리포트 완성 알림 | [확인 필요] 발송 경로는 구현 시 Expo 공식 문서로 확정 |

## 관련
- 결정: docs/decisions/
- 계획: docs/plans/2026-11-10-api-mvp.md
