package com.byeolmyeong.api.auth

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

private const val DEV_PROVIDER = "DEV"
private const val DEV_USER = "dev"
private const val TOKEN_BYTES = 32

/**
 * 세션 발급·검증과 계정 삭제. 세션 토큰은 원문을 돌려주기만 하고 저장하지 않는다(DB 에는 해시만).
 * 토큰과 계정 식별자를 로그에 남기지 않는다.
 */
@Service
class AuthService(
	private val accounts: AccountRepository,
	private val sessions: SessionRepository,
	private val events: ApplicationEventPublisher,
	@Value("\${byeolmyeong.auth.dev-enabled}") private val devEnabled: Boolean,
	@Value("\${byeolmyeong.auth.session-days}") private val sessionDays: Int,
) {
	private val random = SecureRandom()

	/** 개발용 로그인(결정 005). 꺼져 있으면 null. 켜져 있으면 개발용 계정을 찾거나 만들고 새 세션 토큰을 준다. */
	fun devLogin(): String? {
		if (!devEnabled) return null
		val accountId = accounts.findOrCreate(DEV_PROVIDER, DEV_USER)
		val token = issue(accountId)
		events.publishEvent(AuditEvent(AuditAction.USER_LOGIN, AuditActor.user(accountId)))
		return token
	}

	/** 토큰의 주인. 토큰이 없는 것이거나 계정이 지워졌거나 마지막 사용 후 [sessionDays]일이 지났으면 null(결정 021). */
	fun ownerOf(token: String): UUID? = sessions.use(hash(token), sessionDays)

	/** 로그아웃. 이 세션만 지운다(다른 기기의 세션은 그대로). */
	fun logout(token: String) {
		sessions.delete(hash(token))
	}

	/** 계정과 그 사람의 모든 행(세션·출생 정보·뒤에 생기는 글)을 지운다. 감사 기록만 남는다. */
	fun deleteAccount(ownerId: UUID) {
		accounts.delete(ownerId)
		// 감사 기록은 계정과 함께 지워지지 않는다(결정 015). 지운 뒤에도 "이 계정이 지워졌다" 가 남는다.
		events.publishEvent(AuditEvent(AuditAction.ACCOUNT_DELETED, AuditActor.user(ownerId)))
	}

	private fun issue(accountId: UUID): String {
		val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
		sessions.insert(hash(token), accountId)
		return token
	}

	private fun hash(token: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}

@Repository
class AccountRepository(private val jdbc: JdbcTemplate) {

	/** 같은 공급자·사용자의 계정은 하나다. 동시에 불러도 하나만 생긴다. */
	fun findOrCreate(provider: String, providerUserId: String): UUID {
		jdbc.update(
			"insert into accounts (id, provider, provider_user_id) values (?, ?, ?) on conflict (provider, provider_user_id) do nothing",
			UUID.randomUUID(), provider, providerUserId,
		)
		return jdbc.queryForObject(
			"select id from accounts where provider = ? and provider_user_id = ?",
			UUID::class.java, provider, providerUserId,
		)!!
	}

	/** 세션·출생 정보 등은 테이블의 `on delete cascade` 로 함께 지워진다. */
	fun delete(id: UUID) {
		jdbc.update("delete from accounts where id = ?", id)
	}
}

@Repository
class SessionRepository(private val jdbc: JdbcTemplate) {

	fun insert(tokenHash: String, accountId: UUID) {
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", tokenHash, accountId)
	}

	/**
	 * 살아 있는 세션의 계정. 마지막 사용 후 [days]일이 지났으면 그 행을 지우고 null.
	 * 마지막 사용이 하루 넘게 지났을 때만 지금으로 고쳐 쓴다(요청마다 쓰지 않게).
	 */
	fun use(tokenHash: String, days: Int): UUID? {
		val row = jdbc.query(
			"""
			select account_id,
			       last_used_at < now() - make_interval(days => ?) as expired,
			       last_used_at < now() - interval '1 day' as stale
			from sessions where token_hash = ?
			""".trimIndent(),
			{ rs, _ -> Triple(rs.getObject("account_id", UUID::class.java), rs.getBoolean("expired"), rs.getBoolean("stale")) },
			days, tokenHash,
		).firstOrNull() ?: return null
		val (accountId, expired, stale) = row
		if (expired) {
			delete(tokenHash)
			return null
		}
		if (stale) jdbc.update("update sessions set last_used_at = now() where token_hash = ?", tokenHash)
		return accountId
	}

	fun delete(tokenHash: String) {
		jdbc.update("delete from sessions where token_hash = ?", tokenHash)
	}
}
