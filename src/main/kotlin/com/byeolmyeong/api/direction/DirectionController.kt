package com.byeolmyeong.api.direction

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.BirthRequest
import com.byeolmyeong.api.web.BirthRequestParser
import com.byeolmyeong.api.web.ErrorResponse
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `POST /directions` 의 요청 본문. 값은 컨트롤러가 본다. [synastryId] 와 상대의 출생 정보([date]·[time]·[placeId])는 궁합 주제에서만 읽는다.
 */
data class DirectionRequest(val topic: String?, val synastryId: String?, val date: String?, val time: String?, val placeId: Int?)

/**
 * 방향성 제시. 누구의 것인지는 세션에서, 내 출생 정보는 저장된 프로필에서 얻는다. 주제는 `LOVE`·`FUTURE`·`SYNASTRY` 다.
 * 궁합 주제의 상대 출생 정보는 본문으로 받아 계산에만 쓴다(저장하지 않는다).
 */
@RestController
class DirectionController(
	private val owner: CurrentOwner,
	private val directions: DirectionService,
	private val parser: BirthRequestParser,
) {

	@PostMapping("/directions")
	fun create(@RequestBody request: DirectionRequest): Direction = when (request.topic) {
		TOPIC_LOVE -> directions.love(owner.id())
		TOPIC_FUTURE -> directions.future(owner.id())
		TOPIC_SYNASTRY -> directions.synastry(owner.id(), synastryId(request.synastryId)) {
			// 글이 아직 없을 때만 불린다. 앱은 이 오류를 받으면 상대의 출생 정보를 다시 입력받아 보낸다.
			if (request.date == null) throw BadRequestException("BIRTH_REQUIRED")
			parser.chartInput(BirthRequest(request.date, request.time, request.placeId))
		}
		else -> throw BadRequestException("INVALID_TOPIC")
	}

	// id 모양이 아닌 값도 "없는 것" 이다.
	private fun synastryId(value: String?): UUID = try {
		UUID.fromString(value ?: throw BadRequestException("INVALID_REQUEST"))
	} catch (e: IllegalArgumentException) {
		throw NotFoundException("NOT_FOUND")
	}

	@ExceptionHandler(ProfileChangedException::class)
	fun profileChanged(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(409).body(ErrorResponse("PROFILE_CHANGED"))

	@ExceptionHandler(GenerationException::class)
	fun generationFailed(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(502).body(ErrorResponse("GENERATION_FAILED"))
}
