package com.byeolmyeong.api.generation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실천 한 줄의 검사(결정 037). 나 탭 풀이 · 시기 풀이 · 그날의 하늘 · 궁합 방향성이 같이 쓴다. */
class AdviceTest {

	private val titles = listOf("좋아하는 마음을 작은 표현으로 자주 전하기", "혼자만의 시간이 필요할 땐 미리 말해 두기", "작은 일이라도 먼저 시작해 보기")
	private val body = "먼저 맞춰 주는 편이라면 짧게라도 꺼내 볼 만해요."

	private fun problem(titles: List<String>, vararg more: String) = adviceProblem(titles, titles + body + more)

	@Test
	fun `기로 끝나는 실천은 통과한다`() {
		assertNull(problem(titles))
		assertNull(problem(listOf("고마운 마음을 그날 말로 전해 보기")), "보기 도 기 로 끝난다(지금 저장본)")
		assertNull(problem(listOf("전하기 ")), "끝의 공백은 뗀다")
		assertNull(problem(listOf(" 대화 뒤에 혼자 쉬는 시간 갖기")), "앞의 공백은 상관없다")
	}

	@Test
	fun `기로 끝나지 않으면 실천이 아니다`() {
		for (title in listOf("마음을 자주 전해요", "먼저 연락하세요", "먼저 연락해 보기를", "작은 표현으로 전하기.", "전하기!", "좋은 사람이 나타난다", "", " ")) {
			assertEquals("NOT_ADVICE_FORM", problem(listOf(title)), "'$title'")
		}
		assertEquals("NOT_ADVICE_FORM", problem(titles + "전해요"), "하나라도 틀리면")
	}

	@Test
	fun `실천 글 어디든 앞일을 단정하면 버린다`() {
		val assertions = listOf(
			"곧 좋은 사람을 만날 거예요.",
			"올해 안에 인연이 생길 겁니다.",
			"관계가 깊어질 것입니다.",
			"마음이 편해질 거에요.",
			"관계가 좋아질 것이에요.",
			"자연스럽게 가까워지게 됩니다.",
			"상대가 먼저 다가오게 돼요.",
			"반드시 좋은 결과가 있어요.",
			"틀림없이 통하는 사람이에요.",
			"운명 같은 만남을 기다려 보기",
		)
		for (sentence in assertions) assertEquals("NOT_ADVICE_FORM", problem(titles, sentence), sentence)
		assertEquals("NOT_ADVICE_FORM", adviceProblem(listOf("반드시 먼저 연락하기"), listOf("반드시 먼저 연락하기")), "제목에도")
		// 지금의 성향을 말하는 문장과 "그런 거예요" 같은 설명은 앞일의 단정이 아니다
		assertNull(problem(titles, "먼저 맞춰 주게 되는 편이에요. 그건 마음이 깊어서 그런 거예요."))
	}

	@Test
	fun `거예요 앞에 이모지(두 칸짜리 글자)가 와도 오류 없이 본다`() {
		assertNull(problem(titles, "오늘은 이런 날인 😊 거예요."), "띄어 쓴 이모지")
		assertNull(problem(titles, "그래서 그런😊거예요."), "붙여 쓴 이모지")
		assertEquals("NOT_ADVICE_FORM", problem(titles, "😊 곧 좋은 사람을 만날 거예요."), "이모지가 다른 곳에 있어도 단정은 버린다")
	}

	@Test
	fun `공통 글쓰기 규칙이 실천은 기로 끝난다고 알린다`() {
		assertTrue(WRITING_RULES.contains("\"~기\" 로 끝나는 한 줄"), WRITING_RULES)
	}
}
