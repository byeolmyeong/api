package com.byeolmyeong.api.admin

import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 처음 답했다는 이벤트를 모은다(알림함 · 푸시 대신). */
class InquiryAnsweredEvents {
	val events = CopyOnWriteArrayList<InquiryAnswered>()

	@EventListener
	fun on(event: InquiryAnswered) {
		events += event
	}
}

/** 관리자의 문의 답 · 임시 저장(결정 039 의 4~6)과 사용자가 보는 답. */
@AdminIntegrationTest
class AdminInquiryAnswerApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class Events {
		@Bean
		fun inquiryAnsweredEvents() = InquiryAnsweredEvents()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var answered: InquiryAnsweredEvents

	private lateinit var token: String
	private lateinit var owner: UUID
	private lateinit var userToken: String

	@BeforeEach
	fun clean() {
		resetAdminData(jdbc)
		token = adminToken(port)
		userAccount(jdbc, "user-a").let { (id, session) -> owner = id; userToken = session }
		answered.events.clear()
	}

	private fun seed(status: String = "NEW"): UUID {
		val id = UUID.randomUUID()
		jdbc.update("insert into inquiries (id, owner_id, category, body, status) values (?, ?, 'BUG', '앱이 멈춰요 확인해 주세요', ?)", id, owner, status)
		return id
	}

	private fun patch(id: Any, body: Any?, token: String = this.token): HttpResponse<String> =
		http(port, "PATCH", "/admin/inquiries/$id", if (body is String?) body else json(body), token)

	private fun userView(id: UUID) = http(port, "GET", "/inquiries/$id", token = userToken).json()

	@Test
	fun `처음 답하면 ANSWERED · 답한 관리자와 때 · 이벤트 하나, 사용자는 답을 본다`() {
		val id = seed("IN_PROGRESS")
		val rootId = http(port, "GET", "/admin/me", token = token).json()["id"].asString()
		val before = Instant.now().minusSeconds(2)

		val response = patch(id, mapOf("answer" to "  확인했어요.\n다음 판에서 고칠게요.  "))
		assertEquals(200, response.statusCode())
		val json = response.json()
		assertEquals("ANSWERED", json["status"].asString())
		assertEquals("확인했어요.\n다음 판에서 고칠게요.", json["answer"].asString(), "앞뒤 공백을 뗀다")
		assertTrue(Instant.parse(json["answeredAt"].asString()).isAfter(before))
		assertEquals(setOf("id", "name"), json["answeredBy"].propertyNames().toSet())
		assertEquals(listOf(rootId, ROOT_ID), listOf(json["answeredBy"]["id"].asString(), json["answeredBy"]["name"].asString()))
		assertEquals(rootId, json["handledBy"]["id"].asString(), "손댄 관리자도 적는다")

		assertEquals(listOf(InquiryAnswered(owner, id)), answered.events.toList(), "처음 답할 때 이벤트 하나")

		val mine = userView(id)
		assertEquals("ANSWERED", mine["status"].asString())
		assertEquals("확인했어요.\n다음 판에서 고칠게요.", mine["answer"]["body"].asString())
		assertEquals(json["answeredAt"].asString(), mine["answer"]["answeredAt"].asString())
	}

	@Test
	fun `답을 고치면 글만 바뀌고 이벤트는 다시 나지 않는다`() {
		val id = seed()
		val first = patch(id, mapOf("answer" to "처음 답")).json()
		createAdmin(port, token, "ops-kim", "ops-kim-password", "운영 김")
		val theirs = adminToken(port, "ops-kim", "ops-kim-password")

		val edited = patch(id, mapOf("answer" to "고친 답"), theirs)
		assertEquals(200, edited.statusCode())
		val json = edited.json()
		assertEquals("고친 답", json["answer"].asString())
		assertEquals(first["answeredAt"], json["answeredAt"], "처음 답한 때는 그대로")
		assertEquals(ROOT_ID, json["answeredBy"]["name"].asString(), "처음 답한 관리자도 그대로")
		assertEquals("운영 김", json["handledBy"]["name"].asString(), "마지막으로 손댄 관리자는 바뀐다")
		assertEquals(1, answered.events.size, "고칠 때는 이벤트가 없다")
		assertEquals("고친 답", userView(id)["answer"]["body"].asString())

		// 답한 뒤 처리 상태를 바꿔도 답은 남고 사용자에게는 ANSWERED 그대로
		val done = patch(id, """{"status":"DONE"}""").json()
		assertEquals(listOf("DONE", "고친 답"), listOf(done["status"].asString(), done["answer"].asString()))
		assertEquals("ANSWERED", userView(id)["status"].asString())
		// 다시 저장하면 ANSWERED 로 돌아오되 이벤트는 없다
		assertEquals("ANSWERED", patch(id, mapOf("answer" to "고친 답")).json()["status"].asString())
		assertEquals(1, answered.events.size)
	}

	@Test
	fun `여러 관리자가 동시에 처음 답해도 이벤트는 하나다`() {
		val id = seed()
		val start = CountDownLatch(1)
		val calls = (1..6).map { n ->
			CompletableFuture.supplyAsync {
				start.await()
				patch(id, mapOf("answer" to "동시에 보낸 답 $n")).statusCode()
			}
		}
		start.countDown()
		assertEquals(List(6) { 200 }, calls.map { it.get() })
		assertEquals(listOf(InquiryAnswered(owner, id)), answered.events.toList())
	}

	@Test
	fun `임시 저장은 상태 · 답 · 이벤트를 바꾸지 않고 답을 저장하면 비워진다`() {
		val id = seed("IN_PROGRESS")
		val saved = patch(id, mapOf("answerDraft" to "  쓰는 중인 답  "))
		assertEquals(200, saved.statusCode())
		val json = saved.json()
		assertEquals(listOf("IN_PROGRESS", "쓰는 중인 답"), listOf(json["status"].asString(), json["answerDraft"].asString()))
		assertTrue(json["answer"].isNull && json["answeredAt"].isNull && json["answeredBy"].isNull)
		assertTrue(answered.events.isEmpty(), "임시 저장은 알리지 않는다")
		val mine = userView(id)
		assertEquals("OPEN", mine["status"].asString())
		assertFalse(mine.toString().contains("쓰는 중인 답"), "임시 저장은 앱에 보이지 않는다")

		for (clear in listOf("""{"answerDraft":null}""", """{"answerDraft":""}""", """{"answerDraft":"   "}""")) {
			patch(id, mapOf("answerDraft" to "지울 임시 답"))
			assertTrue(patch(id, clear).json()["answerDraft"].isNull, clear)
		}

		patch(id, mapOf("answerDraft" to "다시 쓰는 답"))
		val answeredJson = patch(id, mapOf("answer" to "보낸 답", "memo" to "메모도 함께")).json()
		assertTrue(answeredJson["answerDraft"].isNull, "답을 저장하면 임시 저장을 비운다")
		assertEquals(listOf("보낸 답", "메모도 함께", "ANSWERED"), listOf("answer", "memo", "status").map { answeredJson[it].asString() })
		assertEquals(1, answered.events.size)

		// 답한 뒤의 임시 저장(고칠 답)도 알리지 않는다
		val draftAfter = patch(id, mapOf("answerDraft" to "고칠 답 초안")).json()
		assertEquals(listOf("보낸 답", "고칠 답 초안", "ANSWERED"), listOf("answer", "answerDraft", "status").map { draftAfter[it].asString() })
		assertEquals(1, answered.events.size)
	}

	@Test
	fun `틀린 답 · 임시 저장 · 섞인 요청은 400 이고 아무것도 바꾸지 않는다`() {
		val id = seed()
		fun assertRejected(code: String, body: Any?) {
			val response = patch(id, body)
			assertEquals(400, response.statusCode(), "본문: ${body.toString().take(60)}")
			assertEquals("""{"error":"$code"}""", response.body())
		}
		for (answer in listOf(null, "", "   ", "가".repeat(2001), "😀".repeat(2001), "답 \u0000 끝")) {
			assertRejected("INVALID_ANSWER", mapOf("answer" to answer))
		}
		for (draft in listOf("가".repeat(2001), "초안 \u0000")) assertRejected("INVALID_ANSWER", mapOf("answerDraft" to draft))
		for (body in listOf("""{"answer":1}""", """{"answer":["답"]}""", """{"answerDraft":true}""", """{"answerDraft":{"v":"초안"}}""")) {
			assertRejected("INVALID_REQUEST", body)
		}
		assertRejected("INVALID_REQUEST", mapOf("answer" to "답", "status" to "DONE"))
		assertRejected("INVALID_REQUEST", mapOf("answer" to "답", "status" to "ANSWERED"))
		assertRejected("INVALID_REQUEST", mapOf("answer" to "답", "answerDraft" to "초안"))
		assertRejected("INVALID_STATUS", mapOf("status" to "ANSWERED"))

		val row = jdbc.queryForMap("select status, answer_body, answered_at, answered_by, answer_draft, handled_by from inquiries where id = ?", id)
		assertEquals(listOf("NEW", null, null, null, null, null), row.values.toList())
		assertTrue(answered.events.isEmpty())

		assertEquals(200, patch(id, mapOf("answer" to "가".repeat(2000))).statusCode(), "2000자까지")
		assertEquals(200, patch(id, mapOf("answer" to "😀".repeat(2000))).statusCode(), "코드 포인트로 센다")
		assertEquals(200, patch(id, mapOf("answerDraft" to "가".repeat(2000))).statusCode())

		for (path in listOf(UUID.randomUUID().toString(), "not-a-uuid")) {
			val missing = patch(path, mapOf("answer" to "답"))
			assertEquals(404, missing.statusCode())
			assertEquals("""{"error":"NOT_FOUND"}""", missing.body())
		}
		assertEquals(1, answered.events.size, "없는 문의에는 이벤트가 없다")
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `답과 임시 저장의 글은 로그에 없다`(output: CapturedOutput) {
		val id = seed()
		assertEquals(200, patch(id, mapOf("answerDraft" to "남기면안되는임시답")).statusCode())
		assertEquals(200, patch(id, mapOf("answer" to "남기면안되는답글")).statusCode())
		assertEquals(400, patch(id, mapOf("answer" to "남기면안되는답글".repeat(300))).statusCode())
		assertEquals(400, patch(id, """{"answer":"남기면안되는답글"""").statusCode())
		http(port, "GET", "/inquiries", token = userToken)

		for (secret in listOf("남기면안되는")) assertFalse(output.all.contains(secret), "로그에 '$secret' 이 있다")
		assertTrue(output.all.contains("event=admin_inquiry_answered first=true"))
	}
}
