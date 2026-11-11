package com.byeolmyeong.api.direction

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** `POST /directions` 의 요청 본문. 값은 컨트롤러가 본다. */
data class DirectionRequest(val topic: String?)

/** 방향성 제시. 누구의 것인지는 세션에서, 출생 정보는 저장된 프로필에서 얻는다. 지금 받는 주제는 `LOVE` 하나다. */
@RestController
class DirectionController(private val owner: CurrentOwner, private val directions: DirectionService) {

	@PostMapping("/directions")
	fun create(@RequestBody request: DirectionRequest): Direction = when (request.topic) {
		TOPIC_LOVE -> directions.love(owner.id())
		else -> throw BadRequestException("INVALID_TOPIC")
	}

	@ExceptionHandler(GenerationException::class)
	fun generationFailed(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(502).body(ErrorResponse("GENERATION_FAILED"))
}
