package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val UNAUTHORIZED = """{"error":"UNAUTHORIZED"}"""
private const val PROFILE = """{"date":"1990-03-15","time":"14:30","placeId":1841277}"""

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [DEV_AUTH_ON, "byeolmyeong.cors.allowed-origins=http://localhost:8081"],
)
class AuthApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

	@Test
	fun `개발용 로그인은 토큰을 주고 두 번 불러도 계정은 하나다`() {
		val first = devToken(port)
		val second = devToken(port)
		assertTrue(first.length >= 32)
		assertNotEquals(first, second, "부를 때마다 새 세션이다")
		assertEquals(1, count("accounts"))
		assertEquals("DEV", jdbc.queryForObject("select provider from accounts", String::class.java))
		// 두 토큰 모두 같은 계정의 세션으로 통한다
		assertEquals(404, http(port, "GET", "/profile", token = first).statusCode())
		assertEquals(404, http(port, "GET", "/profile", token = second).statusCode())
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `토큰 원문은 DB 에도 로그에도 남기지 않는다`(output: CapturedOutput) {
		val token = devToken(port)
		http(port, "GET", "/profile", token = token)
		val stored = jdbc.queryForList("select token_hash from sessions", String::class.java)
		assertTrue(stored.isNotEmpty())
		assertFalse(stored.contains(token))
		assertFalse(output.all.contains(token), "로그에 토큰이 있다")
	}

	@Test
	fun `토큰이 없거나 틀리면 세션이 필요한 곳은 401 이다`() {
		for ((method, path, body) in listOf(
			Triple("GET", "/profile", null),
			Triple("PUT", "/profile", PROFILE),
			Triple("POST", "/charts", PROFILE),
			Triple("DELETE", "/account", null),
		)) {
			val none = http(port, method, path, body)
			assertEquals(401, none.statusCode(), "$method $path")
			assertEquals(UNAUTHORIZED, none.body())
			assertEquals(401, http(port, method, path, body, token = "not-a-real-token").statusCode(), "$method $path 틀린 토큰")
		}
		assertEquals(401, http(port, "GET", "/profile", null, null, "Authorization", "Basic abc").statusCode())
	}

	@Test
	fun `로그인 전에 쓰는 곳은 토큰 없이 열린다`() {
		assertEquals(200, http(port, "GET", "/health").statusCode())
		assertEquals(200, http(port, "GET", "/places").statusCode())
		assertEquals(200, http(port, "POST", "/auth/dev").statusCode())
	}

	@Test
	fun `401 에도 허용한 주소의 브라우저가 읽을 수 있게 허용 헤더가 붙는다`() {
		val response = http(port, "GET", "/profile", null, null, "Origin", "http://localhost:8081")
		assertEquals(401, response.statusCode())
		assertEquals("http://localhost:8081", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
	}

	@Test
	fun `계정을 지우면 그 계정의 행이 모두 사라지고 기존 토큰은 401 이다`() {
		val token = devToken(port)
		assertEquals(200, http(port, "PUT", "/profile", PROFILE, token).statusCode())
		assertEquals(1, count("profiles"))

		assertEquals(204, http(port, "DELETE", "/account", token = token).statusCode())

		assertEquals(0, count("accounts"))
		assertEquals(0, count("sessions"))
		assertEquals(0, count("profiles"))
		assertEquals(401, http(port, "GET", "/profile", token = token).statusCode())
	}
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DevAuthOffByDefaultIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	// 운영 설정은 기본값을 쓴다. 기본값에서 개발용 로그인이 열려 있으면 누구나 같은 계정으로 들어온다.
	@Test
	fun `설정을 켜지 않으면 개발용 로그인은 404 이고 계정을 만들지 않는다`() {
		assertEquals(404, http(port, "POST", "/auth/dev").statusCode())
		assertEquals(0, jdbc.queryForObject("select count(*) from accounts", Int::class.java))
	}
}
