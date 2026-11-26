package com.byeolmyeong.api.web

import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 큰 JSON 응답은 받는 쪽이 gzip 을 받으면 줄여 보낸다. 작은 응답은 그대로(줄여 봐야 머리만 붙는다). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompressionIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	private fun get(path: String, gzip: Boolean): HttpResponse<ByteArray> {
		val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET()
		if (gzip) request.header("Accept-Encoding", "gzip")
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
	}

	private fun HttpResponse<ByteArray>.encoding(): String? = headers().firstValue("Content-Encoding").orElse(null)

	@Test
	fun `큰 JSON(출생지 전체 목록)은 gzip 을 받는 쪽에 줄여 보내고 풀면 같은 본문이다`() {
		val plain = get("/places", gzip = false)
		val zipped = get("/places", gzip = true)

		assertEquals(200, zipped.statusCode())
		assertNull(plain.encoding(), "gzip 을 말하지 않은 쪽에는 그대로")
		assertEquals("gzip", zipped.encoding())
		assertTrue(zipped.body().size * 2 < plain.body().size, "절반 아래로 준다: ${plain.body().size} → ${zipped.body().size}")
		assertEquals(String(plain.body()), String(GZIPInputStream(ByteArrayInputStream(zipped.body())).readAllBytes()))
	}

	@Test
	fun `작은 응답은 gzip 을 받는 쪽이어도 줄이지 않는다`() {
		val health = get("/health", gzip = true)

		assertEquals(200, health.statusCode())
		assertNull(health.encoding())
	}
}
