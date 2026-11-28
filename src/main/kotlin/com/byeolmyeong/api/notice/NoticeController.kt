package com.byeolmyeong.api.notice

import com.byeolmyeong.api.web.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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

	/**
	 * 공개 중인 한 건(결정 055 의 2). 없거나 · 임시 저장이거나 · UUID 가 아니면 404 `NOT_FOUND`.
	 * 캐시하지 않는다 — 알림으로 막 공개된 글을 열 때 5분 캐시된 목록에 아직 없어도 열리게.
	 */
	@GetMapping("/notices/{id}")
	fun one(@PathVariable id: String): ResponseEntity<Notice> {
		val noticeId = runCatching { UUID.fromString(id) }.getOrNull()?.takeIf { it.toString() == id } ?: throw NotFoundException("NOT_FOUND")
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(notices.published(noticeId))
	}
}

@Service
class NoticeService(private val repository: NoticeRepository) {

	fun published(): List<Notice> = repository.findPublished()

	fun published(id: UUID): Notice = repository.findPublished(id) ?: throw NotFoundException("NOT_FOUND")
}

@Repository
class NoticeRepository(private val jdbc: JdbcTemplate) {

	private val row = RowMapper { rs, _ ->
		Notice(
			id = rs.getObject("id", UUID::class.java),
			category = rs.getString("category"),
			title = rs.getString("title"),
			body = rs.getString("body"),
			publishedAt = rs.getObject("published_at", OffsetDateTime::class.java).toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
		)
	}

	fun findPublished(): List<Notice> =
		jdbc.query("select id, category, title, body, published_at from notices where status = 'PUBLISHED' order by published_at desc, id desc", row)

	fun findPublished(id: UUID): Notice? =
		jdbc.query("select id, category, title, body, published_at from notices where id = ? and status = 'PUBLISHED'", row, id).firstOrNull()
}
