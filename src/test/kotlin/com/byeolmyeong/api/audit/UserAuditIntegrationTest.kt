package com.byeolmyeong.api.audit

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.direction.DirectionPrompt
import com.byeolmyeong.api.direction.numberless
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.http
import com.byeolmyeong.api.reading.ReadingPrompt
import com.byeolmyeong.api.reading.validDraft
import com.byeolmyeong.api.report.timingJson
import com.byeolmyeong.api.report.validArea
import com.byeolmyeong.api.synastry.areaJson
import com.byeolmyeong.api.synastry.summaryJson
import com.byeolmyeong.api.web.ownerRef
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAPO = 1841277
private const val MY_DATE = "1990-03-15"
private const val MY_TIME = "14:30"
private const val ME = """{"date":"$MY_DATE","time":"$MY_TIME","placeId":$MAPO}"""
private const val OTHER_ME = """{"date":"1991-04-16","time":null,"placeId":null}"""
private const val PARTNER_NAME = "지우비밀이름"
private const val PARTNER_DATE = "1988-07-21"
private const val PARTNER = """{"date":"$PARTNER_DATE","time":null}"""
private const val INQUIRY_BODY = "문의비밀글-앱이 자꾸 꺼져요 도와주세요"
private const val REPLY_EMAIL = "user-secret@example.com"

/** 모든 글의 요청에 검증을 통과하는 답을 주는 가짜. [fail] 이면 쓸 수 없는 답(null)만 준다 — 세 번 뒤 502 가 된다. */
class FakeEveryGenerator : TextGenerator {
	private val mapper = JsonMapper()

	@Volatile
	var fail = false

	override fun generate(request: GenerationRequest): JsonNode? {
		if (fail) return null
		val purpose = request.purpose
		val schema: JsonNode = mapper.valueToTree(request.schema)
		return when {
			purpose == "reading_summary" -> {
				val vocabulary = schema["properties"]["sections"]["items"]["properties"]["basis"]["items"]["enum"].values().map { it.asString() }
				mapper.valueToTree(validDraft(ReadingPrompt(request.instructions, request.userText, vocabulary), 1))
			}
			purpose.startsWith("direction_") -> {
				val vocabulary = schema["properties"]["items"]["items"]["properties"]["basis"]["items"]["enum"].values().map { it.asString() }
				mapper.valueToTree(numberless(DirectionPrompt(purpose, request.instructions, request.userText, vocabulary)))
			}
			purpose == "synastry_summary" -> summaryJson()
			purpose.startsWith("synastry_report_") -> areaJson(request, 1)
			purpose == "report_timing" -> timingJson(request)
			else -> validArea(request, 1)
		}
	}
}

/** 사용자의 주요 행동이 감사 기록에 한 줄씩 남는다(결정 015). 실제 경로를 부르고 `audit_logs` 를 본다. */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class, UserAuditIntegrationTest.FakeConfig::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.rate-limit.synastry-per-minute=100000", "byeolmyeong.rate-limit.inquiries-per-hour=100000"],
)
class UserAuditIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class FakeConfig {
		@Bean
		@Primary
		fun fakeEveryGenerator() = FakeEveryGenerator()
	}

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var generator: FakeEveryGenerator

	private val mapper = JsonMapper()
	private lateinit var token: String
	private lateinit var me: UUID

	@BeforeEach
	fun setUp() {
		awaitReports()
		jdbc.update("delete from accounts")
		generator.fail = false
		token = devToken(port)
		me = jdbc.queryForObject("select id from accounts", UUID::class.java)!!
		jdbc.update("delete from audit_logs")
	}

	// 뒤에서 쓰는 리포트가 남아 있으면 끝날 때까지 기다린다(다음 테스트의 가짜 설정과 섞이지 않게).
	private fun awaitReports() {
		val deadline = System.currentTimeMillis() + 30_000
		while (System.currentTimeMillis() < deadline) {
			val writing = jdbc.queryForObject("select (select count(*) from reports where status = 'WRITING') + (select count(*) from synastry_reports where status = 'WRITING')", Int::class.java)!!
			if (writing == 0) return
			Thread.sleep(50)
		}
	}

	private fun rows(): List<Map<String, Any?>> = jdbc.queryForList(
		"select actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail::text as detail from audit_logs order by occurred_at",
	)

	/** 행이 하나뿐이고 [action] 이며 행위자가 나다. */
	private fun single(action: String, outcome: String = "SUCCESS"): Map<String, Any?> {
		val row = rows().single()
		assertEquals(action, row["action"])
		assertEquals(outcome, row["outcome"])
		assertEquals(listOf("USER", me.toString(), ownerRef(me)), listOf(row["actor_type"], row["actor_id"], row["actor_label"]))
		return row
	}

	private fun clear() = jdbc.update("delete from audit_logs")

	private fun saveProfile(body: String = ME) {
		assertEquals(200, http(port, "PUT", "/profile", body, token).statusCode())
		clear()
	}

	private fun post(path: String, body: String? = null): HttpResponse<String> = http(port, "POST", path, body, token)

	private fun createSynastry(relation: String = "LOVE"): String {
		val response = post("/synastry/direct", """{"name":"$PARTNER_NAME","relation":"$relation","date":"$PARTNER_DATE","time":null}""")
		assertEquals(200, response.statusCode())
		return mapper.readTree(response.body())["id"].asString()
	}

	@Test
	fun `로그인이 남는다`() {
		devToken(port)
		val row = single("USER_LOGIN")
		assertEquals(listOf(null, null, null), listOf(row["target_type"], row["target_id"], row["detail"]))
	}

	@Test
	fun `출생 정보 저장은 처음인지 고친 것인지만 남는다`() {
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
		assertEquals("""{"change": "CREATED"}""", single("PROFILE_SAVED")["detail"])

		clear()
		assertEquals(200, http(port, "PUT", "/profile", OTHER_ME, token).statusCode())
		assertEquals("""{"change": "CHANGED"}""", single("PROFILE_SAVED")["detail"])

		clear()
		assertEquals(400, http(port, "PUT", "/profile", """{"date":"1990-13-45","time":null}""", token).statusCode())
		assertEquals(200, http(port, "GET", "/profile", token = token).statusCode())
		assertEquals(0, rows().size, "틀린 요청과 읽기는 남지 않는다")
	}

	@Test
	fun `요약 해석은 새로 만들 때만 남고 저장된 글을 다시 받는 것은 남지 않는다`() {
		assertEquals(404, post("/readings").statusCode())
		assertEquals(0, rows().size, "프로필이 없어 만들지 않은 요청은 남지 않는다")

		saveProfile()
		assertEquals(200, post("/readings").statusCode())
		val row = single("READING_GENERATED")
		assertEquals(listOf(null, null, null), listOf(row["target_type"], row["target_id"], row["detail"]))

		assertEquals(200, post("/readings").statusCode())
		assertEquals(200, post("/readings").statusCode())
		assertEquals(1, rows().size)
	}

	@Test
	fun `요약 해석의 생성 실패는 FAILURE 로 남는다`() {
		saveProfile()
		generator.fail = true
		assertEquals(502, post("/readings").statusCode())
		single("READING_GENERATED", "FAILURE")
	}

	@Test
	fun `리포트는 만들기 시작한 요청만 남는다`() {
		saveProfile()
		assertEquals(202, post("/reports").statusCode())
		single("REPORT_REQUESTED")

		// 쓰는 중이거나 완성된 리포트에 다시 요청해도 시작하지 않으므로 남지 않는다.
		assertEquals(202, post("/reports").statusCode())
		awaitReports()
		assertEquals("DONE", mapper.readTree(http(port, "GET", "/reports/me", token = token).body())["status"].asString())
		assertEquals(202, post("/reports").statusCode())
		assertEquals(1, rows().size)
	}

	@Test
	fun `궁합 만들기는 관계 유형과 함께 남고 읽기는 남지 않는다`() {
		saveProfile()
		val id = createSynastry("FRIEND")
		val row = single("SYNASTRY_CREATED")
		assertEquals(listOf("SYNASTRY", id, """{"relation": "FRIEND"}"""), listOf(row["target_type"], row["target_id"], row["detail"]))

		assertEquals(200, http(port, "GET", "/synastry", token = token).statusCode())
		assertEquals(200, http(port, "GET", "/synastry/$id", token = token).statusCode())
		assertEquals(200, http(port, "GET", "/synastry/ranking?relation=FRIEND", token = token).statusCode())
		assertEquals(1, rows().size)
	}

	@Test
	fun `궁합 글의 생성 실패는 FAILURE 로 남는다`() {
		saveProfile()
		generator.fail = true
		assertEquals(502, post("/synastry/direct", """{"name":"$PARTNER_NAME","relation":"WORK","date":"$PARTNER_DATE","time":null}""").statusCode())
		val row = single("SYNASTRY_CREATED", "FAILURE")
		assertEquals(listOf(null, null, """{"relation": "WORK"}"""), listOf(row["target_type"], row["target_id"], row["detail"]))
	}

	@Test
	fun `궁합 리포트는 만들기 시작한 요청만 남는다`() {
		saveProfile()
		val id = createSynastry()
		clear()

		assertEquals(202, post("/synastry/$id/report", PARTNER).statusCode())
		val row = single("SYNASTRY_REPORT_REQUESTED")
		assertEquals(listOf("SYNASTRY", id, null), listOf(row["target_type"], row["target_id"], row["detail"]))

		awaitReports()
		assertEquals(202, post("/synastry/$id/report", PARTNER).statusCode())
		assertEquals(200, http(port, "GET", "/synastry/$id/report", token = token).statusCode())
		assertEquals(1, rows().size)
	}

	@Test
	fun `방향성 제시는 주제와 함께 새로 만들 때만 남는다`() {
		saveProfile()
		val synastry = createSynastry()
		clear()

		assertEquals(200, post("/directions", """{"topic":"LOVE"}""").statusCode())
		var row = single("DIRECTION_GENERATED")
		assertEquals(listOf(null, null, """{"topic": "LOVE"}"""), listOf(row["target_type"], row["target_id"], row["detail"]))
		assertEquals(200, post("/directions", """{"topic":"LOVE"}""").statusCode())
		assertEquals(1, rows().size)

		clear()
		assertEquals(200, post("/directions", """{"topic":"FUTURE"}""").statusCode())
		assertEquals("""{"topic": "FUTURE"}""", single("DIRECTION_GENERATED")["detail"])
		assertEquals(200, post("/directions", """{"topic":"FUTURE"}""").statusCode())
		assertEquals(1, rows().size)

		clear()
		val body = """{"topic":"SYNASTRY","synastryId":"$synastry","date":"$PARTNER_DATE","time":null}"""
		assertEquals(200, post("/directions", body).statusCode())
		row = single("DIRECTION_GENERATED")
		assertEquals(listOf("SYNASTRY", synastry, """{"topic": "SYNASTRY"}"""), listOf(row["target_type"], row["target_id"], row["detail"]))
		assertEquals(200, post("/directions", body).statusCode())
		assertEquals(1, rows().size)

		clear()
		assertEquals(400, post("/directions", """{"topic":"MONEY"}""").statusCode())
		assertEquals(0, rows().size)
	}

	@Test
	fun `방향성 제시의 생성 실패는 FAILURE 로 남는다`() {
		saveProfile()
		generator.fail = true
		assertEquals(502, post("/directions", """{"topic":"FUTURE"}""").statusCode())
		assertEquals("""{"topic": "FUTURE"}""", single("DIRECTION_GENERATED", "FAILURE")["detail"])
	}

	@Test
	fun `공유는 새로 만든 것과 지운 것이 종류만 남는다`() {
		saveProfile()
		assertEquals(200, post("/readings").statusCode())
		clear()

		val response = post("/shares", """{"kind":"READING"}""")
		assertEquals(200, response.statusCode())
		val shareId = mapper.readTree(response.body())["shareId"].asString()
		var row = single("SHARE_CREATED")
		assertEquals(listOf(null, null, """{"kind": "READING"}"""), listOf(row["target_type"], row["target_id"], row["detail"]), "공유 식별자는 남기지 않는다")

		// 같은 내용을 다시 공유하면 같은 식별자가 오고 새로 만든 것이 없다.
		assertEquals(shareId, mapper.readTree(post("/shares", """{"kind":"READING"}""").body())["shareId"].asString())
		assertEquals(200, http(port, "GET", "/shared/$shareId").statusCode())
		assertEquals(404, post("/shares", """{"kind":"NEXT_TURN"}""").statusCode())
		assertEquals(1, rows().size)

		clear()
		assertEquals(204, http(port, "DELETE", "/shares/$shareId", token = token).statusCode())
		row = single("SHARE_DELETED")
		assertEquals(listOf(null, null, null), listOf(row["target_type"], row["target_id"], row["detail"]))
		assertEquals(404, http(port, "DELETE", "/shares/$shareId", token = token).statusCode())
		assertEquals(1, rows().size)
	}

	@Test
	fun `문의 보내기는 종류와 문의 id 만 남는다`() {
		val response = post("/inquiries", mapper.writeValueAsString(mapOf("category" to "BUG", "body" to INQUIRY_BODY, "replyEmail" to REPLY_EMAIL)))
		assertEquals(201, response.statusCode())
		val row = single("INQUIRY_SUBMITTED")
		assertEquals(listOf("INQUIRY", mapper.readTree(response.body())["id"].asString(), """{"category": "BUG"}"""), listOf(row["target_type"], row["target_id"], row["detail"]))

		assertEquals(400, post("/inquiries", """{"category":"BUG","body":"짧다"}""").statusCode())
		assertEquals(1, rows().size)
	}

	@Test
	fun `계정 삭제가 남고 그 계정의 기록은 지워지지 않는다`() {
		saveProfile()
		assertEquals(200, post("/readings").statusCode())
		assertEquals(201, post("/inquiries", mapper.writeValueAsString(mapOf("category" to "OTHER", "body" to INQUIRY_BODY))).statusCode())

		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		assertEquals(0, jdbc.queryForObject("select count(*) from accounts", Int::class.java))
		assertEquals(0, jdbc.queryForObject("select count(*) from inquiries", Int::class.java))
		val rows = rows()
		assertEquals(listOf("READING_GENERATED", "INQUIRY_SUBMITTED", "ACCOUNT_DELETED"), rows.map { it["action"] })
		assertTrue(rows.all { it["actor_id"] == me.toString() && it["actor_label"] == ownerRef(me) })
		assertNull(rows.last()["target_id"])
	}

	@Test
	fun `출생 정보·이름·이메일·글·토큰은 어느 행에도 없다`() {
		assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
		assertEquals(200, post("/readings").statusCode())
		assertEquals(202, post("/reports").statusCode())
		val synastry = createSynastry()
		assertEquals(202, post("/synastry/$synastry/report", PARTNER).statusCode())
		assertEquals(200, post("/directions", """{"topic":"LOVE"}""").statusCode())
		assertEquals(200, post("/directions", """{"topic":"FUTURE"}""").statusCode())
		assertEquals(200, post("/directions", """{"topic":"SYNASTRY","synastryId":"$synastry","date":"$PARTNER_DATE","time":null}""").statusCode())
		val shareId = mapper.readTree(post("/shares", """{"kind":"SCORE","synastryId":"$synastry","myName":"내비밀이름"}""").body())["shareId"].asString()
		assertNotNull(shareId)
		assertEquals(204, http(port, "DELETE", "/shares/$shareId", token = token).statusCode())
		assertEquals(201, post("/inquiries", mapper.writeValueAsString(mapOf("category" to "BUG", "body" to INQUIRY_BODY, "replyEmail" to REPLY_EMAIL))).statusCode())
		generator.fail = true
		assertEquals(200, http(port, "PUT", "/profile", OTHER_ME, token).statusCode())
		assertEquals(502, post("/readings").statusCode())
		awaitReports()
		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		// 일어난 때와 행의 id 는 뺀다(우연히 날짜·시각처럼 보이는 숫자가 들어 있을 수 있다).
		val dump = jdbc.queryForList(
			"select concat_ws('|', actor_type, actor_id, actor_label, action, target_type, target_id, outcome, detail::text) from audit_logs", String::class.java,
		).joinToString("\n")
		assertEquals(14, dump.lines().size)
		val tokenHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
		val secrets = listOf(
			MY_DATE, MY_TIME, MAPO.toString(), "1991-04-16", "04-16", PARTNER_DATE, "07-21", PARTNER_NAME, "지우", "내비밀이름",
			INQUIRY_BODY, "문의비밀글", REPLY_EMAIL, "@", "가짜", token, tokenHash, shareId, "DEV", "dev",
		)
		for (secret in secrets) assertFalse(secret in dump, "감사 기록에 있으면 안 되는 값: ${secret.take(6)}…")
	}

	@Test
	fun `감사 기록을 넣지 못해도 사용자의 요청은 그대로 성공한다`(output: CapturedOutput) {
		jdbc.execute("alter table audit_logs rename to audit_logs_away")
		try {
			assertEquals(200, http(port, "PUT", "/profile", ME, token).statusCode())
			assertEquals(200, post("/readings").statusCode())
			assertEquals(201, post("/inquiries", mapper.writeValueAsString(mapOf("category" to "BUG", "body" to INQUIRY_BODY))).statusCode())
			assertEquals(200, http(port, "POST", "/auth/dev").statusCode())
		} finally {
			jdbc.execute("alter table audit_logs_away rename to audit_logs")
		}

		assertEquals(0, rows().size)
		assertEquals(1, jdbc.queryForObject("select count(*) from profiles", Int::class.java))
		assertEquals(1, jdbc.queryForObject("select count(*) from readings", Int::class.java))
		assertEquals(1, jdbc.queryForObject("select count(*) from inquiries", Int::class.java))
		for (action in listOf("PROFILE_SAVED", "READING_GENERATED", "INQUIRY_SUBMITTED", "USER_LOGIN")) {
			assertTrue("event=audit_record_failed action=$action" in output.out)
		}
	}
}
