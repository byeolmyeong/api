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
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

private const val PROFILE = """{"date":"1990-03-15","time":"14:30","placeId":1841277}"""
private const val WRITING = """{"status":"WRITING"}"""
private const val FAILED = """{"status":"FAILED"}"""

/**
 * 뒤에서 일할 자리가 꽉 찼을 때. 자리는 6개(일하는 1 + 기다리는 5)이고 리포트 한 편의 일은 5개다:
 * 앞 사람의 리포트가 자리 5개를 쓰고 있으면 뒤 사람의 리포트는 첫 일만 줄에 서고 둘째 일에서 거절된다.
 */
@Import(TestcontainersConfiguration::class, ReportApiIntegrationTest.FakeConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.worker-threads=1", "byeolmyeong.report.queue-capacity=5"],
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

	private fun until(what: String, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (!condition()) {
			check(System.nanoTime() < deadline) { what }
			Thread.sleep(10)
		}
	}

	private fun idle() = until("뒤에서 도는 생성이 끝나지 않는다") { reportExecutor.activeCount == 0 && reportExecutor.queueSize == 0 }

	private fun post(token: String) = http(port, "POST", "/reports", token = token)

	private fun get(token: String) = http(port, "GET", "/reports/me", token = token)

	@Test
	fun `일할 자리가 없으면 FAILED 로 답하고 줄에 선 그 리포트의 일은 모델을 부르지 않으며 자리가 나면 다시 만들 수 있다`() {
		jdbc.update("delete from accounts")
		generator.reset()
		events.owners.clear()
		val first = devToken(port)
		http(port, "PUT", "/profile", PROFILE, first)
		val second = "another-accounts-session-token"
		val secondOwner = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", secondOwner)
		jdbc.update(
			"insert into sessions (token_hash, account_id) values (?, ?)",
			HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(second.toByteArray())), secondOwner,
		)
		http(port, "PUT", "/profile", PROFILE, second)
		val gate = CountDownLatch(1).also { generator.gate = it }

		// 앞 사람: 일 하나는 일꾼이 붙잡고(생성기가 답하지 않는다) 넷은 줄에 있다. 줄에 한 자리가 남는다
		assertEquals(WRITING, post(first).body())
		until("앞 사람의 일이 시작되지 않는다") { generator.requests.size + generator.timingRequests.size == 1 }
		assertEquals(4, reportExecutor.queueSize)

		// 뒤 사람: 첫 일은 줄에 서고 둘째 일이 거절된다
		val posted = post(second)

		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals(FAILED, posted.body())
		assertEquals(FAILED, get(second).body())
		assertEquals(5, reportExecutor.queueSize, "뒤 사람의 첫 일은 줄에 남아 있다")
		assertEquals(WRITING, get(first).body(), "앞 사람의 리포트는 그대로 쓰는 중이다")

		gate.countDown()
		until("앞 사람의 리포트가 완성되지 않는다") { get(first).body().contains("\"status\":\"DONE\"") }
		idle()

		assertEquals(4, generator.requests.size, "앞 사람의 네 영역뿐이다: 거절된 리포트의 줄에 선 일은 차례가 와도 모델을 부르지 않는다")
		assertEquals(1, generator.timingRequests.size)
		assertEquals(FAILED, get(second).body())
		assertEquals(1, jdbc.queryForObject("select count(*) from reports where body is not null", Int::class.java), "거절된 리포트는 아무것도 저장하지 않는다")
		assertEquals(1, events.owners.size)

		// 자리가 났다
		assertEquals(WRITING, post(second).body())
		until("뒤 사람의 리포트가 완성되지 않는다") { get(second).body().contains("\"status\":\"DONE\"") }
		idle()
		assertEquals(8, generator.requests.size)
		assertEquals(secondOwner, events.owners.last())
	}
}
