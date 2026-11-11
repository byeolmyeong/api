package com.byeolmyeong.api.generation

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "test-key-not-real"

/** Claude API 를 흉내 내는 로컬 서버로 요청 모양과 응답 처리만 본다. 실제 API 는 부르지 않는다. */
class ClaudeTextGeneratorTest {

	private val mapper = JsonMapper()
	private lateinit var server: HttpServer
	private val hits = AtomicInteger()
	private var status = 200
	private var responseBody = ""
	private var delayMillis = 0L
	private var requestPath: String? = null
	private var requestKey: String? = null
	private var requestBeta: String? = null
	private var requestBody: JsonNode? = null

	private val schema = mapOf(
		"type" to "object",
		"properties" to mapOf("title" to mapOf("type" to "string"), "basis" to basisSchema(listOf("태양", "쌍둥이", "6하우스"))),
		"required" to listOf("title", "basis"),
		"additionalProperties" to false,
	)

	private val request = GenerationRequest(purpose = "test", instructions = "지시문", userText = "차트 사실", schema = schema)

	private val draft: JsonNode = mapper.readTree("""{"title":"제목","basis":["태양","6하우스"]}""")

	@BeforeEach
	fun start() {
		server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
		server.createContext("/") { exchange ->
			hits.incrementAndGet()
			requestPath = exchange.requestURI.path
			// 키를 어느 헤더로 보낼지는 SDK 가 정한다(x-api-key 또는 Authorization)
			requestKey = exchange.requestHeaders.getFirst("x-api-key") ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
			requestBeta = exchange.requestHeaders.getFirst("anthropic-beta")
			requestBody = mapper.readTree(exchange.requestBody.readAllBytes())
			if (delayMillis > 0) Thread.sleep(delayMillis)
			val bytes = responseBody.toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stop() = server.stop(0)

	private fun generator(apiKey: String = KEY, timeoutSeconds: Long = 5, refusalFallback: Boolean = true) = ClaudeTextGenerator(
		apiKey = apiKey,
		baseUrl = "http://localhost:${server.address.port}",
		model = "test-model",
		effort = "medium",
		timeoutSeconds = timeoutSeconds,
		refusalFallback = refusalFallback,
	)

	/** Messages API 의 응답 모양. 글은 `text` 블록에 JSON 문자열로 오고, 그 앞에 빈 생각 블록이 올 수 있다. */
	private fun message(text: String?, stopReason: String = "end_turn"): String = mapper.writeValueAsString(
		mapOf(
			"id" to "msg_1",
			"type" to "message",
			"role" to "assistant",
			"model" to "test-model",
			"content" to listOfNotNull(
				mapOf("type" to "thinking", "thinking" to "", "signature" to "sig"),
				text?.let { mapOf("type" to "text", "text" to it) },
			),
			"stop_reason" to stopReason,
			"stop_sequence" to null,
			"usage" to mapOf("input_tokens" to 10, "output_tokens" to 20),
		),
	)

	@Test
	fun `구조를 정한 요청을 보내고 글을 돌려준다`() {
		responseBody = message(mapper.writeValueAsString(draft))

		assertEquals(draft, generator().generate(request))

		assertEquals(1, hits.get())
		assertEquals("/v1/messages", requestPath)
		assertEquals(KEY, requestKey)
		val body = requestBody!!
		assertEquals("test-model", body["model"].asString())
		assertEquals(16000, body["max_tokens"].asInt())
		assertEquals("지시문", body["system"].asString())
		assertEquals(1, body["messages"].size(), "미리 채운 응답(prefill)을 보내지 않는다")
		assertEquals("user", body["messages"][0]["role"].asString())
		assertEquals("차트 사실", body["messages"][0]["content"].asString())
		assertEquals("medium", body["output_config"]["effort"].asString())
		val format = body["output_config"]["format"]
		assertEquals("json_schema", format["type"].asString())
		assertEquals(
			listOf("태양", "쌍둥이", "6하우스"),
			format["schema"]["properties"]["basis"]["items"]["enum"].values().map { it.asString() },
			"근거는 차트에 있는 말 중에서만 고르게 한다",
		)
		assertEquals(mapper.valueToTree<JsonNode>(schema), format["schema"], "받은 구조를 그대로 보낸다")
		// 이 모델이 400 으로 거절하는 것들을 보내지 않는다
		for (field in listOf("thinking", "temperature", "top_p", "top_k", "tool_choice", "tools")) {
			assertNull(body[field], field)
		}
		// 거절되면 서버 쪽에서 다른 모델로 다시 돌리게 한다
		assertEquals("server-side-fallback-2026-07-01", requestBeta)
		assertEquals("default", body["fallbacks"].asString())
	}

	@Test
	fun `거절 대비를 끄면 그 값들을 보내지 않는다`() {
		responseBody = message(mapper.writeValueAsString(draft))

		assertEquals(draft, generator(refusalFallback = false).generate(request))

		assertNull(requestBeta)
		assertNull(requestBody!!["fallbacks"])
	}

	@Test
	fun `쓸 수 없는 응답은 null 이다`() {
		fun assertUnusable(body: String, why: String) {
			responseBody = body
			assertNull(generator().generate(request), why)
		}
		val json = mapper.writeValueAsString(draft)
		assertUnusable(message(json, stopReason = "refusal"), "거절 — 내용이 있어도 읽지 않는다")
		assertUnusable(message(null, stopReason = "refusal"), "내용 없는 거절")
		assertUnusable(message(json.dropLast(20), stopReason = "max_tokens"), "중간에 끊겼다")
		assertUnusable(message(json, stopReason = "max_tokens"), "끊겼다고 하면 읽지 않는다")
		assertUnusable(message("JSON 이 아니다"), "글이 JSON 이 아니다")
		assertUnusable(message("[]"), "객체가 아니다")
		assertUnusable(message(null), "글이 없다")
	}

	@Test
	fun `429 는 한 번만 부르고 실패로 알린다`() {
		status = 429
		responseBody = """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""

		val e = assertFailsWith<GenerationException> { generator().generate(request) }

		assertEquals("HTTP_429", e.reason)
		assertEquals(1, hits.get(), "다시 부르지 않는다")
	}

	@Test
	fun `서버 오류 500 도 한 번만 부르고 실패로 알린다`() {
		status = 500
		responseBody = "oops <b>not json</b>"

		val e = assertFailsWith<GenerationException> { generator().generate(request) }

		assertEquals("HTTP_500", e.reason)
		assertEquals(1, hits.get())
	}

	@Test
	fun `실패 사유에 요청과 응답의 글을 옮기지 않는다`() {
		status = 400
		responseBody = """{"type":"error","error":{"type":"invalid_request_error","message":"차트 사실 is wrong"}}"""

		val e = assertFailsWith<GenerationException> { generator().generate(request) }

		assertEquals("HTTP_400", e.reason)
		assertEquals("HTTP_400", e.message)
		assertNull(e.cause, "SDK 예외에는 응답 본문이 들어 있다")
	}

	@Test
	fun `키가 없으면 부르지 않고 실패로 알린다`() {
		val e = assertFailsWith<GenerationException> { generator(apiKey = " ").generate(request) }

		assertEquals("NO_API_KEY", e.reason)
		assertEquals(0, hits.get())
	}

	@Test
	fun `정한 시간 안에 답이 없으면 실패로 알린다`() {
		delayMillis = 3_000
		responseBody = message(mapper.writeValueAsString(draft))

		val e = assertFailsWith<GenerationException> { generator(timeoutSeconds = 1).generate(request) }

		assertEquals("IO", e.reason)
		assertTrue(hits.get() <= 1)
	}
}
