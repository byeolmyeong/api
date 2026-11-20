package com.byeolmyeong.api.sky

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.auth.koreaToday
import com.byeolmyeong.api.web.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 시기 탭의 이번 달(결정 032). 누구의 것인지는 세션에서, 출생 정보는 저장된 프로필에서 얻는다. 시기 풀이 없이도 열린다. */
@RestController
class MonthSkyController(private val owner: CurrentOwner, private val months: MonthSkyService) {

	@GetMapping("/timing/month")
	fun month(@RequestParam(required = false) month: String?): MonthSky =
		months.month(owner.id(), parseMonth(month, koreaToday()))

	@ExceptionHandler(MonthRateLimitedException::class)
	fun rateLimited(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(429).body(ErrorResponse("RATE_LIMITED"))
}
