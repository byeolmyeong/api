package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

private val LOGIN_ID = Regex("[a-z0-9][a-z0-9._-]{2,31}")
private const val NAME_MAX = 40

/** 관리자 한 명(목록·만들기·켜고 끄기의 응답). 비밀번호 해시는 없다. */
data class AdminAccount(val id: UUID, val loginId: String, val name: String, val active: Boolean, val createdAt: String)

/** DB 의 관리자 행. 해시가 들어 있으므로 이 패키지의 서비스 밖으로 내보내지 않는다. */
class AdminAccountRow(val id: UUID, val loginId: String, val name: String, val active: Boolean, val createdAt: OffsetDateTime, val passwordHash: String) {
	fun toAccount() = AdminAccount(id, loginId, name, active, createdAt.utcSeconds())
}

/** 아이디 규칙: 소문자·숫자·`.`·`_`·`-` 3~32자, 첫 글자는 소문자나 숫자. [normalizeLoginId] 를 거친 값에 쓴다. */
internal fun validLoginId(loginId: String): Boolean = LOGIN_ID.matches(loginId)

/** 표시 이름 규칙: 앞뒤 공백을 뗀 1~40자(코드 포인트). */
internal fun validName(name: String): Boolean = name.codePointLength() in 1..NAME_MAX && NUL !in name

/**
 * 관리자 계정: 첫 관리자 만들기, 목록, 만들기, 켜고 끄기(결정 014). 역할 구분은 없다.
 * 로그에는 이벤트 이름과 사유 코드만 남긴다.
 */
@Service
class AdminAccountService(
	private val accounts: AdminAccountRepository,
	private val sessions: AdminSessionRepository,
	private val passwords: AdminPasswords,
	private val events: ApplicationEventPublisher,
	private val audit: AdminAudit,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * 관리자가 한 명도 없고 [loginId]·[password] 가 둘 다 있고 규칙에 맞을 때만 첫 관리자를 만든다. 만들었으면 true.
	 * 관리자가 이미 있으면 두 값을 보지 않는다.
	 */
	fun bootstrap(loginId: String, password: String): Boolean {
		if (accounts.count() > 0) return false
		val id = normalizeLoginId(loginId)
		if (id.isEmpty() || password.isEmpty()) {
			log.warn("event=admin_bootstrap_skipped reason=NOT_SET")
			return false
		}
		val problem = if (!validLoginId(id)) "INVALID_LOGIN_ID" else if (!passwords.acceptable(password)) "INVALID_PASSWORD" else null
		if (problem != null) {
			log.warn("event=admin_bootstrap_skipped reason={}", problem)
			return false
		}
		val created = accounts.insertIfNone(id, id, passwords.hash(password)) ?: return false
		log.info("event=admin_bootstrap_created")
		events.publishEvent(AuditEvent(AuditAction.ADMIN_BOOTSTRAPPED, AuditActor.SYSTEM, "ADMIN", created.toString()))
		return true
	}

	fun list(): List<AdminAccount> = accounts.findAll().map { it.toAccount() }

	/** [loginId]·[name]·[password] 는 검증을 거친 값이다. 이미 있는 아이디면 409 `LOGIN_ID_TAKEN`. */
	fun create(loginId: String, name: String, password: String): AdminAccount {
		val id = accounts.insert(loginId, name, passwords.hash(password)) ?: throw AdminRejectedException(409, "LOGIN_ID_TAKEN")
		log.info("event=admin_created")
		audit.record(AuditAction.ADMIN_CREATED, "ADMIN", id)
		return accounts.findById(id)!!.toAccount()
	}

	/**
	 * 관리자를 켜거나 끈다. 자기 자신은 끌 수 없고(409 `CANNOT_DEACTIVATE_SELF`), 마지막 남은 활성 관리자도 끌 수 없다
	 * (409 `LAST_ACTIVE_ADMIN` — 두 관리자가 동시에 서로를 끄는 경우). 끄면 그 관리자의 세션을 모두 지운다.
	 */
	@Transactional
	fun setActive(actorId: UUID, targetId: UUID, active: Boolean): AdminAccount {
		// 동시에 들어온 끄기 요청이 서로의 결과를 보도록 관리자 행을 모두 잠근다.
		val all = accounts.lockAll()
		val target = all.firstOrNull { it.id == targetId } ?: throw NotFoundException("NOT_FOUND")
		if (!active) {
			if (targetId == actorId) throw AdminRejectedException(409, "CANNOT_DEACTIVATE_SELF")
			if (target.active && all.count { it.active } <= 1) throw AdminRejectedException(409, "LAST_ACTIVE_ADMIN")
			sessions.deleteAllOf(targetId)
		}
		accounts.updateActive(targetId, active)
		log.info("event=admin_active_changed active={}", active)
		// 이미 그 상태였으면 바뀐 것이 없어 남기지 않는다. 이 트랜잭션이 커밋된 뒤에 기록된다.
		if (target.active != active) audit.record(if (active) AuditAction.ADMIN_REACTIVATED else AuditAction.ADMIN_DEACTIVATED, "ADMIN", targetId)
		return accounts.findById(targetId)!!.toAccount()
	}

	/**
	 * 다른 관리자의 비밀번호를 다시 정하고 그 관리자의 세션을 모두 지운다(결정 018). [password] 는 검증을 거친 값이다.
	 * 자기 자신에게는 쓸 수 없다(409 `USE_OWN_PASSWORD_CHANGE` — 자기 것은 지금 비밀번호를 확인하는 길로만 바꾼다).
	 * 꺼진 관리자에게도 되고 켜지지는 않는다. 비밀번호는 로그와 감사 기록에 남기지 않는다.
	 */
	@Transactional
	fun resetPassword(actorId: UUID, targetId: UUID, password: String) {
		if (targetId == actorId) throw AdminRejectedException(409, "USE_OWN_PASSWORD_CHANGE")
		accounts.findById(targetId) ?: throw NotFoundException("NOT_FOUND")
		accounts.updatePassword(targetId, passwords.hash(password))
		sessions.deleteAllOf(targetId)
		log.info("event=admin_password_reset")
		audit.record(AuditAction.ADMIN_PASSWORD_RESET, "ADMIN", targetId)
	}
}

@Repository
class AdminAccountRepository(private val jdbc: JdbcTemplate) {

	private val row = RowMapper { rs, _ ->
		AdminAccountRow(
			rs.getObject("id", UUID::class.java), rs.getString("login_id"), rs.getString("name"), rs.getBoolean("active"),
			rs.getObject("created_at", OffsetDateTime::class.java), rs.getString("password_hash"),
		)
	}

	fun count(): Int = jdbc.queryForObject("select count(*) from admin_accounts", Int::class.java)!!

	fun findAll(): List<AdminAccountRow> = jdbc.query("select * from admin_accounts order by created_at, id", row)

	fun lockAll(): List<AdminAccountRow> = jdbc.query("select * from admin_accounts order by id for update", row)

	fun findById(id: UUID): AdminAccountRow? = jdbc.query("select * from admin_accounts where id = ?", row, id).firstOrNull()

	fun findByLoginId(loginId: String): AdminAccountRow? = jdbc.query("select * from admin_accounts where login_id = ?", row, loginId).firstOrNull()

	/** 이미 있는 아이디면 넣지 않고 null. */
	fun insert(loginId: String, name: String, passwordHash: String): UUID? {
		val id = UUID.randomUUID()
		val inserted = jdbc.update(
			"insert into admin_accounts (id, login_id, name, password_hash) values (?, ?, ?, ?) on conflict (login_id) do nothing",
			id, loginId, name, passwordHash,
		)
		return id.takeIf { inserted == 1 }
	}

	/** 관리자가 한 명도 없을 때만 넣고 그 id 를 준다(서버 여러 대가 같이 떠도 첫 관리자는 하나). 넣지 않았으면 null. */
	fun insertIfNone(loginId: String, name: String, passwordHash: String): UUID? {
		val id = UUID.randomUUID()
		val inserted = jdbc.update(
			"""
			insert into admin_accounts (id, login_id, name, password_hash)
			select ?, ?, ?, ? where not exists (select 1 from admin_accounts)
			on conflict (login_id) do nothing
			""",
			id, loginId, name, passwordHash,
		)
		return id.takeIf { inserted == 1 }
	}

	fun updateActive(id: UUID, active: Boolean) {
		jdbc.update("update admin_accounts set active = ? where id = ?", active, id)
	}

	fun updatePassword(id: UUID, passwordHash: String) {
		jdbc.update("update admin_accounts set password_hash = ? where id = ?", passwordHash, id)
	}
}
