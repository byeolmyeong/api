package com.byeolmyeong.api.report

import com.byeolmyeong.api.ByeolmyeongApiApplication
import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PROFILE = """{"date":"1990-03-15","time":"14:30","placeId":1841277}"""

/**
 * 배포에서 옛 색을 내릴 때(결정 046 의 4): 서버를 닫는 동안 쓰던 리포트를 끝까지 써서 저장하고, 새 요청은 받지 않는다.
 * 기다리는 동안에도 살아 있다는 표시를 적어, 다른 서버가 멈춘 것으로 보고 가로채지 않게 한다.
 * 서버를 직접 띄우고 닫는다. DB 는 서버 밖에 둬서 닫은 뒤에도 행을 본다.
 */
class GracefulShutdownIntegrationTest {

	private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17")).apply { start() }
	private val db = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

	private fun until(what: String, seconds: Long = 20, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
		while (!condition()) {
			check(System.nanoTime() < deadline) { what }
			Thread.sleep(20)
		}
	}

	/** 닫히는 중인 서버는 연결을 받지 않거나 오래 붙잡을 수 있어, 짧게 끊는 요청으로 본다. */
	private fun healthAnswers(port: Int): Boolean = runCatching {
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build().send(
			HttpRequest.newBuilder(URI("http://localhost:$port/health")).timeout(Duration.ofSeconds(1)).GET().build(),
			HttpResponse.BodyHandlers.discarding(),
		).statusCode() == 200
	}.getOrDefault(false)

	private fun status(): String = db.queryForObject("select status from reports", String::class.java)!!

	@Test
	fun `닫는 동안 쓰던 리포트를 끝까지 써서 저장하고 그동안 새 요청은 받지 않는다`() {
		val app = SpringApplicationBuilder(ByeolmyeongApiApplication::class.java, ReportApiIntegrationTest.FakeConfig::class.java)
			.run(
				"--server.port=0",
				"--spring.datasource.url=${postgres.jdbcUrl}",
				"--spring.datasource.username=${postgres.username}",
				"--spring.datasource.password=${postgres.password}",
				"--$DEV_AUTH_ON",
				"--byeolmyeong.report.heartbeat-seconds=1",
			)
		val port = app.environment.getRequiredProperty("local.server.port").toInt()
		val generator = app.getBean(FakeReportGenerator::class.java)
		val gate = CountDownLatch(1).also { generator.gate = it }

		var closing: Thread? = null
		try {
			val token = devToken(port)
			http(port, "PUT", "/profile", PROFILE, token)
			assertEquals("""{"status":"WRITING"}""", http(port, "POST", "/reports", token = token).body())
			// 네 영역과 시기가 모두 모델의 답을 기다린다
			until("생성이 시작되지 않는다") { generator.requests.size + generator.timingRequests.size == 5 }

			closing = thread(name = "closing") { app.close() }

			until("닫는 동안에도 새 요청을 받는다") { !healthAnswers(port) }
			// 표시가 오래 끊긴 것처럼 옮겨 놓아도, 닫히기를 기다리는 동안 다시 적힌다
			db.update("update reports set active_at = now() - interval '11 minutes'")
			until("닫는 동안 살아 있다는 표시가 멈췄다") {
				db.queryForObject("select count(*) from reports where active_at > now() - interval '10 seconds'", Int::class.java) == 1
			}
			assertTrue(closing!!.isAlive, "쓰던 생성이 끝나기 전에 닫혔다")
			assertEquals("WRITING", status())

			gate.countDown()
			closing.join(TimeUnit.SECONDS.toMillis(30))
			assertFalse(closing.isAlive, "생성이 끝났는데 닫히지 않는다")
			assertEquals("DONE", status(), "닫기 전에 끝까지 써서 저장한다")
			assertEquals(4, generator.requests.size, "끊고 다시 부르지 않았다")
			assertEquals(1, generator.timingRequests.size)
		} finally {
			gate.countDown()
			(closing ?: thread { app.close() }).join(TimeUnit.SECONDS.toMillis(30))
			postgres.stop()
		}
	}
}
