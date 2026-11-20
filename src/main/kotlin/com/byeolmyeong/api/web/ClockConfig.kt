package com.byeolmyeong.api.web

import com.byeolmyeong.api.birth.KOREA_ZONE
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * "지금" 을 주는 시계 하나. 테스트가 고정 시계로 바꿔 끼운다(`@Primary`).
 * 시간대는 한국이다 — 이 빈은 `BirthRequestParser` 의 기본 시계(한국) 자리에도 들어가므로 그 판정을 바꾸지 않는다.
 * "오늘" 을 셀 때는 시계의 시간대에 기대지 말고 `instant()` 를 한국 날짜로 바꿔 쓴다.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	fun clock(): Clock = Clock.system(KOREA_ZONE)
}
