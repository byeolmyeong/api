package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import com.byeolmyeong.api.report.FakeRevenueCat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.TestPropertySource
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

private const val PROFILE = """{"date":"1990-03-15","time":"14:30","placeId":1841277}"""

/**
 * 관리자의 다시 만들기(결정 043 의 1)도 사용자가 누른 것과 같은 판정을 거친다(결정 056 의 11): 결제 시작 뒤 안 산 사용자는 402, RevenueCat 을 묻지 못하면 503 이
 * 관리자에게 그대로 간다. 관리자 요청에는 앱 버전 머리가 없어 426 은 없다. 결제 시작 시각은 지난 때로 둔다.
 */
@AdminIntegrationTest
@Import(AdminRegenerateEntitlementIntegrationTest.Fakes::class)
@TestPropertySource(properties = ["byeolmyeong.purchase.starts-at=2026-01-01T00:00:00+09:00", "byeolmyeong.purchase.revenuecat-secret-key=not-used-fake-revenuecat"])
class AdminRegenerateEntitlementIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class Fakes {
		@Bean
		@Primary
		fun regenerateGenerator() = FailingGenerator()

		@Bean
		@Primary
		fun regenerateRevenueCat() = FakeRevenueCat()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var revenueCat: FakeRevenueCat

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private lateinit var token: String

	private fun settle() {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline) { "뒤에서 도는 생성이 끝나지 않는다" }
			Thread.sleep(10)
		}
	}

	@BeforeEach
	fun clean() {
		settle()
		resetAdminData(jdbc)
		revenueCat.reset()
		token = adminToken(port)
	}

	@AfterEach
	fun drain() = settle()

	/** 출생 정보가 있는 계정 하나와 실패한 나 탭 풀이 한 줄(다른 차트). */
	private fun userWithFailedReport(): UUID {
		val (id, session) = userAccount(jdbc, "user-${UUID.randomUUID()}")
		assertEquals(200, http(port, "PUT", "/profile", PROFILE, session).statusCode())
		jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, started_at) values (?, ?, 'ME', 'old-chart', 'FAILED', now() - interval '1 hour')",
			UUID.randomUUID(), id,
		)
		return id
	}

	private fun regenerate(id: UUID): HttpResponse<String> = http(port, "POST", "/admin/users/$id/regenerate", """{"kind":"REPORT_ME"}""", token)

	private fun rows(id: UUID): Int = jdbc.queryForObject("select count(*) from detail_reports where owner_id = ?", Int::class.java, id)!!

	@Test
	fun `결제 시작 뒤 안 산 사용자의 다시 만들기는 관리자에게 402 이고 새 행이 없다`() {
		val id = userWithFailedReport()

		val response = regenerate(id)

		assertEquals(402 to """{"error":"PURCHASE_REQUIRED"}""", response.statusCode() to response.body())
		assertEquals(1, rows(id))
		assertEquals(listOf(id), revenueCat.asked.toList())
	}

	@Test
	fun `RevenueCat 을 묻지 못하면 관리자에게 503 이다`() {
		val id = userWithFailedReport()
		revenueCat.fails()

		val response = regenerate(id)

		assertEquals(503 to """{"error":"PURCHASE_CHECK_FAILED"}""", response.statusCode() to response.body())
		assertEquals(1, rows(id))
	}

	@Test
	fun `상품이 있는 사용자는 다시 만들고 버전 머리가 없어 426 이 아니다`() {
		val id = userWithFailedReport()
		jdbc.update("insert into entitlements (account_id, source) values (?, 'LEGACY')", id)

		assertEquals(202, regenerate(id).statusCode())
		assertEquals(2, rows(id))
	}
}
