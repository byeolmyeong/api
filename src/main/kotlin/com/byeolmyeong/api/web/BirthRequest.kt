package com.byeolmyeong.api.web

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.birth.ChartInput
import com.byeolmyeong.api.birth.InvalidBirthDateException
import com.byeolmyeong.api.birth.Place
import com.byeolmyeong.api.birth.Places
import com.byeolmyeong.api.birth.toUtc
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** 출생 정보 요청 본문(`POST /charts`·`PUT /profile`). 값 검증은 [BirthRequestParser] 가 하므로 모두 느슨한 타입으로 받는다. */
data class BirthRequest(val date: String?, val time: String?, val placeId: Int?)

/**
 * 검증을 통과한 출생 정보. [utc] 는 그때의 표준시·서머타임을 반영한 시각이고, 시각을 모르면 그날 정오다.
 * [place] 는 시각을 알 때만 있다. 시각을 모르면 요청에 `placeId` 가 와도 읽지 않는다(결정 007).
 */
data class Birth(val date: LocalDate, val time: LocalTime?, val place: Place?, val utc: Instant)

/** 시각은 `HH:mm` 만 받는다. 초나 소수 초가 붙거나 `24:00` 이면 INVALID_TIME(엄격 해석은 24시를 다음 날 0시로 넘기지 않는다). */
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

/**
 * 요청의 출생 정보를 검증한다. 틀리면 [BadRequestException] 을 던진다
 * (`UNKNOWN_PLACE` · `INVALID_DATE` · `INVALID_TIME`). 오류에 입력값을 넣지 않는다.
 */
@Component
class BirthRequestParser(
	private val places: Places,
	/** "지금"(오늘 이후 날짜·오늘의 아직 오지 않은 시각)을 판정하는 시계. 테스트가 바꿔 끼운다. */
	private val clock: Clock = Clock.system(KOREA_ZONE),
) {

	fun parse(request: BirthRequest): Birth {
		val place = if (request.time == null) null else request.placeId?.let(places::find) ?: throw BadRequestException("UNKNOWN_PLACE")
		val date = parse("INVALID_DATE") { LocalDate.parse(request.date ?: throw BadRequestException("INVALID_DATE")) }
		val time = request.time?.let { parse("INVALID_TIME") { LocalTime.parse(it, HH_MM) } }
		// "지금"은 한 번만 읽는다(자정을 걸쳐 날짜와 시각이 어긋나지 않게). 날짜 검사(toUtc)도 같은 오늘을 쓴다.
		val now = LocalDateTime.now(clock)
		// 오늘 태어났다면 아직 오지 않은 시각은 받지 않는다(날짜만 보는 toUtc 가 놓치는 경우). 시각 모름은 그대로 받는다.
		if (time != null && date == now.toLocalDate() && time.isAfter(now.toLocalTime())) throw BadRequestException("INVALID_TIME")
		val utc = try {
			toUtc(date, time, today = now.toLocalDate())
		} catch (e: InvalidBirthDateException) {
			throw BadRequestException("INVALID_DATE")
		}
		return Birth(date, time, place, utc)
	}

	/**
	 * 요청에 적힌 사람(궁합의 상대)의 출생 정보를 검증해 차트 입력으로 바꾼다. 저장하지 않고 계산에만 쓴다(결정 010).
	 * 시각을 모르면 곳을 읽지 않고 위경도는 차트에 쓰이지 않는다(0.0 은 자리만 채운다).
	 */
	fun chartInput(request: BirthRequest): ChartInput {
		val birth = parse(request)
		val place = birth.place ?: return ChartInput(birth.utc, lat = 0.0, lon = 0.0, timeKnown = false)
		return ChartInput(birth.utc, place.lat, place.lon, timeKnown = true)
	}

	private fun <T> parse(code: String, block: () -> T): T = try {
		block()
	} catch (e: DateTimeParseException) {
		throw BadRequestException(code)
	}
}
