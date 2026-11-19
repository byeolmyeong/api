package com.byeolmyeong.api.auth

import com.byeolmyeong.api.ByeolmyeongApiApplication
import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 결정 026: 약관 · 처리방침 버전(`byeolmyeong.legal.*`)이 비어 있으면 모든 가입이 400 이 되므로 서버가 아예 시작하지 않는다(계획 리뷰 초점 3).
 * 빈으로 등록됐는지까지 보려고 서버 전체를 띄운다. 명령줄 인자는 설정 파일보다 우선한다.
 */
class LegalVersionsStartupTest {

	private fun refusedForBlankVersion(e: Throwable) =
		generateSequence(e) { it.cause }.any { it is IllegalStateException && it.message.orEmpty().contains("결정 026") }

	@Test
	fun `약관이나 처리방침 버전이 비어 있으면 서버가 시작하지 않는다`() {
		for (arg in listOf("--byeolmyeong.legal.terms-version=", "--byeolmyeong.legal.privacy-version=   ")) {
			val e = assertFailsWith<Throwable>(arg) {
				SpringApplicationBuilder(ByeolmyeongApiApplication::class.java, TestcontainersConfiguration::class.java)
					.run("--server.port=0", arg).close()
			}
			assertTrue(refusedForBlankVersion(e), "빈 약관 버전이 시작을 막아야 한다($arg): $e")
		}
	}
}
