package com.byeolmyeong.api.report

import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.web.PurchaseCheckFailedException
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// 결제 확인 테스트(결정 056)가 함께 쓰는 대역.

/** RevenueCat 대신 끼우는 가짜. 물은 계정을 기억하고 [answer] 로 답한다(던지면 묻지 못한 것). 실제 RevenueCat 을 부르지 않는다. */
class FakeRevenueCat : RevenueCat {
	val asked = CopyOnWriteArrayList<UUID>()

	@Volatile
	var answer: (UUID) -> Boolean = { false }

	override fun hasDetail(accountId: UUID): Boolean {
		asked += accountId
		return answer(accountId)
	}

	fun fails() {
		answer = { throw PurchaseCheckFailedException("TEST_FAILURE") }
	}

	fun reset() {
		asked.clear()
		answer = { false }
	}
}

/** 글을 만들지 않는 생성기: 문([gate])이 닫혀 있으면 열릴 때까지 서 있다가 빈 답을 준다 — 받기가 시작됐는지만 보는 테스트용. */
class GatedEmptyGenerator : TextGenerator {
	val requests = CopyOnWriteArrayList<GenerationRequest>()

	@Volatile
	var gate: CountDownLatch? = null

	override fun generate(request: GenerationRequest): JsonNode? {
		requests += request
		gate?.await(30, TimeUnit.SECONDS)
		return null
	}

	fun reset() {
		gate?.countDown()
		gate = null
		requests.clear()
	}
}
