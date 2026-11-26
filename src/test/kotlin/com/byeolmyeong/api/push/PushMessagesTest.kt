package com.byeolmyeong.api.push

import kotlin.test.Test
import kotlin.test.assertEquals

class PushMessagesTest {

	@Test
	fun `별명 지음 본문은 닉네임을 넣고 없으면 뺀다`() {
		assertEquals("별명이 님의 별명이 기다리고 있어요", PushMessages.readingDoneBody("별명이"))
		assertEquals("별명이 기다리고 있어요", PushMessages.readingDoneBody(null))
		assertEquals("별명이 기다리고 있어요", PushMessages.readingDoneBody(" "))
	}

	@Test
	fun `답변 첫 줄은 빈 줄을 건너뛰고 60자에서 자른다`() {
		assertEquals("첫 줄", PushMessages.answerPreview("\n\n  첫 줄  \n둘째 줄"))
		assertEquals("가".repeat(60), PushMessages.answerPreview("가".repeat(60)))
		assertEquals("가".repeat(60) + "…", PushMessages.answerPreview("가".repeat(61)))
		assertEquals("😀".repeat(60) + "…", PushMessages.answerPreview("😀".repeat(70)), "코드 포인트로 센다")
		assertEquals("", PushMessages.answerPreview("  \n "))
	}
}
