package com.byeolmyeong.api.web

import com.byeolmyeong.api.chart.computeChart
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 차트 계산. 세션이 있어야 한다(세션 검사는 `auth` 가 한다). 요청의 출생 정보는 저장하지도 로그에 남기지도 않는다
 * (오류 응답과 예외 메시지에도 입력값을 넣지 않는다).
 */
@RestController
class ChartController(private val parser: BirthRequestParser) {

	@PostMapping("/charts")
	fun create(@RequestBody request: BirthRequest): ChartResponse {
		val birth = parser.parse(request)
		// 시각을 모르면 곳이 없고 위경도는 계산에 쓰이지 않는다(0.0 은 자리만 채운다).
		return computeChart(birth.utc, birth.place?.lat ?: 0.0, birth.place?.lon ?: 0.0, timeKnown = birth.time != null).toResponse()
	}
}
