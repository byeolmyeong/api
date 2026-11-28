package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import com.byeolmyeong.api.push.RecordingExpoPush
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * "○○ 님과의 궁합이 나왔어요" 의 30초 규칙(결정 053 의 8): 완성 뒤 정해진 시간 안에 결과를 받아 가지(GET 이 DONE) 않은 궁합만, 한 번,
 * 알림함 + 푸시로 알린다. 여기서는 기다리는 시간을 한 시간으로 두고 `done_at` 을 옮겨 시간이 지난 것을 만든다(도는 일은 1초마다).
 */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class, SynastryReadyNoticeIntegrationTest.FakeExpo::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000",
		"byeolmyeong.synastry.notice-after-seconds=3600", "byeolmyeong.synastry.notice-check-seconds=1",
	],
)
class SynastryReadyNoticeIntegrationTest {

	@TestConfiguration
	class FakeExpo {
		@Bean
		@Primary
		fun recordingExpoPush() = RecordingExpoPush()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	@Autowired
	private lateinit var expo: RecordingExpoPush

	@Autowired
	private lateinit var synastries: SynastryService

	private lateinit var token: String

	private fun until(what: String, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (!condition()) {
			check(System.nanoTime() < deadline) { what }
			Thread.sleep(20)
		}
	}

	@BeforeEach
	fun setUp() {
		until("뒤에서 도는 생성이 끝나지 않는다") { reportExecutor.activeCount == 0 && reportExecutor.queueSize == 0 }
		jdbc.update("delete from accounts")
		generator.reset()
		expo.sent.clear()
		token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
		// 푸시 수신 동의 + 기기 하나(서비스 알림이라 푸시 동의만 본다 — 앱 결정 104 의 9)
		jdbc.update("update accounts set push_consented_at = now()")
		jdbc.update("insert into push_devices (expo_token, account_id, platform) select 'ExponentPushToken[synastry]', id, 'IOS' from accounts")
	}

	@AfterEach
	fun tearDown() = until("뒤에서 도는 생성이 끝나지 않는다") { reportExecutor.activeCount == 0 && reportExecutor.queueSize == 0 }

	/** 만들고 완성될 때까지 DB 로만 기다린다(GET 을 부르면 받아 간 것이 된다). */
	private fun doneWithoutSeeing(name: String): UUID {
		val response = http(port, "POST", "/synastry", directBody(name = name), token)
		assertEquals(202, response.statusCode(), response.body())
		val id = UUID.fromString(mapper.readTree(response.body())["id"].asString())
		until("궁합이 완성되지 않는다") { jdbc.queryForObject("select status from synastries where id = ?", String::class.java, id) == "DONE" }
		return id
	}

	private fun ago(id: UUID) = jdbc.update("update synastries set done_at = now() - interval '2 hours' where id = ?", id)

	private fun notified(): List<String?> = jdbc.queryForList("select kind || ':' || synastry_id from notifications order by created_at", String::class.java)

	@Test
	fun `완성 뒤 정해진 시간 안에 받아 가지 않은 궁합만 한 번 알림함과 푸시로 알린다`() {
		val unseen = doneWithoutSeeing("지우")
		val recent = doneWithoutSeeing("하늘")
		val seen = doneWithoutSeeing("바다")
		assertEquals("DONE", mapper.readTree(http(port, "GET", "/synastry/$seen", token = token).body())["status"].asString())
		ago(unseen)
		ago(seen)

		until("알림이 가지 않는다") { notified().isNotEmpty() && expo.sent.isNotEmpty() }
		Thread.sleep(2_500) // 도는 일이 두어 번 더 돌아도 더 가지 않는지 본다

		assertEquals(listOf("SYNASTRY_REPORT_DONE:$unseen"), notified())
		assertEquals(listOf(unseen), jdbc.queryForList("select id from synastries where notified_at is not null", UUID::class.java))
		val push = expo.sent.single()
		assertEquals("ExponentPushToken[synastry]", push.to)
		assertEquals("지우 님과의 궁합이 나왔어요", push.title)
		assertEquals(mapOf("type" to "SYNASTRY_REPORT_DONE", "synastryId" to unseen.toString()), push.data)
		// 받아 간 궁합 · 아직 시간이 안 된 궁합은 알리지 않는다
		assertEquals(listOf(recent, seen).toSet(), jdbc.queryForList("select id from synastries where notified_at is null", UUID::class.java).toSet())
	}

	@Test
	fun `두 서버가 함께 돌아도 한 번만 알린다`() {
		val id = doneWithoutSeeing("지우")

		// 도는 일(1초마다)과 다른 서버의 도는 일 넷이 한꺼번에 잡으려 한다
		ago(id)
		(1..4).map { CompletableFuture.runAsync { synastries.noticeReady() } }.forEach { it.join() }
		until("알림이 가지 않는다") { expo.sent.isNotEmpty() }
		Thread.sleep(2_500)

		assertEquals(listOf("SYNASTRY_REPORT_DONE:$id"), notified())
		assertEquals(1, expo.sent.size)
	}
}
