package com.byeolmyeong.api.birth

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.computeChart
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.sql.Time
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.HexFormat
import java.util.UUID

/** 한 사람이 저장해 둔 출생 정보. [time] 은 모르면 null, [placeId] 는 출생지 목록의 id 이고 시각을 모르면 null 이다(결정 007). */
data class BirthProfile(val date: LocalDate, val time: LocalTime?, val placeId: Int?)

/**
 * 차트를 정하는 입력. 저장된 글이 "같은 차트"의 것인지는 [hash] 로 가린다(출생 정보 원문을 글 옆에 두지 않는다).
 * 시각을 모르면 [utc] 는 그날 정오이고 위경도는 차트에 쓰이지 않으므로 해시에 넣지 않는다.
 * 계산 방식이 바뀌어 글을 다시 써야 하면 해시 앞의 `v1` 을 올린다.
 */
data class ChartInput(val utc: Instant, val lat: Double, val lon: Double, val timeKnown: Boolean) {

	/** SHA-256(16진수 64자). */
	val hash: String
		get() {
			val text = if (timeKnown) "v1|${utc.epochSecond}|$lat|$lon" else "v1|${utc.epochSecond}|time-unknown"
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
		}

	fun chart(): Chart = computeChart(utc, lat, lon, timeKnown)
}

/**
 * 저장된 출생 정보를 읽고 쓰는 한 곳. 해석·리포트·궁합이 모두 소유자의 출생 정보를 쓰므로 공통 패키지에 둔다
 * (기능 패키지는 서로 의존하지 않는다). 소유자 id 는 부르는 쪽이 세션에서 얻어 넘긴다.
 */
@Service
class BirthProfileService(private val repository: BirthProfileRepository, private val places: Places) {

	fun find(ownerId: UUID): BirthProfile? = repository.find(ownerId)

	/** 소유자가 저장해 둔 출생 정보의 차트 입력. 저장한 적 없으면 null. */
	fun chartInput(ownerId: UUID): ChartInput? {
		val profile = find(ownerId) ?: return null
		val utc = toUtc(profile.date, profile.time)
		// 시각을 모르면 저장된 곳이 없고 위경도는 차트에도 해시에도 쓰이지 않는다(0.0 은 자리만 채운다).
		if (profile.time == null) return ChartInput(utc, lat = 0.0, lon = 0.0, timeKnown = false)
		val place = checkNotNull(profile.placeId?.let(places::find)) { "저장된 출생지가 목록에 없다" }
		return ChartInput(utc, place.lat, place.lon, timeKnown = true)
	}

	fun save(ownerId: UUID, profile: BirthProfile) = repository.upsert(ownerId, profile)
}

@Repository
class BirthProfileRepository(private val jdbc: JdbcTemplate) {

	fun find(ownerId: UUID): BirthProfile? = jdbc.query(
		"select birth_date, birth_time, place_id from profiles where account_id = ?",
		{ rs, _ ->
			BirthProfile(
				date = rs.getDate("birth_date").toLocalDate(),
				time = rs.getTime("birth_time")?.toLocalTime(),
				placeId = rs.getObject("place_id") as Int?,
			)
		},
		ownerId,
	).firstOrNull()

	fun upsert(ownerId: UUID, profile: BirthProfile) {
		jdbc.update(
			"""
			insert into profiles (account_id, birth_date, birth_time, place_id) values (?, ?, ?, ?)
			on conflict (account_id) do update
			set birth_date = excluded.birth_date, birth_time = excluded.birth_time,
				place_id = excluded.place_id, updated_at = now()
			""".trimIndent(),
			ownerId,
			java.sql.Date.valueOf(profile.date),
			profile.time?.let(Time::valueOf),
			profile.placeId,
		)
	}
}
