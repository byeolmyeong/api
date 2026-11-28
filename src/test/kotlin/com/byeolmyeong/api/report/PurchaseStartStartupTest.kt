package com.byeolmyeong.api.report

import com.byeolmyeong.api.ByeolmyeongApiApplication
import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 결정 056 의 1: 결제 시작 시각을 넣었는데 RevenueCat 비밀 키가 비어 있으면 표에 없는 계정의 받기가 모두 503 이 되므로 서버가 아예 시작하지 않는다.
 * 빈으로 등록됐는지까지 보려고 서버 전체를 띄운다. 명령줄 인자는 설정 파일보다 우선한다.
 */
class PurchaseStartStartupTest {

	@Test
	fun `결제 시작 시각이 있는데 RevenueCat 비밀 키가 비어 있으면 서버가 시작하지 않는다`() {
		val e = assertFailsWith<Throwable> {
			SpringApplicationBuilder(ByeolmyeongApiApplication::class.java, TestcontainersConfiguration::class.java)
				.run("--server.port=0", "--byeolmyeong.purchase.starts-at=2026-12-10T00:00:00+09:00", "--byeolmyeong.purchase.revenuecat-secret-key=").close()
		}
		assertTrue(generateSequence(e) { it.cause }.any { it is IllegalStateException && it.message.orEmpty().contains("REVENUECAT_SECRET_KEY") }, "$e")
	}
}
