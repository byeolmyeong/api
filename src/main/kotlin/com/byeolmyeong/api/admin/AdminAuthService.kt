package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

private const val TOKEN_BYTES = 32
private const val LOGIN_WINDOW_MILLIS = 600_000L

/** 아이디별 제한의 키로 쓰는 길이. 긴 글로 메모리를 채우지 못하게 자른다. */
private const val LIMIT_KEY_LENGTH = 64

/** 로그인 결과. [token] 은 원문이고 이 응답에서만 나간다. */
class AdminLogin(val token: String, val expiresAt: OffsetDateTime, val admin: Admin)

/** 아이디는 앞뒤 공백을 떼고 소문자로 본다. */
internal fun normalizeLoginId(raw: String): String = raw.trim().lowercase(Locale.ROOT)

/**
 * 관리자 로그인·세션(결정 014). 앱 사용자의 `auth` 와 아무것도 나누지 않는다.
 * 세션 토큰은 원문을 돌려주기만 하고 DB 에는 해시만 둔다. 로그에는 이벤트 이름만 남긴다(아이디·비밀번호·토큰 없음).
 */
@Service
class AdminAuthService(
	private val accounts: AdminAccountRepository,
	private val sessions: AdminSessionRepository,
	private val passwords: AdminPasswords,
	private val events: ApplicationEventPublisher,
	private val audit: AdminAudit,
	@Value("\${byeolmyeong.admin.session-hours}") private val sessionHours: Int,
	@Value("\${byeolmyeong.rate-limit.admin-login-per-ip}") perIp: Int,
	@Value("\${byeolmyeong.rate-limit.admin-login-per-id}") perId: Int,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()
	private val ipLimiter = RateLimiter(perIp, LOGIN_WINDOW_MILLIS, System::currentTimeMillis)
	private val idLimiter = RateLimiter(perId, LOGIN_WINDOW_MILLIS, System::currentTimeMillis)

	/**
	 * 없는 아이디·틀린 비밀번호·꺼진 관리자는 모두 401 `INVALID_CREDENTIALS` 다. 시도는 주소별·아이디별로 센다(성공도 센다).
	 * 주소별 한도에 걸린 요청은 아이디별 수를 올리지 않는다.
	 */
	fun login(loginId: String, password: String, address: String): AdminLogin {
		val id = normalizeLoginId(loginId)
		if (!ipLimiter.tryAcquire(address) || !idLimiter.tryAcquire(id.take(LIMIT_KEY_LENGTH))) {
			log.warn("event=admin_login_rate_limited")
			throw AdminRejectedException(429, "RATE_LIMITED")
		}
		val account = accounts.findByLoginId(id)
		val matched = passwords.matches(password, account?.passwordHash)
		if (account == null || !matched || !account.active) {
			log.warn("event=admin_login_failed")
			// 누구인지도, 시도한 아이디도 남기지 않는다(아이디 자리에 비밀번호를 넣는 일이 있다).
			events.publishEvent(AuditEvent(AuditAction.ADMIN_LOGIN, AuditActor.SYSTEM, success = false))
			throw AdminRejectedException(401, "INVALID_CREDENTIALS")
		}
		sessions.deleteExpired()
		val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
		val expiresAt = sessions.insert(hash(token), account.id, sessionHours)
		log.info("event=admin_login_succeeded")
		events.publishEvent(AuditEvent(AuditAction.ADMIN_LOGIN, AuditActor.admin(account.id, account.loginId)))
		return AdminLogin(token, expiresAt, Admin(account.id, account.loginId, account.name))
	}

	/** 토큰의 관리자. 없는 토큰이거나 끝났거나 꺼진 관리자면 null. */
	fun adminOf(token: String): Admin? = sessions.findAdmin(hash(token))

	fun logout(token: String) {
		sessions.delete(hash(token))
		log.info("event=admin_logout")
		audit.record(AuditAction.ADMIN_LOGOUT)
	}

	/** 자기 비밀번호를 바꾼다. 이 요청의 세션([token])은 남기고 자기의 다른 세션은 지운다. 지금 비밀번호 확인도 아이디별 제한에 든다. */
	fun changePassword(admin: Admin, token: String, currentPassword: String, newPassword: String) {
		if (!idLimiter.tryAcquire(admin.loginId.take(LIMIT_KEY_LENGTH))) throw AdminRejectedException(429, "RATE_LIMITED")
		if (!passwords.matches(currentPassword, accounts.findByLoginId(admin.loginId)?.passwordHash)) {
			log.warn("event=admin_password_change_failed")
			throw BadRequestException("INVALID_CURRENT_PASSWORD")
		}
		if (!passwords.acceptable(newPassword)) throw BadRequestException("INVALID_PASSWORD")
		accounts.updatePassword(admin.id, passwords.hash(newPassword))
		sessions.deleteOthers(admin.id, hash(token))
		log.info("event=admin_password_changed")
		audit.record(AuditAction.ADMIN_PASSWORD_CHANGED)
	}

	private fun hash(token: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}

@Repository
class AdminSessionRepository(private val jdbc: JdbcTemplate) {

	/** 끝나는 때(지금 + [hours]시간)를 DB 시계로 정해 넣고 그 값을 준다. */
	fun insert(tokenHash: String, adminId: UUID, hours: Int): OffsetDateTime =
		jdbc.queryForObject(
			"insert into admin_sessions (token_hash, admin_id, expires_at) values (?, ?, now() + make_interval(hours => ?)) returning expires_at",
			OffsetDateTime::class.java, tokenHash, adminId, hours,
		)!!

	fun findAdmin(tokenHash: String): Admin? =
		jdbc.query(
			"""
			select a.id, a.login_id, a.name from admin_sessions s join admin_accounts a on a.id = s.admin_id
			where s.token_hash = ? and s.expires_at > now() and a.active
			""",
			{ rs, _ -> Admin(rs.getObject("id", UUID::class.java), rs.getString("login_id"), rs.getString("name")) },
			tokenHash,
		).firstOrNull()

	fun delete(tokenHash: String) {
		jdbc.update("delete from admin_sessions where token_hash = ?", tokenHash)
	}

	fun deleteExpired() {
		jdbc.update("delete from admin_sessions where expires_at <= now()")
	}

	fun deleteOthers(adminId: UUID, keepTokenHash: String) {
		jdbc.update("delete from admin_sessions where admin_id = ? and token_hash <> ?", adminId, keepTokenHash)
	}

	fun deleteAllOf(adminId: UUID) {
		jdbc.update("delete from admin_sessions where admin_id = ?", adminId)
	}
}
