package com.byeolmyeong.api.direction

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 생성기가 돌려준 JSON 을 글로 읽는 곳과, 조언형인지 글자로 보는 곳(결정 009). */
class DirectionDraftTest {

	private val mapper = JsonMapper()

	private val draft = DirectionDraft(
		title = "마음을 말로 옮겨 볼 만한 때",
		items = listOf(
			DirectionItem("먼저 안부 물어보기", "기다리기보다 가볍게 먼저 연락해 볼 만해요.", listOf("금성")),
			DirectionItem("서운한 마음을 그날 말해 보기", "쌓아 두는 편이라면 짧게라도 꺼내 보면 좋아요.", listOf("달")),
			DirectionItem("혼자만의 시간을 미리 알려 보기", "거리가 필요할 때 이유를 함께 말해 볼 만해요.", listOf("화성")),
		),
	)

	private fun withBody(body: String) = draft.copy(items = draft.items.mapIndexed { i, item -> if (i == 1) item.copy(body = body) else item })

	@Test
	fun `모양이 맞으면 글로 읽는다`() {
		assertEquals(draft, parseDirectionDraft(mapper.valueToTree(draft)))
	}

	@Test
	fun `필드가 빠졌거나 타입이 틀리면 null 이다`() {
		fun parse(json: String) = parseDirectionDraft(mapper.readTree(json))
		assertNull(parse("""{"title":"제목"}"""), "항목이 없다")
		assertNull(parse("""{"title":1,"items":[]}"""), "제목이 문자열이 아니다")
		assertNull(parse("""{"title":"제목","items":[{"title":"해 보기","body":"본문"}]}"""), "근거가 없다")
		assertNull(parse("""{"title":"제목","items":[{"title":"해 보기","body":"본문","basis":[1]}]}"""), "근거가 문자열이 아니다")
	}

	@Test
	fun `조언형으로 쓴 글은 통과한다`() {
		assertNull(adviceProblem(draft))
		// 지금의 성향을 말하는 문장과 "그런 거예요" 같은 설명은 앞일의 단정이 아니다
		assertNull(adviceProblem(withBody("먼저 맞춰 주게 되는 편이에요. 그건 마음이 깊어서 그런 거예요. 한 번쯤 원하는 것을 말해 볼 만해요.")))
	}

	@Test
	fun `항목 제목이 보기로 끝나지 않으면 조언형이 아니다`() {
		for (title in listOf("좋은 사람이 나타난다", "먼저 연락하세요", "먼저 연락해 보기를")) {
			val broken = draft.copy(items = draft.items.mapIndexed { i, item -> if (i == 0) item.copy(title = title) else item })
			assertEquals("NOT_ADVICE_FORM", adviceProblem(broken), title)
		}
		assertNull(adviceProblem(draft.copy(items = draft.items.map { it.copy(title = it.title + " ") })), "끝의 공백은 본다")
	}

	@Test
	fun `무슨 일이 일어난다고 단정하면 버린다`() {
		val assertions = listOf(
			"곧 좋은 사람을 만날 거예요.",
			"올해 안에 인연이 생길 겁니다.",
			"관계가 깊어질 것입니다.",
			"마음이 편해질 거에요.",
			"자연스럽게 가까워지게 됩니다.",
			"상대가 먼저 다가오게 돼요.",
			"반드시 좋은 결과가 있어요.",
			"틀림없이 통하는 사람이에요.",
			"운명 같은 만남을 기다려 볼 만해요.",
		)
		for (sentence in assertions) {
			assertEquals("NOT_ADVICE_FORM", adviceProblem(withBody("먼저 말해 볼 만해요. $sentence")), sentence)
		}
		assertEquals("NOT_ADVICE_FORM", adviceProblem(draft.copy(title = "곧 설레는 만남이 찾아올 거예요")), "맨 위 제목도 본다")
	}
}
