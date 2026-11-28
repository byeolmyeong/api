package com.byeolmyeong.api.report

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.MutableClock
import com.byeolmyeong.api.birth.BirthProfileService
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
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PROFILE = """{"date":"1990-03-15","time":"14:30","placeId":1841277}"""
private const val OTHER_PROFILE = """{"date":"1991-07-02","time":"09:10","placeId":1841277}"""
private const val PURCHASE_REQUIRED = """{"error":"PURCHASE_REQUIRED"}"""
private const val PURCHASE_CHECK_FAILED = """{"error":"PURCHASE_CHECK_FAILED"}"""
private const val UPDATE_REQUIRED = """{"error":"UPDATE_REQUIRED"}"""

/** 결제 시작 시각(이 테스트의 설정). 한국 2026-12-10 00:00. */
private val PURCHASE_STARTS: Instant = Instant.parse("2026-12-09T15:00:00Z")

/**
 * 자세한 풀이 받기의 판정(결정 056 의 3 · 4): 시작 전 → 모두, 시작 뒤 → 결제 없이 여는 목록 · 표 · RevenueCat(1분 캐시, 있으면 STORE) · 아니면 402,
 * RevenueCat 을 묻지 못하면 503. 이미 완성 · 쓰는 중은 묻지 않는다. 시계 · RevenueCat · 생성기는 가짜다.
 */
@Import(TestcontainersConfiguration::class, EntitlementCheckIntegrationTest.Fakes::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.purchase.starts-at=2026-12-10T00:00:00+09:00"],
)
class EntitlementCheckIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class Fakes {
		@Bean
		@Primary
		fun checkClock() = MutableClock(PURCHASE_STARTS.plusSeconds(3600))

		@Bean
		@Primary
		fun checkRevenueCat() = FakeRevenueCat()

		@Bean
		@Primary
		fun checkGenerator() = GatedEmptyGenerator()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var clock: MutableClock

	@Autowired
	private lateinit var revenueCat: FakeRevenueCat

	@Autowired
	private lateinit var generator: GatedEmptyGenerator

	@Autowired
	private lateinit var profiles: BirthProfileService

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private fun settle() {
		generator.reset()
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (reportExecutor.activeCount > 0 || reportExecutor.queueSize > 0) {
			check(System.nanoTime() < deadline) { "뒤에서 도는 생성이 끝나지 않는다" }
			Thread.sleep(10)
		}
	}

	@BeforeEach
	@AfterEach
	fun clean() {
		settle()
		jdbc.update("delete from accounts")
		jdbc.update("delete from audit_logs")
		jdbc.update("delete from entitlement_allowlist")
		revenueCat.reset()
		clock.now = PURCHASE_STARTS.plusSeconds(3600)
	}

	private fun token(): String = devToken(port).also { assertEquals(200, http(port, "PUT", "/profile", PROFILE, it).statusCode()) }

	private fun ownerId(): UUID = jdbc.queryForObject("select id from accounts", UUID::class.java)!!

	private fun post(path: String, token: String): HttpResponse<String> = http(port, "POST", path, token = token)

	/** 앱이 보내는 버전 머리를 실어 받기를 누른다. */
	private fun postFromApp(path: String, token: String, version: String): HttpResponse<String> =
		http(port, "POST", path, null, token, "X-App-Platform", "ios", "X-App-Version", version)

	private fun reportRows(): List<String> = jdbc.queryForList("select kind from detail_reports order by kind", String::class.java).filterNotNull()

	private fun entitlementRows(): List<String> = jdbc.queryForList("select source from entitlements order by source", String::class.java).filterNotNull()

	private fun requestedAudits(): Int = jdbc.queryForObject("select count(*) from audit_logs where action = 'REPORT_REQUESTED'", Int::class.java)!!

	@Test
	fun `결제 시작 시각 전에는 안 산 계정도 받고 RevenueCat 을 묻지 않는다`() {
		clock.now = PURCHASE_STARTS.minusSeconds(1)
		val token = token()

		assertEquals(202, post("/me-report", token).statusCode())
		assertEquals(202, post("/timing-report", token).statusCode())

		assertEquals(listOf("ME", "TIMING"), reportRows())
		assertTrue(revenueCat.asked.isEmpty())
		assertEquals(listOf("LEGACY"), entitlementRows(), "시작 전에 누른 받기는 1.0.0 수령자로 적는다")
	}

	@Test
	fun `결제 시작 전에 누른 받기는 완성이 시작 뒤여도 1점0점0 수령자라 다른 풀이도 받는다`() {
		clock.now = PURCHASE_STARTS.minusSeconds(1)
		val token = token()
		generator.gate = CountDownLatch(1)
		assertEquals(202 to """{"status":"WRITING"}""", post("/me-report", token).let { it.statusCode() to it.body() })

		clock.now = PURCHASE_STARTS.plusSeconds(1)
		settle() // 시작 뒤에 끝난다(이 생성기는 글을 주지 않아 실패로 끝난다 — 실패만 한 받기도 수령자다)

		assertEquals(202, post("/timing-report", token).statusCode())
		assertEquals(listOf("LEGACY"), entitlementRows())
		assertTrue(revenueCat.asked.isEmpty())
	}

	@Test
	fun `안 산 사람은 402 이고 행 · 감사 기록이 없으며 RevenueCat 의 답은 1분 동안 다시 묻지 않는다`() {
		val token = token()

		val first = post("/me-report", token)
		assertEquals(402, first.statusCode())
		assertEquals(PURCHASE_REQUIRED, first.body())
		assertEquals(listOf(ownerId()), revenueCat.asked.toList())

		assertEquals(402, post("/timing-report", token).statusCode())
		clock.now = clock.now.plusSeconds(59)
		assertEquals(402, post("/me-report", token).statusCode())
		assertEquals(1, revenueCat.asked.size, "1분 안에는 담아 둔 답을 쓴다")

		clock.now = clock.now.plusSeconds(2)
		assertEquals(402, post("/me-report", token).statusCode())
		assertEquals(2, revenueCat.asked.size, "1분이 지나면 다시 묻는다")

		assertEquals(emptyList(), reportRows())
		assertEquals(emptyList(), entitlementRows())
		assertEquals(0, requestedAudits())
	}

	@Test
	fun `산 사람은 받고 STORE 로 적혀 다음부터는 RevenueCat 을 묻지 않는다`() {
		val token = token()
		revenueCat.answer = { true }

		assertEquals(202, post("/me-report", token).statusCode())
		assertEquals(listOf("STORE"), entitlementRows())

		revenueCat.answer = { false }
		assertEquals(202, post("/timing-report", token).statusCode())

		assertEquals(1, revenueCat.asked.size)
		assertEquals(listOf("ME", "TIMING"), reportRows())
	}

	@Test
	fun `결제 없이 여는 목록의 이메일이면 대소문자와 상관없이 받고 표에 적지 않는다`() {
		val token = token()
		jdbc.update("update accounts set email = 'Team.Member@Example.com'")
		val admin = UUID.randomUUID()
		jdbc.update("insert into admin_accounts (id, login_id, password_hash, name) values (?, ?, 'x', '관리자')", admin, "admin-$admin")
		jdbc.update("insert into entitlement_allowlist (id, email, created_by) values (?, 'team.member@example.com', ?)", UUID.randomUUID(), admin)

		assertEquals(202, post("/me-report", token).statusCode())
		assertEquals(202, post("/timing-report", token).statusCode())

		assertTrue(revenueCat.asked.isEmpty())
		assertEquals(emptyList(), entitlementRows())

		// 목록에서 빼면 바로 닫힌다
		jdbc.update("delete from entitlement_allowlist")
		jdbc.update("delete from detail_reports")
		assertEquals(402, post("/me-report", token).statusCode())
	}

	@Test
	fun `1점0점0 수령자는 출생 정보를 바꿔도 받는다`() {
		val token = token()
		jdbc.update("insert into entitlements (account_id, source) values (?, 'LEGACY')", ownerId())
		assertEquals(202, post("/me-report", token).statusCode())
		settle()

		assertEquals(200, http(port, "PUT", "/profile", OTHER_PROFILE, token).statusCode())
		assertEquals(emptyList(), reportRows(), "옛 차트의 풀이는 지워진다(결정 041)")

		assertEquals(202, post("/me-report", token).statusCode())
		assertEquals(202, post("/timing-report", token).statusCode())
		assertEquals(listOf("LEGACY"), entitlementRows())
		assertTrue(revenueCat.asked.isEmpty())
	}

	@Test
	fun `이미 완성됐거나 쓰는 중인 풀이는 묻지 않고 그 상태다`() {
		val token = token()
		val owner = ownerId()
		val hash = profiles.chartInput(owner)!!.hash
		jdbc.update("insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, 'ME', ?, 'DONE', '{}'::jsonb)", UUID.randomUUID(), owner, hash)
		jdbc.update("insert into detail_reports (id, owner_id, kind, chart_hash, status) values (?, ?, 'TIMING', ?, 'WRITING')", UUID.randomUUID(), owner, hash)
		revenueCat.fails()

		val me = post("/me-report", token)
		val timing = post("/timing-report", token)

		assertEquals(202 to """{"status":"DONE"}""", me.statusCode() to me.body())
		assertEquals(202 to """{"status":"WRITING"}""", timing.statusCode() to timing.body())
		assertTrue(revenueCat.asked.isEmpty())
	}

	@Test
	fun `RevenueCat 을 묻지 못하면 503 이고 행이 없으며 실패는 담아 두지 않는다`() {
		val token = token()
		revenueCat.fails()

		val failed = post("/me-report", token)
		assertEquals(503, failed.statusCode())
		assertEquals(PURCHASE_CHECK_FAILED, failed.body())
		assertEquals(emptyList(), reportRows())

		revenueCat.answer = { true }
		assertEquals(202, post("/me-report", token).statusCode(), "실패는 담아 두지 않아 바로 다시 묻는다")
		assertEquals(2, revenueCat.asked.size)
	}

	@Test
	fun `1점1점0 미만 앱의 새 받기는 상품이 있어도 426 이고 행이 없으며 RevenueCat 을 묻지 않는다`() {
		val token = token()
		jdbc.update("insert into entitlements (account_id, source) values (?, 'LEGACY')", ownerId())

		for (version in listOf("1.0.0", "1.0.9", "1")) {
			val me = postFromApp("/me-report", token, version)
			assertEquals(426 to UPDATE_REQUIRED, me.statusCode() to me.body(), version)
		}
		val timing = postFromApp("/timing-report", token, "1.0.0")
		assertEquals(426 to UPDATE_REQUIRED, timing.statusCode() to timing.body())
		assertEquals(emptyList(), reportRows())

		assertEquals(202, postFromApp("/me-report", token, "1.1.0").statusCode())
		assertEquals(202, postFromApp("/timing-report", token, "1.10").statusCode())
		assertTrue(revenueCat.asked.isEmpty())
	}

	@Test
	fun `버전 머리가 없는 웹과 읽을 수 없는 버전은 426 이 아니라 판정대로 402 다`() {
		val token = token()

		assertEquals(402 to PURCHASE_REQUIRED, post("/me-report", token).let { it.statusCode() to it.body() })
		assertEquals(402 to PURCHASE_REQUIRED, postFromApp("/timing-report", token, "1.0.0-beta").let { it.statusCode() to it.body() })
	}

	@Test
	fun `1점1점0 미만 앱도 이미 완성됐거나 쓰는 중인 풀이는 그 상태를 받는다`() {
		val token = token()
		val owner = ownerId()
		val hash = profiles.chartInput(owner)!!.hash
		jdbc.update("insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, 'ME', ?, 'DONE', '{}'::jsonb)", UUID.randomUUID(), owner, hash)
		jdbc.update("insert into detail_reports (id, owner_id, kind, chart_hash, status) values (?, ?, 'TIMING', ?, 'WRITING')", UUID.randomUUID(), owner, hash)

		assertEquals(202 to """{"status":"DONE"}""", postFromApp("/me-report", token, "1.0.0").let { it.statusCode() to it.body() })
		assertEquals(202 to """{"status":"WRITING"}""", postFromApp("/timing-report", token, "1.0.0").let { it.statusCode() to it.body() })
	}

	@Test
	fun `결제 시작 시각 전에는 1점0점0 앱도 새로 받는다`() {
		clock.now = PURCHASE_STARTS.minusSeconds(1)
		val token = token()

		assertEquals(202, postFromApp("/me-report", token, "1.0.0").statusCode())
		assertEquals(202, postFromApp("/timing-report", token, "1.0.0").statusCode())
	}

	private fun confirm(token: String): HttpResponse<String> = postFromApp("/entitlements/confirm", token, "1.1.0")

	@Test
	fun `결제 직후 확인은 RevenueCat 을 담아 둔 답 없이 묻고 있으면 STORE 로 적은 뒤 두 풀이를 함께 시작한다`() {
		val token = token()
		assertEquals(402, post("/me-report", token).statusCode(), "결제 전: 없음이 1분 담긴다")
		revenueCat.answer = { true }
		generator.gate = CountDownLatch(1)

		val confirmed = confirm(token)

		assertEquals(200 to """{"detail":true,"me":"WRITING","timing":"WRITING"}""", confirmed.statusCode() to confirmed.body())
		assertEquals(2, revenueCat.asked.size, "확인은 담아 둔 답을 쓰지 않는다")
		assertEquals(listOf("STORE"), entitlementRows())
		assertEquals(listOf("ME", "TIMING"), reportRows())
		assertEquals(2, requestedAudits())
	}

	@Test
	fun `확인을 다시 불러도 같고 이미 완성됐거나 쓰는 중인 풀이는 그대로다`() {
		val token = token()
		val owner = ownerId()
		jdbc.update(
			"insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, 'ME', ?, 'DONE', '{}'::jsonb)",
			UUID.randomUUID(), owner, profiles.chartInput(owner)!!.hash,
		)
		revenueCat.answer = { true }
		generator.gate = CountDownLatch(1)

		val first = confirm(token)
		val again = confirm(token)

		assertEquals(200 to """{"detail":true,"me":"DONE","timing":"WRITING"}""", first.statusCode() to first.body())
		assertEquals(200 to """{"detail":true,"me":"DONE","timing":"WRITING"}""", again.statusCode() to again.body())
		assertEquals(listOf("STORE"), entitlementRows())
		assertEquals(1, requestedAudits(), "시기 풀이 한 번만 시작했다")
	}

	@Test
	fun `스토어 구매가 없으면 detail false 이고 아무것도 시작하지 않는다 - 1점0점0 수령자여도`() {
		val token = token()
		jdbc.update("insert into entitlements (account_id, source) values (?, 'LEGACY')", ownerId())

		val response = confirm(token)

		assertEquals(200 to """{"detail":false}""", response.statusCode() to response.body())
		assertEquals(emptyList(), reportRows())
		assertEquals(listOf("LEGACY"), entitlementRows())
		assertEquals(401, http(port, "POST", "/entitlements/confirm").statusCode(), "세션이 있어야 한다")
	}

	@Test
	fun `확인에서 RevenueCat 을 묻지 못하면 503 이고 아무것도 적지 않는다`() {
		val token = token()
		revenueCat.fails()

		val response = confirm(token)

		assertEquals(503 to PURCHASE_CHECK_FAILED, response.statusCode() to response.body())
		assertEquals(emptyList(), entitlementRows())
		assertEquals(emptyList(), reportRows())
	}

	private fun read(token: String): Pair<Int, String> = http(port, "GET", "/entitlements", token = token).let { it.statusCode() to it.body() }

	@Test
	fun `샀음 읽기는 받기와 같은 판정이다 - 시작 전 모두 · 수령자는 true, 안 산 사람은 false, 묻지 못하면 503`() {
		val token = token()
		val owner = ownerId()

		clock.now = PURCHASE_STARTS.minusSeconds(1)
		assertEquals(200 to """{"detail":true}""", read(token), "결제 시작 전에는 모두 가진 것과 같다")
		assertTrue(revenueCat.asked.isEmpty())

		clock.now = PURCHASE_STARTS.plusSeconds(1)
		revenueCat.fails()
		assertEquals(503 to PURCHASE_CHECK_FAILED, read(token))

		revenueCat.answer = { false }
		assertEquals(200 to """{"detail":false}""", read(token))

		jdbc.update("insert into entitlements (account_id, source) values (?, 'LEGACY')", owner)
		assertEquals(200 to """{"detail":true}""", read(token))
		assertEquals(2, revenueCat.asked.size, "표에 있으면 묻지 않는다")
		assertEquals(401, http(port, "GET", "/entitlements").statusCode(), "세션이 있어야 한다")
	}

	@Test
	fun `샀음 읽기에서 RevenueCat 에 있으면 STORE 로 적는다`() {
		val token = token()
		revenueCat.answer = { true }

		assertEquals(200 to """{"detail":true}""", read(token))
		assertEquals(listOf("STORE"), entitlementRows())
	}

	private fun oldReportRows(): Int = jdbc.queryForObject("select count(*) from reports", Int::class.java)!!

	@Test
	fun `옛 심층 리포트 받기도 같은 판정이다 - 안 산 사람 402 · 1점1점0 미만 앱 426 · 산 사람 202`() {
		val token = token()

		assertEquals(402 to PURCHASE_REQUIRED, post("/reports", token).let { it.statusCode() to it.body() })
		assertEquals(426 to UPDATE_REQUIRED, postFromApp("/reports", token, "1.0.0").let { it.statusCode() to it.body() })
		assertEquals(0, oldReportRows())
		assertEquals(0, requestedAudits())

		revenueCat.answer = { true }
		clock.now = clock.now.plusSeconds(61) // 담아 둔 "없음" 이 지난 뒤
		assertEquals(202, post("/reports", token).statusCode())
		assertEquals(listOf("STORE"), entitlementRows())
		assertEquals(1, oldReportRows())
	}

	@Test
	fun `옛 심층 리포트도 결제 시작 전에는 묻지 않고 받으며 쓰는 중이면 시작 뒤에도 묻지 않는다`() {
		clock.now = PURCHASE_STARTS.minusSeconds(1)
		val token = token()
		generator.gate = CountDownLatch(1)

		assertEquals(202 to """{"status":"WRITING"}""", postFromApp("/reports", token, "1.0.0").let { it.statusCode() to it.body() })

		clock.now = PURCHASE_STARTS.plusSeconds(1)
		revenueCat.fails()
		assertEquals(202 to """{"status":"WRITING"}""", postFromApp("/reports", token, "1.0.0").let { it.statusCode() to it.body() })
		assertTrue(revenueCat.asked.isEmpty())
	}

	@Test
	fun `확인은 계정마다 분당 6번까지이고 넘으면 429 이며 RevenueCat 을 묻지 않는다 - 다른 계정은 따로 센다`() {
		val token = token()

		repeat(6) { assertEquals(200, confirm(token).statusCode(), "${it + 1}번째") }
		val limited = confirm(token)

		assertEquals(429 to """{"error":"RATE_LIMITED"}""", limited.statusCode() to limited.body())
		assertEquals(6, revenueCat.asked.size)

		val other = "entitlement-confirm-other-session"
		val otherId = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'confirm-other')", otherId)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", com.byeolmyeong.api.synastry.sha256(other), otherId)
		assertEquals(200, confirm(other).statusCode())
	}
}
