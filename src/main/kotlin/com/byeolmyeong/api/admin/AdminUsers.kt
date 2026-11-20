package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AccountDeletionRequested
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

private val KST = ZoneId.of("Asia/Seoul")
private val USER_STATUSES = setOf("ACTIVE", "SUSPENDED")
private val PROVIDERS = setOf("KAKAO", "APPLE", "GOOGLE")
private val PAGE_SIZES = setOf(10, 20, 50, 100)
private const val PAGE_SIZE_DEFAULT = 20
private const val RANGE_DAYS_MAX = 366
private const val REASON_MAX = 200
// 재생성의 종류(결정 043 의 1). 자세한 풀이는 detail_reports 의 kind 로 옮긴다. 별명은 실패가 저장되지 않아 늘 409.
private val REPORT_KINDS = mapOf("REPORT_ME" to "ME", "REPORT_TIMING" to "TIMING")
private val GENERATION_KINDS = setOf("NICKNAME") + REPORT_KINDS.keys

/** 자세한 풀이의 상태(결정 043 의 1). */
data class UserReports(val me: String, val timing: String)

/** 사용자 목록의 한 줄. 이메일은 서버가 가린 값뿐이다(원문은 어떤 관리자 응답에도 없다). */
data class UserRow(
	val id: UUID,
	val nickname: String?,
	val profileNo: Int?,
	val emailMasked: String?,
	val provider: String,
	val joinedAt: String,
	val lastSeenAt: String?,
	val reports: UserReports,
	val status: String,
)

data class UserPage(val items: List<UserRow>, val page: Int, val size: Int, val total: Int)

data class UserConsents(val termsVersion: String?, val privacyVersion: String?, val pushAt: String?, val marketingAt: String?)

/** 출생 정보는 있는지만 — 날짜 · 시각 · 장소 값은 주지 않는다. */
data class UserBirth(val registered: Boolean, val hasTime: Boolean, val hasPlace: Boolean)

data class UserGeneration(val kind: String, val status: String, val at: String?)

data class UserInquiry(val id: UUID, val category: String, val status: String, val createdAt: String)

data class UserActivity(val at: String, val action: String)

data class UserSuspension(val at: String, val by: AdminRef?, val reason: String)

/** 사용자 상세(결정 043 의 1). 기기 · 오늘의 하늘 알림은 푸시 묶음(결정 042)이 더한다. */
data class UserDetail(
	val id: UUID,
	val nickname: String?,
	val profileNo: Int?,
	val emailMasked: String?,
	val provider: String,
	val joinedAt: String,
	val lastSeenAt: String?,
	val reports: UserReports,
	val status: String,
	val consents: UserConsents,
	val birth: UserBirth,
	val generations: List<UserGeneration>,
	val inquiries: List<UserInquiry>,
	val recent: List<UserActivity>,
	val suspension: UserSuspension?,
)

/** 찾기 조건. 검증을 거친 값이다. [joinedFrom] · [joinedTo] 는 한국 날짜(끝 날짜 포함). */
class UserFilter(val query: String?, val status: String?, val provider: String?, val joinedFrom: LocalDate?, val joinedTo: LocalDate?)

/** 이메일 가리기(결정 043 의 1): 앞 4글자 + `****@도메인`. `@` 가 없으면 앞 4글자 + `****`. */
internal fun maskEmail(email: String): String {
	val at = email.lastIndexOf('@')
	val local = if (at < 0) email else email.substring(0, at)
	val visible = String(local.codePoints().limit(4).toArray(), 0, minOf(4, local.codePointCount(0, local.length)))
	return if (at < 0) "$visible****" else "$visible****${email.substring(at)}"
}

/** 사용자 관리(결정 043 의 1 · 044). 누가 했는지는 관리자 세션에서 얻는다. */
@RestController
class AdminUsersController(private val users: AdminUsersService, private val current: CurrentAdmin) {

	/** 가입이 늦은 것부터. 빈 값은 없는 것으로 본다. */
	@GetMapping("/admin/users")
	fun list(
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false) status: String?,
		@RequestParam(required = false) provider: String?,
		@RequestParam(required = false) joinedFrom: String?,
		@RequestParam(required = false) joinedTo: String?,
		@RequestParam(required = false) page: String?,
		@RequestParam(required = false) size: String?,
	): UserPage {
		val statusFilter = status?.ifEmpty { null }
		if (statusFilter != null && statusFilter !in USER_STATUSES) throw BadRequestException("INVALID_STATUS")
		val providerFilter = provider?.ifEmpty { null }
		if (providerFilter != null && providerFilter !in PROVIDERS) throw BadRequestException("INVALID_PROVIDER")
		val pageNumber = if (page.isNullOrEmpty()) 0 else page.toIntOrNull()
		val pageSize = if (size.isNullOrEmpty()) PAGE_SIZE_DEFAULT else size.toIntOrNull()
		if (pageNumber == null || pageNumber < 0 || pageSize == null || pageSize !in PAGE_SIZES) throw BadRequestException("INVALID_PAGE")
		val from = joinedFrom?.ifEmpty { null }?.let(::kstDate)
		val to = joinedTo?.ifEmpty { null }?.let(::kstDate)
		if (from != null && to != null && (to < from || ChronoUnit.DAYS.between(from, to) + 1 > RANGE_DAYS_MAX)) throw BadRequestException("INVALID_DATE_RANGE")
		val query = q?.trim()?.ifEmpty { null }
		if (query != null && NUL in query) return UserPage(emptyList(), pageNumber, pageSize, 0)
		return users.list(UserFilter(query, statusFilter, providerFilter, from, to), pageNumber, pageSize)
	}

	@GetMapping("/admin/users/{id}")
	fun get(@PathVariable id: String): UserDetail = users.get(pathId(id))

	@PostMapping("/admin/users/{id}/suspend")
	fun suspend(@PathVariable id: String, @RequestBody body: JsonNode): UserDetail = users.suspend(pathId(id), current.get().id, reason(body))

	@PostMapping("/admin/users/{id}/unsuspend")
	fun unsuspend(@PathVariable id: String, @RequestBody body: JsonNode): UserDetail = users.unsuspend(pathId(id), current.get().id, reason(body))

	/** 사용자가 요청했을 때만(사유에 요청 경로). 닉네임을 다시 적어 확인한다. 사유는 확인용이고 저장하지 않는다. */
	@DeleteMapping("/admin/users/{id}")
	fun delete(@PathVariable id: String, @RequestBody body: JsonNode): ResponseEntity<Void> {
		val userId = pathId(id)
		reason(body)
		val confirm = body.textOrNull("confirmNickname") ?: throw BadRequestException("CONFIRM_MISMATCH")
		users.delete(userId, confirm)
		return ResponseEntity.noContent().build()
	}

	@PostMapping("/admin/users/{id}/regenerate")
	fun regenerate(@PathVariable id: String, @RequestBody body: JsonNode): ResponseEntity<Void> {
		val userId = pathId(id)
		val kind = body.requireObject().textOrNull("kind")?.takeIf { it in GENERATION_KINDS } ?: throw BadRequestException("INVALID_KIND")
		users.regenerate(userId, kind)
		return ResponseEntity.accepted().build()
	}

	private fun reason(body: JsonNode): String {
		val reason = body.requireObject().textOrNull("reason")?.trim()
		if (reason.isNullOrEmpty() || reason.codePointLength() > REASON_MAX || NUL in reason) throw BadRequestException("INVALID_REASON")
		return reason
	}

	private fun kstDate(raw: String): LocalDate =
		try {
			LocalDate.parse(raw).takeIf { it.toString() == raw }
		} catch (_: DateTimeParseException) {
			null
		} ?: throw BadRequestException("INVALID_DATE_RANGE")
}

@Service
class AdminUsersService(
	private val repository: AdminUsersRepository,
	private val events: ApplicationEventPublisher,
	private val audit: AdminAudit,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun list(filter: UserFilter, page: Int, size: Int): UserPage =
		UserPage(repository.find(filter, page.toLong() * size, size), page, size, repository.count(filter))

	fun get(id: UUID): UserDetail = repository.detail(id) ?: throw NotFoundException("NOT_FOUND")

	/** 이미 정지면 409 `ALREADY_SUSPENDED`. 세션은 그대로다(문의할 길 — 결정 044). */
	@Transactional
	fun suspend(id: UUID, adminId: UUID, reason: String): UserDetail {
		if (!repository.suspend(id, adminId, reason)) {
			if (!repository.exists(id)) throw NotFoundException("NOT_FOUND")
			throw AdminRejectedException(409, "ALREADY_SUSPENDED")
		}
		repository.recordSuspension(id, "SUSPEND", reason, adminId)
		log.info("event=admin_user_suspended")
		audit.record(AuditAction.USER_SUSPENDED, "USER", id)
		return get(id)
	}

	/** 정지가 아니면 409 `NOT_SUSPENDED`. 해제 사유는 정지 내역에 남는다. */
	@Transactional
	fun unsuspend(id: UUID, adminId: UUID, reason: String): UserDetail {
		if (!repository.unsuspend(id)) {
			if (!repository.exists(id)) throw NotFoundException("NOT_FOUND")
			throw AdminRejectedException(409, "NOT_SUSPENDED")
		}
		repository.recordSuspension(id, "UNSUSPEND", reason, adminId)
		log.info("event=admin_user_unsuspended")
		audit.record(AuditAction.USER_UNSUSPENDED, "USER", id)
		return get(id)
	}

	/** 닉네임이 다르면 400 `CONFIRM_MISMATCH`. 지우는 일은 `auth` 가 앱의 계정 삭제와 같이 한다(이벤트). */
	fun delete(id: UUID, confirmNickname: String) {
		val nickname = repository.nicknameOf(id) ?: throw NotFoundException("NOT_FOUND")
		if (nickname.value == null || nickname.value != confirmNickname.trim()) throw BadRequestException("CONFIRM_MISMATCH")
		events.publishEvent(AccountDeletionRequested(id))
		log.info("event=admin_user_deleted")
		audit.record(AuditAction.USER_DELETED_BY_ADMIN, "USER", id)
	}

	/**
	 * 가장 최근 생성이 실패(FAILED)한 나 탭 풀이 · 시기 풀이만 다시 시작한다. 아니면(별명 포함) 409 `NOT_FAILED`.
	 * 다시 만드는 일은 `report` 가 한다(이벤트, 요청 스레드) — 사용자의 생성 한도에 걸리면 429 가 그대로 간다.
	 */
	fun regenerate(id: UUID, kind: String) {
		if (!repository.exists(id)) throw NotFoundException("NOT_FOUND")
		val report = REPORT_KINDS[kind]
		if (report == null || repository.latestReportStatus(id, report) != "FAILED") throw AdminRejectedException(409, "NOT_FAILED")
		events.publishEvent(GenerationRetryRequested(id, report))
		log.info("event=admin_generation_retried kind={}", kind)
		audit.record(AuditAction.GENERATION_RETRIED, "USER", id, mapOf("kind" to kind))
	}
}

/** 있는 계정의 닉네임(없을 수 있다). */
class Nickname(val value: String?)

/**
 * 사용자 관리가 읽는 것. `admin` 은 다른 기능 패키지의 코드를 부를 수 없어 자기 SQL 로 읽는다(결정 014).
 * 이메일 원문은 읽어서 가린 값만 내보내고, 출생 정보는 있는지만 본다.
 */
@Repository
class AdminUsersRepository(private val jdbc: JdbcTemplate) {

	private val rowSelect = """
		select a.id, a.nickname, a.avatar, a.email, a.provider, a.created_at, a.last_seen_at, a.suspended_at,
		       (select d.status from detail_reports d where d.owner_id = a.id and d.kind = 'ME' order by d.started_at desc limit 1) as me_status,
		       (select d.status from detail_reports d where d.owner_id = a.id and d.kind = 'TIMING' order by d.started_at desc limit 1) as timing_status
		from accounts a
	""".trimIndent()

	private val filterSql = """
		where (cast(? as text) is null or position(lower(?) in lower(coalesce(a.nickname, ''))) > 0 or starts_with(lower(coalesce(a.email, '')), lower(?)))
		  and (cast(? as text) is null or (? = 'SUSPENDED') = (a.suspended_at is not null))
		  and (cast(? as text) is null or a.provider = ?)
		  and (cast(? as timestamptz) is null or a.created_at >= ?)
		  and (cast(? as timestamptz) is null or a.created_at < ?)
	""".trimIndent()

	private fun filterArgs(f: UserFilter): Array<Any?> {
		val from = f.joinedFrom?.atStartOfDay(KST)?.toOffsetDateTime()
		val to = f.joinedTo?.plusDays(1)?.atStartOfDay(KST)?.toOffsetDateTime()
		return arrayOf(f.query, f.query, f.query, f.status, f.status, f.provider, f.provider, from, from, to, to)
	}

	private fun reportStatus(raw: String?): String = when (raw) {
		null -> "NONE"
		"WRITING" -> "GENERATING"
		else -> raw
	}

	private val row = RowMapper { rs, _ ->
		UserRow(
			id = rs.getObject("id", UUID::class.java),
			nickname = rs.getString("nickname"),
			profileNo = (rs.getObject("avatar") as Number?)?.toInt(),
			emailMasked = rs.getString("email")?.let(::maskEmail),
			provider = rs.getString("provider"),
			joinedAt = rs.getObject("created_at", OffsetDateTime::class.java).utcSeconds(),
			lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java)?.utcSeconds(),
			reports = UserReports(reportStatus(rs.getString("me_status")), reportStatus(rs.getString("timing_status"))),
			status = if (rs.getObject("suspended_at") == null) "ACTIVE" else "SUSPENDED",
		)
	}

	fun find(filter: UserFilter, offset: Long, limit: Int): List<UserRow> =
		jdbc.query("$rowSelect $filterSql order by a.created_at desc, a.id desc limit ? offset ?", row, *filterArgs(filter), limit, offset)

	fun count(filter: UserFilter): Int =
		jdbc.queryForObject("select count(*) from accounts a $filterSql", Int::class.java, *filterArgs(filter))!!

	fun exists(id: UUID): Boolean = jdbc.queryForObject("select exists(select 1 from accounts where id = ?)", Boolean::class.java, id) == true

	fun nicknameOf(id: UUID): Nickname? = jdbc.query("select nickname from accounts where id = ?", { rs, _ -> Nickname(rs.getString("nickname")) }, id).firstOrNull()

	fun detail(id: UUID): UserDetail? {
		val base = jdbc.query("$rowSelect where a.id = ?", row, id).firstOrNull() ?: return null
		val consents = jdbc.queryForObject(
			"select terms_version, privacy_version, push_consented_at, marketing_consented_at from accounts where id = ?",
			{ rs, _ ->
				UserConsents(
					rs.getString("terms_version"), rs.getString("privacy_version"),
					rs.getObject("push_consented_at", OffsetDateTime::class.java)?.utcSeconds(),
					rs.getObject("marketing_consented_at", OffsetDateTime::class.java)?.utcSeconds(),
				)
			},
			id,
		)!!
		val birth = jdbc.query(
			"select birth_time is not null as has_time, place_id is not null as has_place from profiles where account_id = ?",
			{ rs, _ -> UserBirth(true, rs.getBoolean("has_time"), rs.getBoolean("has_place")) },
			id,
		).firstOrNull() ?: UserBirth(false, false, false)
		return UserDetail(
			base.id, base.nickname, base.profileNo, base.emailMasked, base.provider, base.joinedAt, base.lastSeenAt, base.reports, base.status,
			consents, birth, generations(id), inquiries(id), recent(id), suspension(id),
		)
	}

	/** 그 사람의 [kind](ME · TIMING) 가장 최근 생성의 상태(상세의 generations 와 같은 줄). 없으면 null. */
	fun latestReportStatus(id: UUID, kind: String): String? =
		jdbc.query(
			"select status from detail_reports where owner_id = ? and kind = ? order by started_at desc limit 1",
			{ rs, _ -> rs.getString("status") },
			id, kind,
		).firstOrNull()

	/** 별명(요약 해석) · 나 탭 풀이 · 시기 풀이의 지금 상태. 별명은 저장되는 실패가 없어 DONE · NONE 뿐이다. */
	private fun generations(id: UUID): List<UserGeneration> {
		val nickname = jdbc.query(
			"select max(created_at) as at from readings where owner_id = ? and kind = 'SUMMARY_V2' having count(*) > 0",
			{ rs, _ -> rs.getObject("at", OffsetDateTime::class.java) },
			id,
		).firstOrNull()
		fun report(kind: String, name: String): UserGeneration =
			jdbc.query(
				"select status, updated_at from detail_reports where owner_id = ? and kind = ? order by started_at desc limit 1",
				{ rs, _ -> UserGeneration(name, reportStatus(rs.getString("status")), rs.getObject("updated_at", OffsetDateTime::class.java).utcSeconds()) },
				id, kind,
			).firstOrNull() ?: UserGeneration(name, "NONE", null)
		return listOf(
			UserGeneration("NICKNAME", if (nickname == null) "NONE" else "DONE", nickname?.utcSeconds()),
			report("ME", "REPORT_ME"),
			report("TIMING", "REPORT_TIMING"),
		)
	}

	private fun inquiries(id: UUID): List<UserInquiry> = jdbc.query(
		"select id, category, status, created_at from inquiries where owner_id = ? order by created_at desc, id desc limit 20",
		{ rs, _ ->
			UserInquiry(rs.getObject("id", UUID::class.java), rs.getString("category"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime::class.java).utcSeconds())
		},
		id,
	)

	/** 그 사람이 한 일과 그 사람에게 관리자가 한 일(감사 기록), 새것부터 20개. */
	private fun recent(id: UUID): List<UserActivity> = jdbc.query(
		"""
		select occurred_at, action from audit_logs
		where (actor_type = 'USER' and actor_id = ?) or (target_type = 'USER' and target_id = ?)
		order by occurred_at desc, id desc limit 20
		""".trimIndent(),
		{ rs, _ -> UserActivity(rs.getObject("occurred_at", OffsetDateTime::class.java).utcSeconds(), rs.getString("action")) },
		id.toString(), id.toString(),
	)

	private fun suspension(id: UUID): UserSuspension? = jdbc.query(
		"""
		select a.suspended_at, a.suspension_reason, b.id as by_id, b.name as by_name
		from accounts a left join admin_accounts b on b.id = a.suspended_by
		where a.id = ? and a.suspended_at is not null
		""".trimIndent(),
		{ rs, _ ->
			val by = rs.getObject("by_id", UUID::class.java)?.let { AdminRef(it, rs.getString("by_name")) }
			UserSuspension(rs.getObject("suspended_at", OffsetDateTime::class.java).utcSeconds(), by, rs.getString("suspension_reason") ?: "")
		},
		id,
	).firstOrNull()

	/** 정지했으면 true(이미 정지면 false). */
	fun suspend(id: UUID, adminId: UUID, reason: String): Boolean = jdbc.update(
		"update accounts set suspended_at = now(), suspended_by = ?, suspension_reason = ? where id = ? and suspended_at is null",
		adminId, reason, id,
	) > 0

	/** 풀었으면 true(정지가 아니면 false). */
	fun unsuspend(id: UUID): Boolean = jdbc.update(
		"update accounts set suspended_at = null, suspended_by = null, suspension_reason = null where id = ? and suspended_at is not null",
		id,
	) > 0

	fun recordSuspension(id: UUID, action: String, reason: String, adminId: UUID) {
		jdbc.update("insert into account_suspensions (id, account_id, action, reason, admin_id) values (?, ?, ?, ?, ?)", UUID.randomUUID(), id, action, reason, adminId)
	}
}
