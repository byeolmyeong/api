package com.byeolmyeong.api.auth

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val REDIRECT = "http://localhost:8081/auth/kakao"

// 카카오 웹 로그인의 인가 코드 → ID 토큰 교환(결정 021). 카카오 대신 이 테스트의 HTTP 서버가 답한다.
class KakaoOAuthClientTest {

	private lateinit var server: HttpServer
	private var status = 200
	private var responseBody = """{"access_token":"a","token_type":"bearer","id_token":"the-id-token","expires_in":7199}"""
	private var lastForm: Map<String, String> = emptyMap()
	private var lastPath = ""
	private val calls = AtomicInteger()

	@BeforeEach
	fun start() {
		server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
		server.createContext("/") { exchange ->
			calls.incrementAndGet()
			lastPath = exchange.requestURI.path
			lastForm = exchange.requestBody.readAllBytes().decodeToString().split("&").filter { it.isNotEmpty() }.associate {
				val (k, v) = it.split("=", limit = 2)
				URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
			}
			val bytes = responseBody.toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stop() = server.stop(0)

	private fun client(authUrl: String = "http://localhost:${server.address.port}") =
		KakaoOAuthClient("rest-key", "secret", listOf(REDIRECT, " "), authUrl)

	@Test
	fun `인가 코드를 카카오의 토큰 경로에 보내고 ID 토큰을 돌려준다`() {
		assertEquals("the-id-token", client().exchange("the-code", REDIRECT))
		assertEquals("/oauth/token", lastPath)
		assertEquals(
			mapOf(
				"grant_type" to "authorization_code",
				"client_id" to "rest-key",
				"redirect_uri" to REDIRECT,
				"code" to "the-code",
				"client_secret" to "secret",
			),
			lastForm,
		)
	}

	@Test
	fun `등록하지 않은 돌아올 주소는 카카오를 부르지 않고 401 이다`() {
		assertFailsWith<InvalidCodeException> { client().exchange("the-code", "https://evil.example/auth/kakao") }
		assertFailsWith<InvalidCodeException> { client().exchange("the-code", "") }
		assertEquals(0, calls.get())
	}

	@Test
	fun `카카오가 400 이면 코드가 틀린 것이다`() {
		status = 400
		responseBody = """{"error":"invalid_grant","error_code":"KOE320"}"""
		assertFailsWith<InvalidCodeException> { client().exchange("used-code", REDIRECT) }
	}

	@Test
	fun `카카오가 500 이면 502 로 알린다`() {
		status = 500
		responseBody = "{}"
		assertFailsWith<ProviderUnavailableException> { client().exchange("the-code", REDIRECT) }
	}

	@Test
	fun `응답에 ID 토큰이 없으면 설정 문제라 502 로 알린다`() {
		responseBody = """{"access_token":"a","token_type":"bearer"}"""
		assertFailsWith<ProviderUnavailableException> { client().exchange("the-code", REDIRECT) }
		responseBody = "not json"
		assertFailsWith<ProviderUnavailableException> { client().exchange("the-code", REDIRECT) }
	}

	@Test
	fun `카카오에 닿지 못하면 502 로 알린다`() {
		val port = server.address.port
		server.stop(0)
		assertFailsWith<ProviderUnavailableException> { client("http://localhost:$port").exchange("the-code", REDIRECT) }
		start() // AfterEach 가 멈출 서버
	}
}
