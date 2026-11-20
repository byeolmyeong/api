package com.byeolmyeong.api.report

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
private const val DONE_STATUS = "\"status\":\"DONE\""

/**
 * 자세한 풀이의 공통 고리(결정 029) — 앞 사람의 나 탭 풀이 때문에 줄에서 기다리는 시기 풀이. 일꾼이 하나라 뒤 사람의 묶음은 앞 사람이 끝날 때까지 시작도 못 한다.
 * 기다린 시간만으로는 멈춘 것이 아니다: 서버가 살아 있는 동안은 두 종류의 행 모두에 살아 있다는 표시(`active_at`)를 계속 적는다(여기서는 1초마다).
 */
@Import(TestcontainersConfiguration::class, TimingReportApiIntegrationTest.FakeConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.report.worker-threads=1", "byeolmyeong.report.heartbeat-seconds=1"],
)
class DetailReportQueueWaitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeDetailGenerator

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

	private fun settle() {
		generator.gate?.countDown()
		until("뒤에서 도는 생성이 끝나지 않는다") { reportExecutor.activeCount == 0 && reportExecutor.queueSize == 0 }
	}

	@BeforeEach
	@AfterEach
	fun clean() {
		settle()
		jdbc.update("delete from accounts")
		generator.reset()
		timingDone.owners.clear()
	}

	/** 앞 사람의 나 탭 풀이가 하나뿐인 일꾼을 붙잡고 있고(생성기가 답하지 않는다), 뒤 사람의 시기 풀이는 줄에서 기다린다. */
	private fun waitingBehindAnother(): Pair<String, UUID> {
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

		assertEquals(WRITING, http(port, "POST", "/me-report", token = first).body())
		until("앞 사람의 일이 시작되지 않는다") { generator.requests.size == 1 }
		assertEquals(WRITING, http(port, "POST", "/timing-report", token = second).body())
		return second to secondOwner
	}

	private fun timing(token: String) = http(port, "GET", "/timing-report", token = token).body()

	@Test
	fun `줄에서 10분 넘게 기다린 풀이도 WRITING 이고 차례가 오면 완성된다`() {
		val gate = CountDownLatch(1).also { generator.gate = it }
		val (second, secondOwner) = waitingBehindAnother()

		// 자리를 잡은 지 11분이 지났다
		jdbc.update("update detail_reports set started_at = started_at - interval '11 minutes' where owner_id = ?", secondOwner)

		assertEquals(WRITING, timing(second), "기다린 시간만으로 멈춘 것으로 보지 않는다")
		assertEquals(WRITING, http(port, "POST", "/timing-report", token = second).body(), "다시 시작하지도 않는다")

		// started_at 은 그 생성의 표식이기도 하다(끝낼 때 자기 것인지 가린다). 일이 시작되기 전에 되돌려 둔다
		jdbc.update("update detail_reports set started_at = started_at + interval '11 minutes' where owner_id = ?", secondOwner)
		gate.countDown()
		until("뒤 사람의 시기 풀이가 완성되지 않는다") { timing(second).contains(DONE_STATUS) }
		settle()

		assertEquals(5, generator.meRequests().size, "앞 사람의 다섯 영역을 한 번씩만")
		assertEquals(listOf(secondOwner), timingDone.owners.toList())
	}

	@Test
	fun `서버가 살아 있으면 줄에서 기다리는 풀이에도 살아 있다는 표시를 계속 적는다 - 종류를 가리지 않는다`() {
		val gate = CountDownLatch(1).also { generator.gate = it }
		val (second, _) = waitingBehindAnother()

		// 표시가 오래 끊긴 것처럼 옮겨 놓아도 다음 주기에 다시 적힌다(앞 사람의 ME 도, 기다리는 뒤 사람의 TIMING 도)
		jdbc.update("update detail_reports set active_at = now() - interval '11 minutes'")
		until("살아 있다는 표시가 다시 적히지 않는다") {
			jdbc.queryForObject("select count(*) from detail_reports where active_at > now() - interval '10 seconds'", Int::class.java) == 2
		}
		assertEquals(listOf("ME", "TIMING"), jdbc.queryForList("select kind from detail_reports order by kind", String::class.java))
		assertEquals(WRITING, timing(second))

		gate.countDown()
		until("뒤 사람의 시기 풀이가 완성되지 않는다") { timing(second).contains(DONE_STATUS) }
		settle()
	}
}
