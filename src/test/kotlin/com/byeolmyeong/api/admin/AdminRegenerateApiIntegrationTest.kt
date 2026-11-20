package com.byeolmyeong.api.admin

import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.http
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
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val KNOWN = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""

/** 모델을 부르지 않는다 — 부르면 곧바로 실패해 새 생성이 FAILED 로 끝난다(이 테스트는 "다시 시작했는지"만 본다). */
class FailingGenerator : TextGenerator {
	override fun generate(request: GenerationRequest): JsonNode? = throw GenerationException("TEST")
}

/** 관리자의 재생성(결정 043 의 1): 실패한 나 탭 풀이 · 시기 풀이만 그 사람의 지금 차트로 다시 시작한다. */
@AdminIntegrationTest
@Import(AdminRegenerateApiIntegrationTest.FakeConfig::class)
class AdminRegenerateApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun failingGenerator(): TextGenerator = FailingGenerator()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var reportExecutor: ThreadPoolTaskExecutor

	private lateinit var token: String

	/** 뒤에서 도는 생성이 모두 끝날 때까지 기다린다(다음 테스트가 지운 행을 건드리지 않게). */
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
		jdbc.update("delete from audit_logs")
		token = adminToken(port)
	}

	@AfterEach
	fun drain() = settle()

	/** 출생 정보가 있는 계정 하나와, [kind] 의 옛 생성 한 줄([status], 다른 차트). */
	private fun userWithReport(kind: String?, status: String): UUID {
		val (id, session) = userAccount(jdbc, "user-${UUID.randomUUID()}")
		assertEquals(200, http(port, "PUT", "/profile", KNOWN, session).statusCode(), "출생 정보 저장")
		if (kind != null) {
			jdbc.update(
				"insert into detail_reports (id, owner_id, kind, chart_hash, status, body, started_at) values (?, ?, ?, 'old-chart', ?, ?::jsonb, now() - interval '1 hour')",
				UUID.randomUUID(), id, kind, status, if (status == "DONE") "{}" else null,
			)
		}
		return id
	}

	private fun regenerate(id: Any, body: String): HttpResponse<String> = http(port, "POST", "/admin/users/$id/regenerate", body, token)

	private fun rows(id: UUID, kind: String): Int =
		jdbc.queryForObject("select count(*) from detail_reports where owner_id = ? and kind = ?", Int::class.java, id, kind)!!

	private fun assertError(status: Int, code: String, response: HttpResponse<String>, what: String = "") {
		assertEquals(status, response.statusCode(), "$what ${response.body()}")
		assertEquals("""{"error":"$code"}""", response.body(), what)
	}

	@Test
	fun `실패한 나 탭 풀이는 지금 차트로 다시 시작하고 감사 기록 GENERATION_RETRIED 를 남긴다`() {
		val id = userWithReport("ME", "FAILED")

		val response = regenerate(id, """{"kind":"REPORT_ME"}""")

		assertEquals(202, response.statusCode(), response.body())
		assertEquals("", response.body())
		assertEquals(2, rows(id, "ME"), "지금 차트의 새 생성 한 줄이 생겼다")
		val audit = jdbc.queryForMap("select actor_type, target_type, target_id, detail::text as detail from audit_logs where action = 'GENERATION_RETRIED'")
		assertEquals("ADMIN", audit["actor_type"])
		assertEquals("USER", audit["target_type"])
		assertEquals(id.toString(), audit["target_id"])
		assertTrue(audit["detail"].toString().contains("REPORT_ME"), audit["detail"].toString())
	}

	@Test
	fun `쓰는 중인데 2분 넘게 멈춘 것은 실패로 보고 다시 시작한다`() {
		val id = userWithReport("ME", "WRITING")
		jdbc.update("update detail_reports set active_at = now() - interval '3 minutes' where owner_id = ?", id)

		assertEquals(202, regenerate(id, """{"kind":"REPORT_ME"}""").statusCode())
		assertEquals(2, rows(id, "ME"))
	}

	@Test
	fun `실패한 시기 풀이도 다시 시작한다`() {
		val id = userWithReport("TIMING", "FAILED")

		assertEquals(202, regenerate(id, """{"kind":"REPORT_TIMING"}""").statusCode())
		assertEquals(2, rows(id, "TIMING"))
	}

	@Test
	fun `가장 최근 생성이 실패가 아니면 409 NOT_FAILED 이고 아무것도 시작하지 않는다`() {
		for (status in listOf("DONE", "WRITING")) {
			val id = userWithReport("ME", status)
			assertError(409, "NOT_FAILED", regenerate(id, """{"kind":"REPORT_ME"}"""), status)
			assertEquals(1, rows(id, "ME"), status)
		}
		val none = userWithReport(null, "")
		assertError(409, "NOT_FAILED", regenerate(none, """{"kind":"REPORT_TIMING"}"""), "만든 적 없음")
		// 별명은 실패가 저장되지 않는다(요약 해석은 요청 안에서 만든다) — 늘 409
		assertError(409, "NOT_FAILED", regenerate(none, """{"kind":"NICKNAME"}"""), "별명")
		assertEquals(0L, jdbc.queryForObject("select count(*) from audit_logs where action = 'GENERATION_RETRIED'", Long::class.java))
	}

	@Test
	fun `종류가 틀리면 400 INVALID_KIND, 본문이 객체가 아니면 400 INVALID_REQUEST, 없는 사용자는 404`() {
		val id = userWithReport("ME", "FAILED")
		assertError(400, "INVALID_KIND", regenerate(id, """{"kind":"TODAY_SKY"}"""))
		assertError(400, "INVALID_KIND", regenerate(id, """{}"""))
		assertError(400, "INVALID_REQUEST", regenerate(id, """["REPORT_ME"]"""))
		assertError(404, "NOT_FOUND", regenerate(UUID.randomUUID(), """{"kind":"REPORT_ME"}"""))
		assertError(404, "NOT_FOUND", regenerate("not-a-uuid", """{"kind":"REPORT_ME"}"""))
		assertEquals(1, rows(id, "ME"), "거절된 요청은 아무것도 시작하지 않는다")
	}
}
