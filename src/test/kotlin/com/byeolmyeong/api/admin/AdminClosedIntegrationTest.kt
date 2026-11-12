package com.byeolmyeong.api.admin

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
import kotlin.test.assertTrue

/** 첫 관리자의 환경 변수를 주지 않은 서버(기본 설정). 관리자가 없으니 관리자 API 는 전부 닫혀 있어야 한다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON, "byeolmyeong.admin.bcrypt-strength=4"])
class AdminClosedIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var accounts: AdminAccountService

	private fun admins(): Int = jdbc.queryForObject("select count(*) from admin_accounts", Int::class.java)!!

	@Test
	fun `환경 변수가 없으면 관리자가 없고 admin 아래는 전부 닫혀 있다`() {
		assertEquals(0, admins())

		for (path in listOf("/admin/me", "/admin/admins", "/admin/inquiries", "/admin/stats", "/admin/faqs", "/admin/anything")) {
			for (token in listOf(null, "", "any-token", devToken(port))) {
				val response = http(port, "GET", path, token = token)
				assertEquals(401, response.statusCode(), path)
				assertEquals("""{"error":"UNAUTHORIZED"}""", response.body())
			}
		}
		val created = http(port, "POST", "/admin/admins", json(mapOf("loginId" to "first-admin", "name" to "첫", "password" to "first-admin-password")))
		assertEquals(401, created.statusCode(), "세션 없이 관리자를 만들 수 없다")
		for ((id, password) in listOf("" to "", "admin" to "", "admin" to "admin", "root" to "password", "admin" to "admin-password")) {
			val response = login(port, id, password)
			assertEquals(401, response.statusCode(), "$id 로 들어갈 수 없다")
			assertEquals("""{"error":"INVALID_CREDENTIALS"}""", response.body())
		}
		assertEquals(0, admins())
	}

	@Test
	@ExtendWith(OutputCaptureExtension::class)
	fun `두 변수 가운데 하나만 있거나 규칙에 안 맞으면 첫 관리자를 만들지 않는다`(output: CapturedOutput) {
		val secret = "bootstrap-secret-password"
		assertFalse(accounts.bootstrap("", ""))
		assertFalse(accounts.bootstrap("first-admin", ""), "비밀번호가 없다")
		assertFalse(accounts.bootstrap("", secret), "아이디가 없다")
		assertFalse(accounts.bootstrap("   ", secret))
		assertFalse(accounts.bootstrap("first-admin", "short-pw"), "비밀번호가 10자보다 짧다")
		assertFalse(accounts.bootstrap("first admin", secret), "아이디가 규칙에 안 맞는다")
		assertFalse(accounts.bootstrap("first-admin", "x".repeat(73)))
		assertEquals(0, admins())
		assertEquals(401, login(port, "first-admin", secret).statusCode())

		assertTrue(accounts.bootstrap(" First-Admin ", secret), "둘 다 있고 규칙에 맞으면 만든다")
		assertEquals(listOf("first-admin"), jdbc.queryForList("select login_id from admin_accounts", String::class.java))
		assertEquals(200, login(port, "first-admin", secret).statusCode())
		assertFalse(accounts.bootstrap("second-admin", secret), "이제 관리자가 있다")
		assertEquals(1, admins())

		assertFalse(output.all.contains(secret), "비밀번호를 로그에 남기지 않는다")
		assertFalse(output.all.contains("short-pw"))
		assertTrue(output.all.contains("event=admin_bootstrap_created"))
		jdbc.update("delete from admin_accounts")
	}
}

/** 로그인 제한. 설정을 건드리지 않아 기본값(10분에 아이디별 5번, 주소별 20번)을 본다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["byeolmyeong.admin.bootstrap-id=$ROOT_ID", "byeolmyeong.admin.bootstrap-password=$ROOT_PASSWORD", "byeolmyeong.admin.bcrypt-strength=4"],
)
class AdminLoginRateLimitIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `한 아이디에 여섯 번째 시도부터, 한 주소에서 스물한 번째 시도부터 429 다`() {
		repeat(5) { assertEquals(401, login(port, ROOT_ID, "wrong-password-$it").statusCode(), "${it + 1}번째") }

		val limited = login(port, ROOT_ID, "wrong-password-5")
		assertEquals(429, limited.statusCode())
		assertEquals("""{"error":"RATE_LIMITED"}""", limited.body())
		assertEquals(429, login(port, ROOT_ID, ROOT_PASSWORD).statusCode(), "맞는 비밀번호여도 막힌 동안은 들어갈 수 없다")
		assertEquals(429, login(port, " ROOT-ADMIN ", ROOT_PASSWORD).statusCode(), "대소문자·공백으로 제한을 피할 수 없다")

		// 여기까지 이 주소에서 8번. 다른 아이디는 아이디별 제한에 걸리지 않는다.
		repeat(12) { assertEquals(401, login(port, "other-admin-$it", "wrong-password").statusCode(), "다른 아이디 ${it + 1}번째") }

		val byAddress = login(port, "yet-another-admin", "wrong-password")
		assertEquals(429, byAddress.statusCode(), "주소별 한도(20)를 넘었다")
		assertEquals("""{"error":"RATE_LIMITED"}""", byAddress.body())
	}
}
