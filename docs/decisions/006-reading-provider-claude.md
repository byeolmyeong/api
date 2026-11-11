---
type: decision
tags: [글 생성, 공급자, Claude, Anthropic, 모델]
status: active
last_reviewed: 2026-11-11
superseded_by:
---
# 006. 글 생성은 Claude API 로 한다 (공급자는 인터페이스 뒤에서 바꿀 수 있게)

> 상태: 활성 · 결정일 2026-11-11 · 결정 001 의 "해석 생성은 OpenAI API 를 쓴다" 를 대체한다. 001 의 나머지(Spring Boot·PostgreSQL·저장소 분리)는 그대로다.

## 맥락
- 결정 001 은 글 생성 공급자로 OpenAI 를 골랐다. 2026-11-11 에 소유자가 준 OpenAI 키는 유효했지만 계정에 크레딧이 없어 생성 호출이 거절됐다(429 `insufficient_quota`).
- 같은 날 소유자가 OpenAI·Gemini·Claude 가운데 무엇을 쓸지 물었고, Claude 를 골라 키를 발급했다. 고른 이유는 한국어 산문의 자연스러움과 지시(용어 금지·단정 금지·근거 분리)를 지키는 정도다. 다른 두 공급자의 글과 나란히 비교한 것은 아니다.

## 결정
- 글 생성은 Claude API(Messages)를 공식 Java SDK(`com.anthropic:anthropic-java`)로 부른다. SDK 를 아는 클래스는 `reading.ClaudeReadingGenerator` 하나이고, 나머지 코드는 인터페이스 `ReadingGenerator` 만 안다.
- 키는 환경 변수 `ANTHROPIC_API_KEY` 에만 둔다. 없으면 서버는 뜨고 생성 요청만 502 `GENERATION_FAILED` 가 된다.
- 모델은 설정 `byeolmyeong.claude.model`(환경 변수 `CLAUDE_MODEL`)로 바꾼다. 기본값은 `claude-opus-5-5`, 생각의 깊이(`byeolmyeong.claude.effort`)는 `medium`.
- 응답은 구조를 정한 JSON(`output_config.format`)으로 받고, 근거(`basis`)는 그 차트에 있는 말만 고를 수 있게 스키마에서 제한한다. 그래도 서버가 다시 대조한다(docs/architecture.md "글 생성 규칙").
- 모델이 요청을 거절하면 Anthropic 이 권하는 다른 모델로 서버 쪽에서 다시 돌리는 설정(`fallbacks: "default"`)을 켠다(`byeolmyeong.claude.refusal-fallback`). Haiku 5.5 는 이 설정을 받지 않아 그 모델을 쓸 때는 끈다.

## 근거 (2026-11-11 표본 6편: 같은 차트 2개 × 모델 3개, 지시문·깊이 동일)
| 모델 | 한 편의 토큰(입력 / 출력) | 걸린 시간 | 한 번에 통과 |
|---|---|---|---|
| `claude-opus-5-5` | 약 3,200 / 1,900 | 23~24초 | 2 / 2 |
| `claude-sonnet-5-5` | 약 3,200 / 1,300 | 12~13초 | 2 / 2 |
| `claude-haiku-5-5` | 약 3,200 / 2,600 | 13~14초 | 2 / 2 |
- 여섯 편 모두 구조·근거 대조를 첫 시도에 통과했다. 기본 모델을 무엇으로 둘지는 소유자가 표본을 읽고 정한다(아래 재검토 조건).
- 요금은 여기 적지 않는다. 변동하는 수치라 Anthropic 의 가격표가 단일 출처다.

## 대안
- OpenAI — 원래 결정. 계정에 크레딧이 없었다. 구현은 `ReadingGenerator` 하나를 새로 쓰면 된다.
- Gemini — 비교해 보지 않았다.

## 결과·재검토 조건
- 소유자가 표본을 읽고 기본 모델을 정하면 `byeolmyeong.claude.model` 의 기본값과 이 문서를 고친다.
- 글 한 편의 비용이나 기다리는 시간이 문제가 되면 더 작은 모델이나 낮은 깊이를 다시 잰다.
- 공급자를 바꾸면 새 결정 문서를 쓰고 `ClaudeReadingGenerator` 자리에 새 구현을 둔다.
