package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 궁합과 생성 한도(결정 022 · 023 · 053): 한 계정이 분당 2번까지만 모델을 부를 수 있게 둔다. */
@Import(TestcontainersConfiguration::class, FakeSynastryConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000",
		"byeolmyeong.rate-limit.generation-per-minute=2", "byeolmyeong.rate-limit.generation-per-hour=1000",
	],
)
class SynastryQuotaIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeSynastryGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private lateinit var token: String

	private fun settle() {
		generator.gate?.countDown()
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline) { "뒤에서 도는 생성이 끝나지 않는다" }
			Thread.sleep(10)
		}
	}

	// 계정을 지우고 새로 만들면 생성 한도(계정 id 마다 센다)도 새로 시작한다
	@BeforeEach
	fun setUp() {
		settle()
		jdbc.update("delete from accounts")
		generator.reset()
		token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
	}

	@AfterEach
	fun tearDown() = settle()

	private fun profile(date: String, time: String) =
		assertEquals(200, http(port, "PUT", "/profile", """{"date":"$date","time":"$time","placeId":$MAPO}""", token).statusCode())

	private fun hash(): String = jdbc.queryForObject("select owner_chart_hash from synastries", String::class.java)!!

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보를 바꾼 뒤 열 때의 다시 쓰기도 생성 한도를 세고 넘으면 옛 글을 그대로 주고 다음에 다시 시도한다`(output: CapturedOutput) {
		val id = createSynastry(port, token, directBody())["id"].asString() // 한도 1
		profile("1993-11-02", "08:45")
		assertEquals("WRITING", mapper.readTree(http(port, "GET", "/synastry/$id", token = token).body())["status"].asString()) // 한도 2
		awaitSynastry(port, token, id)
		settle()
		val writtenFor = hash()
		val calls = generator.requests.size

		// 다시 바꾸고 연다: 한도를 넘었으니 다시 쓰지 않고 옛 글을 준다(사용자는 실패를 보지 않는다)
		profile(MY_DATE, MY_TIME)
		val opened = http(port, "GET", "/synastry/$id", token = token)

		assertEquals(200, opened.statusCode(), opened.body())
		val synastry = mapper.readTree(opened.body())
		assertEquals("DONE", synastry["status"].asString())
		assertEquals("가짜한줄요약", synastry["headline"].asString())
		assertTrue(synastry.has("report") && synastry.has("reasons"))
		assertEquals(expectedScore("1988-07-21", com.byeolmyeong.api.chart.Relation.LOVE), synastry["score"].asInt(), "점수는 새 차트의 값이다")
		settle()
		assertEquals(calls, generator.requests.size, "모델을 부르지 않는다")
		assertEquals(writtenFor, hash(), "글은 여전히 옛 차트의 것이라 다음에 열 때 다시 시도한다")
		assertTrue(output.all.contains("event=synastry_rewrite_deferred reason=RATE_LIMITED"))
	}
}
