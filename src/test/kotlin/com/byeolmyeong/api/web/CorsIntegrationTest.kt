package com.byeolmyeong.api.web

import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val APP = "http://localhost:8081"

private fun send(port: Int, method: String, path: String, vararg headers: String): HttpResponse<String> =
	HttpClient.newHttpClient().send(
		HttpRequest.newBuilder(URI("http://localhost:$port$path"))
			.method(method, HttpRequest.BodyPublishers.noBody())
			.headers(*headers)
			.build(),
		HttpResponse.BodyHandlers.ofString(),
	)

private fun HttpResponse<String>.allowOrigin(): String? = headers().firstValue("Access-Control-Allow-Origin").orElse(null)

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["byeolmyeong.cors.allowed-origins=$APP,https://app.example"],
)
class CorsIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `허용한 주소의 브라우저 요청에는 허용 헤더를 붙인다`() {
		val response = send(port, "GET", "/health", "Origin", APP)
		assertEquals(200, response.statusCode())
		assertEquals(APP, response.allowOrigin())
		assertEquals("https://app.example", send(port, "GET", "/health", "Origin", "https://app.example").allowOrigin())
	}

	@Test
	fun `본 요청 전에 묻는 요청에 메서드와 세션 헤더를 허용한다고 답한다`() {
		val response = send(
			port, "OPTIONS", "/charts",
			"Origin", APP,
			"Access-Control-Request-Method", "POST",
			"Access-Control-Request-Headers", "authorization,content-type",
		)
		assertEquals(200, response.statusCode())
		assertEquals(APP, response.allowOrigin())
		val allowedHeaders = response.headers().firstValue("Access-Control-Allow-Headers").orElse("").lowercase()
		assertEquals(true, allowedHeaders.contains("authorization") && allowedHeaders.contains("content-type"))
	}

	@Test
	fun `허용하지 않은 주소에는 허용 헤더를 붙이지 않는다`() {
		val response = send(port, "GET", "/health", "Origin", "https://evil.example")
		assertEquals(null, response.allowOrigin())
		assertEquals(403, send(port, "OPTIONS", "/charts", "Origin", "https://evil.example", "Access-Control-Request-Method", "POST").statusCode())
	}

	@Test
	fun `관리 화면의 주소를 정하지 않으면 admin 아래는 앱의 주소에도 열리지 않는다`() {
		for (path in listOf("/admin/auth/login", "/admin/me", "/admin")) {
			assertEquals(403, send(port, "OPTIONS", path, "Origin", APP, "Access-Control-Request-Method", "POST").statusCode(), path)
		}
		for (path in listOf("/admin/me", "/admin/inquiries", "/admin")) {
			val response = send(port, "GET", path, "Origin", APP)
			assertEquals(403, response.statusCode(), path)
			assertEquals(null, response.allowOrigin(), path)
		}
	}
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsOffByDefaultIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `허용 주소를 정하지 않으면 어떤 주소에도 허용 헤더를 붙이지 않는다`() {
		val response = send(port, "GET", "/health", "Origin", APP)
		assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent)
	}
}
