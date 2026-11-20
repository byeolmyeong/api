package com.byeolmyeong.api.sky

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.auth.koreaToday
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.web.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 그날의 하늘(결정 034) — 달력에서 누른 날, 날짜 없이(또는 오늘 날짜로) 부르면 홈의 오늘의 하늘. 누구의 것인지는 세션에서,
 * 출생 정보는 저장된 프로필에서 얻는다. 처음 만들 때는 수십 초가 걸릴 수 있다(동기 응답).
 */
@RestController
class DaySkyController(private val owner: CurrentOwner, private val skies: DaySkyService) {

	@PostMapping("/day-sky")
	fun daySky(@RequestBody(required = false) request: DaySkyRequest?): DaySky =
		skies.daySky(owner.id(), parseDaySkyDate(request?.date, koreaToday()), countQuota = true)

	@ExceptionHandler(GenerationException::class)
	fun generationFailed(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(502).body(ErrorResponse("GENERATION_FAILED"))
}
