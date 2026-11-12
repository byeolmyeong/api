package com.byeolmyeong.api.profile

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
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
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val MAPO = 1841277

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class ProfileApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
	}

	@Test
	fun `저장한 출생 정보를 그대로 돌려준다`() {
		val token = devToken(port)
		assertEquals(404, http(port, "GET", "/profile", token = token).statusCode())
		assertEquals("""{"error":"NO_PROFILE"}""", http(port, "GET", "/profile", token = token).body())

		val body = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
		val saved = http(port, "PUT", "/profile", body, token)
		assertEquals(200, saved.statusCode())
		assertEquals(body, saved.body())
		assertEquals(body, http(port, "GET", "/profile", token = token).body())

		// 다시 저장하면 바뀐다. 시각 모름은 null 로 오가고, 그때는 태어난 곳도 null 이다
		val unknown = """{"date":"2000-06-15","time":null,"placeId":null}"""
		assertEquals(unknown, http(port, "PUT", "/profile", unknown, token).body())
		assertEquals(unknown, http(port, "GET", "/profile", token = token).body())
		assertEquals(1, jdbc.queryForObject("select count(*) from profiles", Int::class.java))
	}

	// 태어난 곳은 상승점·하우스에만 쓰인다. 시각을 모르면 받지 않아도 되고, 와도 저장하지 않는다(결정 007).
	@Test
	fun `시각을 모르면 태어난 곳을 저장하지 않는다`() {
		val token = devToken(port)
		val stored = """{"date":"2000-06-15","time":null,"placeId":null}"""
		fun storedPlace(): Int? = jdbc.queryForObject("select place_id from profiles", Int::class.java)

		// placeId 없이
		val saved = http(port, "PUT", "/profile", """{"date":"2000-06-15","time":null}""", token)
		assertEquals(200, saved.statusCode(), saved.body())
		assertEquals(stored, saved.body())
		assertEquals(stored, http(port, "GET", "/profile", token = token).body())
		assertNull(storedPlace())

		// placeId 를 보내도 저장하지 않는다. 읽지 않으므로 목록에 없는 id 여도 400 이 아니다
		for (sent in listOf(MAPO, 999)) {
			val again = http(port, "PUT", "/profile", """{"date":"2000-06-15","time":null,"placeId":$sent}""", token)
			assertEquals(200, again.statusCode(), again.body())
			assertEquals(stored, again.body())
			assertEquals(stored, http(port, "GET", "/profile", token = token).body())
			assertNull(storedPlace())
		}

		// 시각을 알던 사람이 모름으로 고치면 저장돼 있던 곳도 지워진다
		val known = """{"date":"2000-06-15","time":"09:05","placeId":$MAPO}"""
		assertEquals(known, http(port, "PUT", "/profile", known, token).body())
		assertEquals(MAPO, storedPlace())
		assertEquals(stored, http(port, "PUT", "/profile", """{"date":"2000-06-15","time":null,"placeId":$MAPO}""", token).body())
		assertNull(storedPlace())
	}

	@Test
	fun `틀린 출생 정보는 400 이고 저장하지 않는다`() {
		val token = devToken(port)
		fun assertBad(body: String, error: String) {
			val response = http(port, "PUT", "/profile", body, token)
			assertEquals(400, response.statusCode(), body)
			assertEquals("""{"error":"$error"}""", response.body(), body)
		}
		assertBad("""{"date":"1990-03-15","time":"14:30","placeId":999}""", "UNKNOWN_PLACE")
		assertBad("""{"date":"1990-03-15","time":"14:30"}""", "UNKNOWN_PLACE")
		assertBad("""{"date":"1990-03-15","time":"14:30","placeId":null}""", "UNKNOWN_PLACE")
		assertBad("""{"date":"1990-02-30","time":null}""", "INVALID_DATE")
		assertBad("""{"date":"1990-02-30","time":"14:30","placeId":$MAPO}""", "INVALID_DATE")
		assertBad("""{"date":"1899-12-31","time":"14:30","placeId":$MAPO}""", "INVALID_DATE")
		assertBad("""{"date":"1990-03-15","time":"25:00","placeId":$MAPO}""", "INVALID_TIME")
		assertBad("""{"date":""", "INVALID_REQUEST")
		assertEquals(0, jdbc.queryForObject("select count(*) from profiles", Int::class.java))
	}

	// 소유자는 토큰에서만 얻는다. 다른 계정의 토큰으로는 그 계정의 것만 보이고 바뀐다.
	@Test
	fun `남의 출생 정보는 보이지도 바뀌지도 않는다`() {
		val mine = devToken(port)
		val theirs = "another-accounts-session-token"
		val other = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', 'someone-else')", other)
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(theirs), other)

		val myBody = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
		val theirBody = """{"date":"1985-11-02","time":"06:15","placeId":$MAPO}"""
		http(port, "PUT", "/profile", myBody, mine)
		assertEquals(404, http(port, "GET", "/profile", token = theirs).statusCode(), "내 것이 남에게 보인다")
		http(port, "PUT", "/profile", theirBody, theirs)

		assertEquals(myBody, http(port, "GET", "/profile", token = mine).body())
		assertEquals(theirBody, http(port, "GET", "/profile", token = theirs).body())

		// 남이 계정을 지워도 내 것은 남는다
		assertEquals(204, http(port, "DELETE", "/account", token = theirs).statusCode())
		assertEquals(myBody, http(port, "GET", "/profile", token = mine).body())
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `출생 정보를 로그에 남기지 않는다`(output: CapturedOutput) {
		val token = devToken(port)
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":"07:07","placeId":$MAPO}""", token)
		http(port, "GET", "/profile", token = token)
		http(port, "PUT", "/profile", """{"date":"1977-07-07","time":"77:77","placeId":$MAPO}""", token)
		assertFalse(output.all.contains("1977"), output.all)
		assertFalse(output.all.contains("07:07"), output.all)
	}

	private fun sha256(text: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
