package com.byeolmyeong.api.report

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 뒤에서 일할 자리가 꽉 찼을 때. 스레드를 더 만들지 않고 그 리포트를 실패로 돌린다. */
@Import(TestcontainersConfiguration::class, ReportApiIntegrationTest.FakeConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.worker-threads=1", "byeolmyeong.report.queue-capacity=1"],
)
class ReportExecutorFullIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeReportGenerator

	@Autowired
	private lateinit var events: DoneEvents

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	@Test
	fun `일할 자리가 없으면 FAILED 로 답하고 자리가 나면 다시 만들 수 있다`() {
		jdbc.update("delete from accounts")
		generator.reset()
		val token = devToken(port)
		http(port, "PUT", "/profile", """{"date":"1990-03-15","time":"14:30","placeId":1841277}""", token)
		val gate = CountDownLatch(1).also { generator.gate = it }

		// 자리는 2개(일하는 1 + 기다리는 1)인데 영역은 4개다
		val posted = http(port, "POST", "/reports", token = token)

		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals("""{"status":"FAILED"}""", posted.body())
		assertEquals("""{"status":"FAILED"}""", http(port, "GET", "/reports/me", token = token).body())
		assertTrue(reportExecutor.poolSize <= 1, "스레드를 더 만들지 않는다")

		gate.countDown()
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline)
			Thread.sleep(10)
		}
		assertNull(jdbc.queryForObject("select body::text from reports", String::class.java), "받아 둔 일부 영역도 저장하지 않는다")
		assertTrue(generator.requests.size <= 2, "자리를 얻은 영역만 불렀다")
		assertTrue(events.owners.isEmpty())
	}
}
