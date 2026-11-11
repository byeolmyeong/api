package com.byeolmyeong.api.auth

import org.springframework.beans.factory.annotation.Value
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
	@Value("\${byeolmyeong.auth.dev-enabled}") private val devEnabled: Boolean,
) {
	private val random = SecureRandom()

	/** 개발용 로그인(결정 005). 꺼져 있으면 null. 켜져 있으면 개발용 계정을 찾거나 만들고 새 세션 토큰을 준다. */
	fun devLogin(): String? {
		if (!devEnabled) return null
		return issue(accounts.findOrCreate(DEV_PROVIDER, DEV_USER))
	}

	/** 토큰의 주인. 토큰이 없는 것이거나 계정이 지워졌으면 null. */
	fun ownerOf(token: String): UUID? = sessions.findAccountId(hash(token))

	/** 계정과 그 사람의 모든 행(세션·출생 정보·뒤에 생기는 글)을 지운다. */
	fun deleteAccount(ownerId: UUID) = accounts.delete(ownerId)

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

	fun findAccountId(tokenHash: String): UUID? =
		jdbc.queryForList("select account_id from sessions where token_hash = ?", UUID::class.java, tokenHash).firstOrNull()
}
