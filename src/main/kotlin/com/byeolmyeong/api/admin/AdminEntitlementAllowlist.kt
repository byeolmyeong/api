package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID

/** 이메일 주소의 가장 긴 길이(RFC 5321 의 경로 한도). */
private const val EMAIL_MAX = 254

/** `@` 하나, 그 앞뒤에 공백 · `@` 가 없는 글자. 주소가 실제로 있는지는 보지 않는다(공급자가 준 이메일과 글자로 맞출 뿐이다). */
private val EMAIL = Regex("[^\\s@]+@[^\\s@]+")

/** 결제 없이 여는 계정 한 줄. [email] 은 소문자다. */
data class AllowlistEntry(val id: UUID, val email: String, val createdAt: String, val createdBy: AdminRef)

/**
 * 결제 없이 여는 계정(결정 056 의 9 · 앱 결정 101 의 5-2): 계정 이메일이 이 목록에 있으면 결제 시작 뒤에도 자세한 풀이를 받는다(판정은 `report` 가 표를 읽어 한다).
 * 운영자 · 팀원 · 테스트용. 애플 "이메일 가리기" 계정은 가려진 주소를 넣는다.
 */
@RestController
class AdminEntitlementAllowlistController(private val allowlist: AdminEntitlementAllowlistService, private val current: CurrentAdmin) {

	@GetMapping("/admin/entitlement-allowlist")
	fun list(): List<AllowlistEntry> = allowlist.list()

	/** 앞뒤 공백을 떼고 소문자로 둔다. 이미 있으면(대소문자 무관) 409 `ALREADY_LISTED`. */
	@PostMapping("/admin/entitlement-allowlist")
	fun add(@RequestBody body: JsonNode): ResponseEntity<AllowlistEntry> {
		val email = body.requireObject().textOrNull("email")?.trim()?.lowercase()
		if (email == null || NUL in email || email.codePointLength() > EMAIL_MAX || !EMAIL.matches(email)) throw BadRequestException("INVALID_EMAIL")
		return ResponseEntity.status(201).body(allowlist.add(current.get().id, email))
	}

	@DeleteMapping("/admin/entitlement-allowlist/{id}")
	fun remove(@PathVariable id: String): ResponseEntity<Void> {
		allowlist.remove(pathId(id))
		return ResponseEntity.noContent().build()
	}
}

/** 넣기 · 빼기는 감사 기록에 남긴다 — 이메일은 넣지 않고 줄의 id 만(결정 015). */
@Service
class AdminEntitlementAllowlistService(private val repository: AdminEntitlementAllowlistRepository, private val audit: AdminAudit) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun list(): List<AllowlistEntry> = repository.findAll()

	fun add(adminId: UUID, email: String): AllowlistEntry {
		val id = repository.insert(adminId, email) ?: throw AdminRejectedException(409, "ALREADY_LISTED")
		log.info("event=admin_entitlement_allowlist_added")
		audit.record(AuditAction.ENTITLEMENT_ALLOWLIST_ADDED, "ENTITLEMENT_ALLOWLIST", id)
		return checkNotNull(repository.findById(id))
	}

	fun remove(id: UUID) {
		if (!repository.delete(id)) throw NotFoundException("NOT_FOUND")
		log.info("event=admin_entitlement_allowlist_removed")
		audit.record(AuditAction.ENTITLEMENT_ALLOWLIST_REMOVED, "ENTITLEMENT_ALLOWLIST", id)
	}
}

/** `entitlement_allowlist`(V37). 판정 쪽(`report`)은 자기 SQL 로 읽기만 한다. */
@Repository
class AdminEntitlementAllowlistRepository(private val jdbc: JdbcTemplate) {

	private val select = """
		select l.id, l.email, l.created_at, l.created_by, a.name as creator_name
		from entitlement_allowlist l join admin_accounts a on a.id = l.created_by
	"""

	private val row = RowMapper { rs, _ ->
		AllowlistEntry(
			id = rs.getObject("id", UUID::class.java),
			email = rs.getString("email"),
			createdAt = rs.getObject("created_at", OffsetDateTime::class.java).utcSeconds(),
			createdBy = AdminRef(rs.getObject("created_by", UUID::class.java), rs.getString("creator_name")),
		)
	}

	fun findAll(): List<AllowlistEntry> = jdbc.query("$select order by l.created_at desc, l.id desc", row)

	fun findById(id: UUID): AllowlistEntry? = jdbc.query("$select where l.id = ?", row, id).firstOrNull()

	/** 넣은 줄의 id. 같은 이메일이 이미 있으면 null. */
	fun insert(adminId: UUID, email: String): UUID? {
		val id = UUID.randomUUID()
		return try {
			jdbc.update("insert into entitlement_allowlist (id, email, created_by) values (?, ?, ?)", id, email, adminId)
			id
		} catch (_: DuplicateKeyException) {
			null
		}
	}

	fun delete(id: UUID): Boolean = jdbc.update("delete from entitlement_allowlist where id = ?", id) == 1
}
