package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 서버를 닫는 중(결정 046 — `ReportExecutorDrain` 이 일꾼에 새 일을 받지 않게 한 뒤)에도 맡은 궁합은 끝까지 쓴다(결정 053 의 3).
 * 요약을 마친 궁합은 네 영역을 일꾼에 맡기지 못하므로 그 스레드에서 이어 쓴다. 이 시험은 일꾼을 닫으므로 컨텍스트를 버린다.
 */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.worker-threads=1", "byeolmyeong.report.queue-capacity=10"],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SynastryShutdownIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private fun until(what: String, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
		while (!condition()) {
			check(System.nanoTime() < deadline) { what }
			Thread.sleep(20)
		}
	}

	private fun status(id: String): String = jdbc.queryForObject("select status from synastries where id = ?", String::class.java, UUID.fromString(id))!!

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `서버를 닫는 중이어도 요약을 쓰던 궁합과 줄에서 기다리던 궁합은 네 영역을 그 스레드에서 이어 써서 완성된다`(output: CapturedOutput) {
		jdbc.update("delete from accounts")
		generator.reset()
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
		val summaryGate = CountDownLatch(1)
		generator.respond = { request, call ->
			if (request.purpose == SUMMARY_PURPOSE) summaryGate.await(30, TimeUnit.SECONDS)
			validAnswer(request, call)
		}
		fun post() = mapper.readTree(http(port, "POST", "/synastry", directBody(), token).body())["id"].asString()

		// 하나뿐인 일꾼이 앞 궁합의 요약을 쓰는 중이고, 뒤 궁합의 요약은 줄에서 기다린다
		val first = post()
		until("앞 궁합의 요약이 시작되지 않는다") { generator.summaries.size == 1 }
		val second = post()
		until("뒤 궁합의 요약이 줄에 서지 않는다") { reportExecutor.queueSize == 1 }

		// 서버를 닫기 시작한다: ReportExecutorDrain 처럼 일꾼이 새 일을 받지 않는다
		reportExecutor.threadPoolExecutor.shutdown()
		summaryGate.countDown()

		until("궁합이 끝나지 않는다") { status(first) != "WRITING" && status(second) != "WRITING" }
		assertEquals("DONE", status(first))
		assertEquals("DONE", status(second))
		assertTrue(reportExecutor.threadPoolExecutor.awaitTermination(30, TimeUnit.SECONDS), "이어 쓴 뒤 일꾼이 닫힌다")
		assertEquals(2, generator.summaries.size)
		assertEquals(8, generator.areas.size)
		assertTrue(output.all.contains("event=synastry_report_inline reason=SHUTTING_DOWN"), "닫는 중에 이어 썼다고 남긴다")
		assertFalse(output.all.contains("reason=EXECUTOR_FULL"), "꽉 찬 것과 닫는 중을 나눈다")
	}
}
