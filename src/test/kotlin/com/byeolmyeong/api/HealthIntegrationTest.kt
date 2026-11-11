package com.byeolmyeong.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Test
	fun `빈 DB 에 Flyway 가 첫 마이그레이션을 적용한다`() {
		val applied = jdbc.queryForObject(
			"select count(*) from flyway_schema_history where version = '1' and success",
			Int::class.java,
		)
		assertEquals(1, applied)
	}

	@Test
	fun `health 가 200 을 준다`() {
		val response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI("http://localhost:$port/health")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(200, response.statusCode())
		assertEquals("""{"status":"ok"}""", response.body())
	}
}
