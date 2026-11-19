package com.byeolmyeong.api.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** 결정 022: 개발용 로그인은 배포 환경이 local·staging 일 때만 켤 수 있다. 그 밖에서 켜져 있으면 서버가 시작하지 않는다. */
class DevLoginGuardTest {

	@Test
	fun `개발용 로그인이 켜졌는데 운영이면 시작을 거부한다`() {
		assertFailsWith<IllegalStateException> { DevLoginGuard(devEnabled = true, deployEnv = "prod") }
	}

	@Test
	fun `개발용 로그인이 켜졌는데 환경이 비어 있으면 운영으로 보고 거부한다`() {
		assertFailsWith<IllegalStateException> { DevLoginGuard(devEnabled = true, deployEnv = "") }
		assertFailsWith<IllegalStateException> { DevLoginGuard(devEnabled = true, deployEnv = "   ") }
	}

	@Test
	fun `모르는 환경 이름에서도 개발용 로그인은 거부한다`() {
		assertFailsWith<IllegalStateException> { DevLoginGuard(devEnabled = true, deployEnv = "qa") }
		assertFailsWith<IllegalStateException> { DevLoginGuard(devEnabled = true, deployEnv = "PROD") }
	}

	@Test
	fun `로컬과 스테이징에서는 개발용 로그인을 켤 수 있다`() {
		DevLoginGuard(devEnabled = true, deployEnv = "local")
		DevLoginGuard(devEnabled = true, deployEnv = "staging")
	}

	@Test
	fun `개발용 로그인이 꺼져 있으면 어느 환경이든 시작한다`() {
		for (env in listOf("prod", "", "staging", "local", "qa")) DevLoginGuard(devEnabled = false, deployEnv = env)
	}
}
