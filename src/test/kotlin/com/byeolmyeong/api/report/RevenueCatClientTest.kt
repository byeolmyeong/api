package com.byeolmyeong.api.report

import com.byeolmyeong.api.web.PurchaseCheckFailedException
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SECRET = "revenuecat-secret-for-test"

/** RevenueCat 구독자 조회(결정 056 의 4). RevenueCat 대신 이 테스트의 HTTP 서버가 답한다. */
@ExtendWith(OutputCaptureExtension::class)
class RevenueCatClientTest {

	private lateinit var server: HttpServer
	private var status = 200
	private var body = ""
	private val paths = CopyOnWriteArrayList<String>()
	private val authorizations = CopyOnWriteArrayList<String?>()
	private val account = UUID.fromString("0b6a3c2e-5d1f-4f7a-9c3e-2a1b4c5d6e7f")
	private val now = Instant.parse("2026-12-20T00:00:00Z")

	@BeforeEach
	fun start() {
		server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
		server.createContext("/") { exchange ->
			paths += exchange.requestURI.path
			authorizations += exchange.requestHeaders.getFirst("Authorization")
			val bytes = body.toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
			exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stop() = server.stop(0)

	private fun client(secret: String = SECRET, baseUrl: String = "http://localhost:${server.address.port}") =
		RevenueCatClient(secret, baseUrl, "detail", Clock.fixed(now, ZoneOffset.UTC))

	private fun subscriber(entitlements: String) = """{"request_date":"2026-12-20T00:00:00Z","subscriber":{"entitlements":$entitlements,"non_subscriptions":{}}}"""

	@Test
	fun `비밀 키를 실어 계정 id 로 묻고 평생 상품(만료 없음)이면 있다`() {
		body = subscriber("""{"detail":{"expires_date":null,"product_identifier":"detail_once","purchase_date":"2026-12-11T01:02:03Z"}}""")

		assertTrue(client().hasDetail(account))
		assertEquals(listOf("/v1/subscribers/$account"), paths.toList())
		assertEquals(listOf<String?>("Bearer $SECRET"), authorizations.toList())
	}

	@Test
	fun `고객을 새로 만든 답(201)도 읽는다`() {
		status = 201
		body = subscriber("{}")

		assertFalse(client().hasDetail(account))
	}

	@Test
	fun `상품이 없거나 다른 상품만 있거나 만료됐으면 없고 만료가 뒤면 있다`() {
		body = subscriber("{}")
		assertFalse(client().hasDetail(account))

		body = subscriber("""{"other":{"expires_date":null}}""")
		assertFalse(client().hasDetail(account))

		body = subscriber("""{"detail":{"expires_date":"2026-12-19T23:59:59Z"}}""")
		assertFalse(client().hasDetail(account))

		body = subscriber("""{"detail":{"expires_date":"2026-12-20T00:00:01.000Z"}}""")
		assertTrue(client().hasDetail(account))
	}

	@Test
	fun `비밀 키가 없으면 부르지 않고 묻지 못한 것이다`(output: CapturedOutput) {
		val e = assertThrows<PurchaseCheckFailedException> { client(secret = " ").hasDetail(account) }

		assertEquals("NO_SECRET_KEY", e.reason)
		assertTrue(paths.isEmpty())
		assertTrue(output.out.contains("event=purchase_check_failed reason=NO_SECRET_KEY"))
	}

	@Test
	fun `2xx 가 아니거나 읽을 수 없는 답이면 묻지 못한 것이고 비밀 키는 로그에 없다`(output: CapturedOutput) {
		status = 500
		body = """{"code":7225,"message":"internal"}"""
		assertThrows<PurchaseCheckFailedException> { client().hasDetail(account) }

		status = 401
		assertThrows<PurchaseCheckFailedException> { client().hasDetail(account) }

		status = 200
		for (unreadable in listOf("not json", "{}", """{"subscriber":{}}""", """{"subscriber":{"entitlements":[]}}""", subscriber("""{"detail":"yes"}"""), subscriber("""{"detail":{"expires_date":"내일"}}"""))) {
			body = unreadable
			assertThrows<PurchaseCheckFailedException>(unreadable) { client().hasDetail(account) }
		}

		assertFalse(output.out.contains(SECRET))
		assertTrue(output.out.contains("event=purchase_check_failed"))
	}

	@Test
	fun `연결하지 못하면 묻지 못한 것이다`() {
		val closed = HttpServer.create(InetSocketAddress("localhost", 0), 0).let { val port = it.address.port; it.stop(0); port }

		assertThrows<PurchaseCheckFailedException> { client(baseUrl = "http://localhost:$closed").hasDetail(account) }
	}
}
