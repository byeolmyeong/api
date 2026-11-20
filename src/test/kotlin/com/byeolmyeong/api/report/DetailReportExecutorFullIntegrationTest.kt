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
import kotlin.test.assertTrue

private const val PROFILE = """{"date":"1990-03-15","time":"14:30","placeId":1841277}"""
private const val WRITING = """{"status":"WRITING"}"""
private const val FAILED = """{"status":"FAILED"}"""

/**
 * 자세한 풀이의 공통 고리(`DetailReportService`, 결정 029) — 뒤에서 일할 자리가 꽉 찼을 때. 자리는 6개(일하는 1 + 기다리는 5)다.
 * 앞 사람의 나 탭 풀이(영역 다섯)가 자리 5개를 쓰고 있으면, 뒤 사람의 시기 풀이(묶음 둘 이상)는 첫 묶음만 줄에 서고 둘째에서 거절된다 —
 * 두 풀이가 같은 고리 · 같은 일꾼을 쓴다.
 */
@Import(TestcontainersConfiguration::class, TimingReportApiIntegrationTest.FakeConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.worker-threads=1", "byeolmyeong.report.queue-capacity=5"],
)
class DetailReportExecutorFullIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeDetailGenerator

	@Autowired
	private lateinit var meDone: MeDoneEvents

	@Autowired
	private lateinit var timingDone: TimingDoneEvents

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

	@Test
	fun `일할 자리가 없으면 FAILED 로 답하고 줄에 선 그 풀이의 묶음은 모델을 부르지 않으며 자리가 나면 다시 만들 수 있다`() {
		jdbc.update("delete from accounts")
		generator.reset()
		meDone.owners.clear()
		timingDone.owners.clear()
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

		// 앞 사람의 나 탭 풀이: 영역 하나는 일꾼이 붙잡고(생성기가 답하지 않는다) 넷은 줄에 있다. 줄에 한 자리가 남는다
		assertEquals(WRITING, http(port, "POST", "/me-report", token = first).body())
		until("앞 사람의 일이 시작되지 않는다") { generator.requests.size == 1 }
		assertEquals(4, reportExecutor.queueSize)

		// 뒤 사람의 시기 풀이: 첫 묶음은 줄에 서고 둘째 묶음이 거절된다
		val posted = http(port, "POST", "/timing-report", token = second)

		assertEquals(202, posted.statusCode(), posted.body())
		assertEquals(FAILED, posted.body())
		assertEquals(FAILED, http(port, "GET", "/timing-report", token = second).body())
		assertEquals(5, reportExecutor.queueSize, "뒤 사람의 첫 묶음은 줄에 남아 있다")
		assertEquals(WRITING, http(port, "GET", "/me-report", token = first).body(), "앞 사람의 풀이는 그대로 쓰는 중이다")

		gate.countDown()
		until("앞 사람의 풀이가 완성되지 않는다") { http(port, "GET", "/me-report", token = first).body().contains("\"status\":\"DONE\"") }
		idle()

		assertEquals(5, generator.meRequests().size)
		assertEquals(0, generator.timingRequests().size, "거절된 풀이의 줄에 선 묶음은 차례가 와도 모델을 부르지 않는다")
		assertEquals(FAILED, http(port, "GET", "/timing-report", token = second).body())
		assertEquals(1, jdbc.queryForObject("select count(*) from detail_reports where body is not null", Int::class.java), "거절된 풀이는 아무것도 저장하지 않는다")
		assertEquals(1, meDone.owners.size)

		// 자리가 났다
		assertEquals(WRITING, http(port, "POST", "/timing-report", token = second).body())
		until("뒤 사람의 시기 풀이가 완성되지 않는다") { http(port, "GET", "/timing-report", token = second).body().contains("\"status\":\"DONE\"") }
		idle()
		assertTrue(generator.timingRequests().size >= 2)
		assertEquals(listOf(secondOwner), timingDone.owners.toList())
	}
}
