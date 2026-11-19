package com.byeolmyeong.api.auth

import com.byeolmyeong.api.ByeolmyeongApiApplication
import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 결정 022 의 안전장치가 실제 서버 시작에 걸리는지 본다(리뷰 2). DevLoginGuardTest 는 클래스만 직접 만들어 보므로,
 * 안전장치가 빈으로 등록되지 않거나 조건이 붙어도 통과한다. 여기서는 서버 전체를 띄운다.
 * 명령줄 인자는 테스트 설정(`deploy-env=local`)보다 우선한다.
 */
class DevLoginGuardStartupTest {

	private fun start(vararg args: String): ConfigurableApplicationContext =
		SpringApplicationBuilder(ByeolmyeongApiApplication::class.java, TestcontainersConfiguration::class.java)
			.run("--server.port=0", *args)

	private fun refusedByGuard(e: Throwable) =
		generateSequence(e) { it.cause }.any { it is IllegalStateException && it.message.orEmpty().contains("결정 022") }

	@Test
	fun `개발용 로그인이 켜졌는데 운영이거나 환경이 비어 있으면 서버가 시작하지 않는다`() {
		for (env in listOf("prod", "")) {
			val e = assertFailsWith<Throwable>("deploy-env='$env'") {
				start("--byeolmyeong.auth.dev-enabled=true", "--byeolmyeong.deploy-env=$env").close()
			}
			assertTrue(refusedByGuard(e), "안전장치가 시작을 막아야 한다(deploy-env='$env'): $e")
		}
	}

	@Test
	fun `스테이징이면 개발용 로그인을 켠 채 서버가 시작한다`() {
		start("--byeolmyeong.auth.dev-enabled=true", "--byeolmyeong.deploy-env=staging").use { assertTrue(it.isActive) }
	}
}
