package com.byeolmyeong.api.notice

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 앱에 보이는 공지 한 개. [category] 는 `NOTICE` · `UPDATE`, [publishedAt] 은 UTC 초 단위. 상태 · 알림 · 쓴 관리자는 내보내지 않는다. */
data class Notice(val id: UUID, val category: String, val title: String, val body: String, val publishedAt: String)

/**
 * 공지사항의 공개 조회(결정 055 의 2). 세션 없이 읽는다.
 * 글은 관리자가 `/admin/notices` 로 쓴다(`admin` 패키지). 이 패키지는 `notices` 테이블을 읽기만 한다.
 */
@RestController
class NoticeController(private val notices: NoticeService) {

	/** 공개한 것만 공개 시각이 새것부터. 하나도 없으면 빈 배열. 5분 동안 캐시해도 된다. */
	@GetMapping("/notices")
	fun list(): ResponseEntity<List<Notice>> =
		ResponseEntity.ok().header("Cache-Control", "public, max-age=300").body(notices.published())
}

@Service
class NoticeService(private val repository: NoticeRepository) {

	fun published(): List<Notice> = repository.findPublished()
}

@Repository
class NoticeRepository(private val jdbc: JdbcTemplate) {

	fun findPublished(): List<Notice> =
		jdbc.query(
			"select id, category, title, body, published_at from notices where status = 'PUBLISHED' order by published_at desc, id desc",
		) { rs, _ ->
			Notice(
				id = rs.getObject("id", UUID::class.java),
				category = rs.getString("category"),
				title = rs.getString("title"),
				body = rs.getString("body"),
				publishedAt = rs.getObject("published_at", OffsetDateTime::class.java).toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
			)
		}
}
