package com.byeolmyeong.api.reading

/**
 * 생성기에 넘기는 것. 출생 날짜·시각·장소는 들어 있지 않다(차트 요소만).
 * [instructions] 는 글쓰기 규칙, [facts] 는 서버가 계산한 차트, [basisVocabulary] 는 근거로 쓸 수 있는 말 전부.
 */
data class ReadingPrompt(val instructions: String, val facts: String, val basisVocabulary: List<String>)

/** 글 생성 공급자를 가리는 한 곳. 공급자와 모델을 바꿀 때 구현만 바꾼다(결정 006). */
interface ReadingGenerator {

	/**
	 * 한 번 생성한다. 응답은 왔지만 쓸 수 없으면(거절·잘림·구조가 틀림) null 을 돌려준다 — 부르는 쪽이 다시 생성할 수 있다.
	 * 호출 자체가 실패하면(키 없음·HTTP 오류·시간 초과) [ReadingGenerationException] 을 던진다 — 다시 불러도 같을 실패다.
	 */
	fun generate(prompt: ReadingPrompt): ReadingDraft?
}

/** 글을 만들지 못했다(502 `GENERATION_FAILED`). [reason] 은 로그에 남기는 짧은 코드이고 글·차트·키를 담지 않는다. */
class ReadingGenerationException(val reason: String) : RuntimeException(reason)
