package com.byeolmyeong.api.birth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.sql.Time
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 한 사람이 저장해 둔 출생 정보. [time] 은 모르면 null, [placeId] 는 출생지 목록의 id. */
data class BirthProfile(val date: LocalDate, val time: LocalTime?, val placeId: Int)

/**
 * 저장된 출생 정보를 읽고 쓰는 한 곳. 해석·리포트·궁합이 모두 소유자의 출생 정보를 쓰므로 공통 패키지에 둔다
 * (기능 패키지는 서로 의존하지 않는다). 소유자 id 는 부르는 쪽이 세션에서 얻어 넘긴다.
 */
@Service
class BirthProfileService(private val repository: BirthProfileRepository) {

	fun find(ownerId: UUID): BirthProfile? = repository.find(ownerId)

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
				placeId = rs.getInt("place_id"),
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
