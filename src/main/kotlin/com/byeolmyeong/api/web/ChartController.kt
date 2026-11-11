package com.byeolmyeong.api.web

import com.byeolmyeong.api.birth.InvalidBirthDateException
import com.byeolmyeong.api.birth.Places
import com.byeolmyeong.api.birth.toUtc
import com.byeolmyeong.api.chart.computeChart
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** 요청 본문. 값 검증은 컨트롤러가 하므로 모두 느슨한 타입으로 받는다. */
data class ChartRequest(val date: String?, val time: String?, val placeId: Int?)

data class ErrorResponse(val error: String)

class BadChartRequest(val code: String) : RuntimeException(code)

/**
 * 로그인 없는 차트 계산. 요청의 출생 정보는 저장하지도 로그에 남기지도 않는다
 * (오류 응답과 예외 메시지에도 입력값을 넣지 않는다).
 */
@RestController
class ChartController(private val places: Places) {

	@PostMapping("/charts")
	fun create(@RequestBody request: ChartRequest): ChartResponse {
		val place = request.placeId?.let(places::find) ?: throw BadChartRequest("UNKNOWN_PLACE")
		val date = parse("INVALID_DATE") { LocalDate.parse(request.date ?: throw BadChartRequest("INVALID_DATE")) }
		val time = request.time?.let { parse("INVALID_TIME") { LocalTime.parse(it) } }
		val utc = try {
			toUtc(date, time)
		} catch (e: InvalidBirthDateException) {
			throw BadChartRequest("INVALID_DATE")
		}
		return computeChart(utc, place.lat, place.lon, timeKnown = time != null).toResponse()
	}

	private fun <T> parse(code: String, block: () -> T): T = try {
		block()
	} catch (e: DateTimeParseException) {
		throw BadChartRequest(code)
	}

	@ExceptionHandler(BadChartRequest::class)
	fun badRequest(e: BadChartRequest): ResponseEntity<ErrorResponse> =
		ResponseEntity.badRequest().body(ErrorResponse(e.code))

	// 본문이 JSON 이 아니거나 타입이 틀린 경우. 기본 처리기는 입력 조각이 든 메시지를 경고 로그로 남기므로 여기서 받는다.
	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun unreadable(): ResponseEntity<ErrorResponse> =
		ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST"))
}
