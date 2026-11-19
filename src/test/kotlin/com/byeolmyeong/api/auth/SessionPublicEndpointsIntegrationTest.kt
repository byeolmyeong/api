package com.byeolmyeong.api.auth

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val UNAUTHORIZED = """{"error":"UNAUTHORIZED"}"""

/**
 * 배포 전 확인 5: 세션 없이 여는 곳은 "메서드 + 경로" 로 정한다. 공개 경로에 다른 메서드의 컨트롤러가 생겨도 세션 없이 열리지 않는다.
 * 테스트 전용 컨트롤러로 공개 경로 아래에 다른 메서드를 붙여 본다(지금 저장소에는 없는 경로다).
 */
@Import(TestcontainersConfiguration::class, SessionPublicEndpointsIntegrationTest.ProbeController::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class SessionPublicEndpointsIntegrationTest {

	@RestController
	class ProbeController {
		@DeleteMapping("/shared/{shareId}")
		fun deleteShared(@PathVariable shareId: String) = "probe"

		@GetMapping("/invites/{id}")
		fun getInvite(@PathVariable id: String) = "probe"

		@PostMapping("/invites/{id}")
		fun postInvite(@PathVariable id: String) = "probe"
	}

	@LocalServerPort
	private var port: Int = 0

	@Test
	fun `공개 경로라도 열지 않은 메서드는 세션이 있어야 한다`() {
		val response = http(port, "DELETE", "/shared/abc")
		assertEquals(401, response.statusCode())
		assertEquals(UNAUTHORIZED, response.body())
		assertEquals(200, http(port, "DELETE", "/shared/abc", token = devToken(port)).statusCode())
	}

	@Test
	fun `초대 경로는 아직 만들지 않아 공개 목록에 없다`() {
		assertEquals(401, http(port, "GET", "/invites/abc").statusCode())
		assertEquals(401, http(port, "POST", "/invites/abc", "{}").statusCode())
	}

	@Test
	fun `지금 로그인 전에 쓰는 곳은 그대로 열려 있다`() {
		assertEquals(200, http(port, "GET", "/health").statusCode())
		assertEquals(200, http(port, "HEAD", "/health").statusCode())
		assertNotEquals(401, http(port, "GET", "/places").statusCode())
		assertEquals(200, http(port, "GET", "/faqs").statusCode())
		assertEquals(200, http(port, "GET", "/app-config").statusCode())
		assertEquals(404, http(port, "GET", "/shared/no-such-share").statusCode())
		assertEquals(200, http(port, "POST", "/auth/dev").statusCode())
		for (path in listOf("/auth/login", "/auth/signup", "/auth/apple/notifications")) {
			assertNotEquals(UNAUTHORIZED, http(port, "POST", path, "{}").body(), path)
		}
	}
}
