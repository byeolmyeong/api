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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 결제 시작 시각이 없는 서버(결정 056 의 1 — 1.1.0 출시 전의 운영): 누구나 받고, 426 이 없고, RevenueCat 을 묻지 않는다. */
@Import(TestcontainersConfiguration::class, EntitlementNoStartIntegrationTest.Fakes::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class EntitlementNoStartIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class Fakes {
		@Bean
		@Primary
		fun noStartRevenueCat() = FakeRevenueCat()

		@Bean
		@Primary
		fun noStartGenerator() = GatedEmptyGenerator()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var revenueCat: FakeRevenueCat

	@Autowired
	private lateinit var generator: GatedEmptyGenerator

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	@BeforeEach
	@AfterEach
	fun clean() {
		generator.reset()
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline) { "뒤에서 도는 생성이 끝나지 않는다" }
			Thread.sleep(10)
		}
		jdbc.update("delete from accounts")
		revenueCat.reset()
	}

	@Test
	fun `결제 시작 시각이 없으면 안 산 계정 · 1점0점0 앱도 두 풀이를 받고 RevenueCat 을 묻지 않는다`() {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", """{"date":"1990-03-15","time":null,"placeId":null}""", token).statusCode())
		revenueCat.fails()

		// 1.0.0 앱의 버전 머리가 있어도 426 이 없다(결정 056 의 5 — 결제 시작 뒤에만)
		assertEquals(202, http(port, "POST", "/me-report", null, token, "X-App-Platform", "ios", "X-App-Version", "1.0.0").statusCode())
		assertEquals(202, http(port, "POST", "/timing-report", token = token).statusCode())

		assertEquals(2, jdbc.queryForObject("select count(*) from detail_reports", Int::class.java))
		assertTrue(revenueCat.asked.isEmpty())
	}
}
