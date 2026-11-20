package com.byeolmyeong.api.audit

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActorType
import com.byeolmyeong.api.web.BadRequestException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

private const val SIZE_DEFAULT = 20
private const val SIZE_MAX = 100
private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** `from`·`to` 의 날짜는 한국 시간의 하루다. */
private val KOREA: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 감사 기록 조회(결정 015). 읽기 하나뿐이다 — 고치거나 지우는 경로가 없다.
 * 경로가 `/admin/` 아래라 관리자 세션 검사(`admin.AdminSessionInterceptor`)와 관리 화면의 CORS 가 걸린다. 이 조회 자체는 기록하지 않는다.
 */
@RestController
class AuditLogController(private val audit: AuditService) {

	/**
	 * 새것부터. 빈 글인 조건은 없는 것으로 본다. 틀린 조건은 `actorType` → `action` → 날짜 → 쪽 순서로 처음 걸린 것 하나를 답한다.
	 * `q` 는 틀릴 수 없다(앞뒤 공백을 떼고 대소문자를 가리지 않는 일부 맞춤).
	 */
	@GetMapping("/admin/audit-logs")
	fun list(
		@RequestParam(required = false) actorType: String?,
		@RequestParam(required = false) action: String?,
		@RequestParam(required = false) from: String?,
		@RequestParam(required = false) to: String?,
		@RequestParam(required = false) actorRef: String?,
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false) page: String?,
		@RequestParam(required = false) size: String?,
	): AuditLogPage {
		val type = actorType?.ifEmpty { null }
		if (type != null && AuditActorType.entries.none { it.name == type }) throw BadRequestException("INVALID_ACTOR_TYPE")
		val act = action?.ifEmpty { null }
		if (act != null && AuditAction.entries.none { it.name == act }) throw BadRequestException("INVALID_ACTION")
		// `from` 은 그날의 처음부터, `to` 는 그날의 끝까지(다음 날의 처음 전까지)다.
		val since = date(from)?.atStartOfDay(KOREA)?.toInstant()
		val until = date(to)?.plusDays(1)?.atStartOfDay(KOREA)?.toInstant()
		val pageNumber = if (page.isNullOrEmpty()) 0 else page.toIntOrNull()
		val pageSize = if (size.isNullOrEmpty()) SIZE_DEFAULT else size.toIntOrNull()
		if (pageNumber == null || pageNumber < 0 || pageSize == null || pageSize !in 1..SIZE_MAX) throw BadRequestException("INVALID_PAGE")
		val ref = actorRef?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }
		val search = q?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }
		return audit.list(AuditFilter(type, act, since, until, ref, search), pageNumber, pageSize)
	}

	/** `YYYY-MM-DD` 만 받는다. 없거나 빈 글이면 null, 모양이 틀리거나 없는 날이면 `INVALID_DATE`. */
	private fun date(raw: String?): LocalDate? {
		if (raw.isNullOrEmpty()) return null
		if (!DATE.matches(raw)) throw BadRequestException("INVALID_DATE")
		return try {
			LocalDate.parse(raw)
		} catch (_: DateTimeParseException) {
			throw BadRequestException("INVALID_DATE")
		}
	}
}

/**
 * 거르는 조건. null 인 것은 거르지 않는다. [since] 는 포함, [until] 은 포함하지 않는다.
 * [q] 는 소문자로 바꾼 검색어다: 관리자 아이디 일부 또는 (지금 있는) 앱 사용자 닉네임 일부(결정 043 의 8).
 */
class AuditFilter(val actorType: String?, val action: String?, val since: Instant?, val until: Instant?, val actorRef: String?, val q: String? = null)
