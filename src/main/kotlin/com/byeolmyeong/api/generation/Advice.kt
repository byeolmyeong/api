package com.byeolmyeong.api.generation

// 실천 한 줄의 검사(결정 009 · 037). 실천을 쓰는 글(궁합 방향성, 나 탭 풀이의 연애 · 시기 풀이 · 그날의 하늘)이 같이 쓰는 단일 출처.

/** 실천 한 줄의 끝맺음. "보기" 도 "기" 로 끝나므로 지금 저장본은 그대로 통과한다(결정 037). */
private const val PRACTICE_ENDING = "기"
private val ASSERTION_WORDS = listOf("반드시", "틀림없이", "운명")

// "~게 됩니다"·"~게 돼요": 일이 그렇게 된다고 말하는 끝맺음.
private val BECOMES = Regex("게\\s*(됩니다|돼요)")

// "~ㄹ 거예요" 꼴: 이 말들 바로 앞 글자가 ㄹ 받침이면 앞일(또는 짐작)을 말하는 끝맺음이다. "그런 거예요" 는 아니다.
private val WILL_ENDING = Regex("(\\S)\\s*(거예요|거에요|겁니다|것입니다|것이에요)")
private const val HANGUL_FIRST = 0xAC00
private const val HANGUL_LAST = 0xD7A3
private const val FINAL_COUNT = 28
private const val FINAL_RIEUL = 8

private fun endsWithRieul(c: Char): Boolean = c.code in HANGUL_FIRST..HANGUL_LAST && (c.code - HANGUL_FIRST) % FINAL_COUNT == FINAL_RIEUL

/**
 * 조언형인지 글자로 본다. 문제가 없으면 null, 있으면 `NOT_ADVICE_FORM`.
 * [practiceTitles] 는 모두 앞뒤 공백을 뗀 뒤 "기" 로 끝나야 하고(마침표 · 느낌표 없이), [texts](실천 글 전체 — 제목 · 본문)
 * 어디에도 앞일을 단정하는 꼴("반드시"·"틀림없이"·"운명", "~ㄹ 거예요"·"~ㄹ 겁니다"·"~ㄹ 것입니다", "~게 됩니다"·"~게 돼요")이 없어야 한다.
 * 흔한 꼴만 막는다 — 돌려 말한 단정은 지시문이 맡는다.
 */
fun adviceProblem(practiceTitles: List<String>, texts: List<String>): String? {
	val asserts = texts.any { text ->
		ASSERTION_WORDS.any { it in text } ||
			BECOMES.containsMatchIn(text) ||
			WILL_ENDING.findAll(text).any { endsWithRieul(it.groupValues[1].single()) }
	}
	val practices = practiceTitles.all { it.trim().endsWith(PRACTICE_ENDING) }
	return if (asserts || !practices) "NOT_ADVICE_FORM" else null
}
