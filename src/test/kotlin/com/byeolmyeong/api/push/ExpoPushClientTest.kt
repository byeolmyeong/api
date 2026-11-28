package com.byeolmyeong.api.push

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Expo 푸시 보내기(결정 042 의 1). Expo 대신 이 테스트의 HTTP 서버가 답한다.
@ExtendWith(OutputCaptureExtension::class)
class ExpoPushClientTest {

	private lateinit var server: HttpServer
	private val mapper = JsonMapper()
	private var status = 200
	private var respond: (Int) -> String = { n -> """{"data":[${List(n) { """{"status":"ok","id":"t$it"}""" }.joinToString(",")}]}""" }
	private val bodies = CopyOnWriteArrayList<String>()
	private val authorizations = CopyOnWriteArrayList<String?>()
	private var path = ""

	@BeforeEach
	fun start() {
		server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
		server.createContext("/") { exchange ->
			path = exchange.requestURI.path
			authorizations += exchange.requestHeaders.getFirst("Authorization")
			val body = exchange.requestBody.readAllBytes().decodeToString()
			bodies += body
			val bytes = respond(mapper.readTree(body).size()).toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stop() = server.stop(0)

	private fun client(enabled: Boolean = true, token: String = "expo-access-token") =
		ExpoPushClient(enabled, token, "http://localhost:${server.address.port}")

	private fun message(n: Int) = PushMessage("ExponentPushToken[device$n]", "제목 $n", "본문 $n", mapOf("type" to "ME_REPORT_DONE"))

	@Test
	fun `접근 토큰을 실어 보내고 받은 표마다 OK 다`() {
		val outcomes = client().send(listOf(message(1), message(2)))

		assertEquals(listOf(PushOutcome.OK, PushOutcome.OK), outcomes)
		assertEquals("/--/api/v2/push/send", path)
		assertEquals(listOf<String?>("Bearer expo-access-token"), authorizations.toList())
		val sent = mapper.readTree(bodies.single())
		assertEquals("ExponentPushToken[device1]", sent[0]["to"].asString())
		assertEquals("제목 1", sent[0]["title"].asString())
		assertEquals("본문 1", sent[0]["body"].asString())
		assertEquals("ME_REPORT_DONE", sent[0]["data"]["type"].asString())
		assertEquals("default", sent[0]["sound"].asString())
	}

	@Test
	fun `소리 없는 메시지는 sound 를 빼고 채널이 있으면 channelId 를 싣는다`() {
		val quiet = PushMessage("ExponentPushToken[quiet]", "제목", "본문", mapOf("type" to "NOTICE"), sound = false, channelId = "quiet")
		val silentIos = PushMessage("ExponentPushToken[ios]", "제목", "본문", mapOf("type" to "NOTICE"), sound = false)

		client().send(listOf(quiet, silentIos, message(3)))

		val sent = mapper.readTree(bodies.single())
		assertFalse(sent[0].has("sound"), "소리 없음은 sound 를 뺀다(Expo: 빼면 소리가 없다)")
		assertEquals("quiet", sent[0]["channelId"].asString())
		assertFalse(sent[1].has("sound"))
		assertFalse(sent[1].has("channelId"), "채널이 없으면 키를 싣지 않는다(없는 채널이면 알림이 안 보인다)")
		assertEquals("default", sent[2]["sound"].asString(), "지금 모양 그대로")
		assertFalse(sent[2].has("channelId"))
	}

	@Test
	fun `100건씩 묶어 보낸다`() {
		val outcomes = client().send(List(250) { message(it) })

		assertEquals(250, outcomes.size)
		assertEquals(listOf(100, 100, 50), bodies.map { mapper.readTree(it).size() })
	}

	@Test
	fun `DeviceNotRegistered 는 따로 알리고 그 밖의 오류는 FAILED 다`(output: CapturedOutput) {
		respond = {
			"""{"data":[{"status":"ok","id":"a"},{"status":"error","message":"x","details":{"error":"DeviceNotRegistered"}},{"status":"error","message":"too big","details":{"error":"MessageTooBig"}}]}"""
		}

		val outcomes = client().send(listOf(message(1), message(2), message(3)))

		assertEquals(listOf(PushOutcome.OK, PushOutcome.DEVICE_NOT_REGISTERED, PushOutcome.FAILED), outcomes)
		assertTrue(output.all.contains("event=push_failed reason=MessageTooBig"))
	}

	@Test
	fun `연결 실패 · 오류 응답 · 읽을 수 없는 답은 모두 FAILED 이고 던지지 않는다`() {
		status = 500
		assertEquals(listOf(PushOutcome.FAILED), client().send(listOf(message(1))))

		status = 200
		respond = { """{"errors":[{"code":"PUSH_TOO_MANY_EXPERIENCE_IDS"}]}""" }
		assertEquals(listOf(PushOutcome.FAILED), client().send(listOf(message(1))))

		respond = { """{"data":[]}""" }
		assertEquals(listOf(PushOutcome.FAILED, PushOutcome.FAILED), client().send(listOf(message(1), message(2))), "표 수가 다르다")

		respond = { "not json" }
		assertEquals(listOf(PushOutcome.FAILED), client().send(listOf(message(1))))

		server.stop(0)
		assertEquals(listOf(PushOutcome.FAILED), client().send(listOf(message(1))))
		server = HttpServer.create(InetSocketAddress("localhost", 0), 0) // AfterEach 가 멈출 것
	}

	@Test
	fun `꺼졌거나 접근 토큰이 없으면 보내지 않고 로그만 남긴다`(output: CapturedOutput) {
		assertEquals(listOf(PushOutcome.SKIPPED), client(enabled = false).send(listOf(message(1))))
		assertEquals(listOf(PushOutcome.SKIPPED, PushOutcome.SKIPPED), client(token = " ").send(listOf(message(1), message(2))))

		assertEquals(emptyList<String>(), bodies.toList(), "Expo 를 부르지 않았다")
		assertTrue(output.all.contains("event=push_skipped reason=DISABLED count=1"))
		assertTrue(output.all.contains("event=push_skipped reason=NO_ACCESS_TOKEN count=2"))
		assertEquals(emptyList<PushOutcome>(), client().send(emptyList()))
	}

	@Test
	fun `토큰 · 본문 · 접근 토큰은 로그에 남지 않는다`(output: CapturedOutput) {
		respond = { """{"data":[{"status":"error","details":{"error":"InvalidCredentials"}}]}""" }
		client().send(listOf(message(7)))
		status = 500
		client().send(listOf(message(7)))

		for (secret in listOf("device7", "본문 7", "제목 7", "expo-access-token")) assertFalse(output.all.contains(secret), secret)
	}
}
