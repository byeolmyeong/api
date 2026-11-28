package com.byeolmyeong.api.notice

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.AdminIntegrationTest
import com.byeolmyeong.api.admin.adminToken
import com.byeolmyeong.api.admin.json
import com.byeolmyeong.api.admin.mapper
import com.byeolmyeong.api.admin.resetAdminData
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 공지사항의 공개 조회 `GET /notices`(결정 055 의 2). 글은 표에 직접 넣는다(쓰는 길은 관리자 API — AdminNoticeApiIntegrationTest). */
@AdminIntegrationTest
class NoticeApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
	}

	private fun notice(title: String, category: String = "NOTICE", publishedAt: String? = "2026-12-01T03:00:00Z", body: String = "$title 의 본문"): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into notices (id, category, title, body, status, published_at) values (?, ?, ?, ?, ?, ?)",
			id, category, title, body, if (publishedAt == null) "DRAFT" else "PUBLISHED", publishedAt?.let(OffsetDateTime::parse),
		)
		return id
	}

	@Test
	fun `공지가 없으면 빈 배열이고 5분 캐시해도 된다`() {
		val response = http(port, "GET", "/notices")
		assertEquals(200, response.statusCode())
		assertEquals("[]", response.body())
		assertEquals("public, max-age=300", response.headers().firstValue("Cache-Control").orElse(null))
	}

	@Test
	fun `공개한 것만 공개 시각이 새것부터 다섯 키로 준다`() {
		notice("옛 공지", publishedAt = "2026-12-01T03:00:00Z")
		val update = notice("새 기능", category = "UPDATE", publishedAt = "2026-12-03T09:30:15Z", body = "첫 줄\n\n셋째 줄")
		notice("임시 저장", publishedAt = null)
		notice("가운데 공지", publishedAt = "2026-12-02T00:00:00Z")

		val list = mapper.readTree(http(port, "GET", "/notices").body()).toList()

		assertEquals(listOf("새 기능", "가운데 공지", "옛 공지"), list.map { it["title"].asString() })
		for (item in list) assertEquals(setOf("id", "category", "title", "body", "publishedAt"), item.propertyNames().toSet())
		assertEquals(update.toString(), list[0]["id"].asString())
		assertEquals("UPDATE", list[0]["category"].asString())
		assertEquals("첫 줄\n\n셋째 줄", list[0]["body"].asString(), "본문은 줄바꿈까지 그대로")
		assertEquals("2026-12-03T09:30:15Z", list[0]["publishedAt"].asString(), "UTC 초 단위")
		assertEquals("NOTICE", list[1]["category"].asString())
	}

	@Test
	fun `세션을 보지 않는다`() {
		notice("하나")
		assertEquals(200, http(port, "GET", "/notices", token = devToken(port)).statusCode())
		assertEquals(200, http(port, "GET", "/notices", token = "no-such-token").statusCode())
		assertEquals(200, http(port, "HEAD", "/notices").statusCode())
	}

	@Test
	fun `공개 경로는 GET 뿐이고 그 밖의 notices 경로는 열리지 않는다`() {
		val id = notice("하나")
		val admin = adminToken(port)
		val calls = listOf(
			Triple("POST", "/notices", json(mapOf("category" to "NOTICE", "title" to "몰래", "body" to "몰래 넣은 글"))),
			Triple("PUT", "/notices/$id", json(mapOf("category" to "NOTICE", "title" to "몰래", "body" to "몰래 고친 글"))),
			Triple("DELETE", "/notices/$id", null),
			Triple("GET", "/notices/$id", null),
			Triple("POST", "/notices/$id/publish", json(mapOf("notify" to true))),
		)
		for ((method, path, body) in calls) {
			for (auth in listOf(null, admin)) {
				val response = http(port, method, path, body, auth)
				assertTrue(response.statusCode() in listOf(401, 404, 405), "$method $path → ${response.statusCode()}")
			}
		}
		assertEquals(1, jdbc.queryForObject("select count(*) from notices", Int::class.java))
	}

	@Test
	fun `표가 업데이트 갈래의 알림과 공개 상태 · 공개 시각의 어긋남을 받지 않는다`() {
		fun insert(category: String, status: String, publishedAt: OffsetDateTime?, notify: Boolean) = jdbc.update(
			"insert into notices (id, category, title, body, status, published_at, notify) values (?, ?, '제목', '본문', ?, ?, ?)",
			UUID.randomUUID(), category, status, publishedAt, notify,
		)
		val now = OffsetDateTime.parse("2026-12-01T00:00:00Z")
		assertThrows<DataIntegrityViolationException> { insert("UPDATE", "DRAFT", null, true) }
		assertThrows<DataIntegrityViolationException> { insert("NOTICE", "PUBLISHED", null, false) }
		assertThrows<DataIntegrityViolationException> { insert("NOTICE", "DRAFT", now, false) }
		assertThrows<DataIntegrityViolationException> { insert("EVENT", "DRAFT", null, false) }
		insert("NOTICE", "DRAFT", null, true)
		insert("UPDATE", "PUBLISHED", now, false)
		assertEquals(2, jdbc.queryForObject("select count(*) from notices", Int::class.java))
	}
}

/** 공개 조회의 주소별 제한(결정 055 의 2 — FAQ 와 같다). 한도를 3 으로 낮춰 본다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["byeolmyeong.rate-limit.notices-per-minute=3"])
class NoticeRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `공개 목록은 한 주소에서 분당 한도를 넘으면 429 다`() {
		repeat(3) { assertEquals(200, http(port, "GET", "/notices").statusCode(), "${it + 1}번째") }

		val limited = http(port, "GET", "/notices")
		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
		assertEquals(200, http(port, "GET", "/faqs").statusCode(), "다른 경로는 막지 않는다")
	}
}
