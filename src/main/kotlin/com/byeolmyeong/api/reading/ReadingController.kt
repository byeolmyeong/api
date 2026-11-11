package com.byeolmyeong.api.reading

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.web.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** 요약 해석. 요청 본문이 없다 — 누구의 것인지는 세션에서, 출생 정보는 저장된 프로필에서 얻는다. */
@RestController
class ReadingController(private val owner: CurrentOwner, private val readings: ReadingService) {

	@PostMapping("/readings")
	fun create(): Reading = readings.summary(owner.id())

	@ExceptionHandler(ReadingGenerationException::class)
	fun generationFailed(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(502).body(ErrorResponse("GENERATION_FAILED"))
}
