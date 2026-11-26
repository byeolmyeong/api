package com.byeolmyeong.api.auth

import com.byeolmyeong.api.web.AccountDeletionRequested
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.BadRequestException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import org.springframework.transaction.support.TransactionTemplate

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
	private val appleTokens: AppleTokenRepository,
	private val apple: AppleSignIn,
	private val kakao: KakaoOAuth,
	private val events: ApplicationEventPublisher,
	private val tx: TransactionTemplate,
	@Value("\${byeolmyeong.auth.dev-enabled}") private val devEnabled: Boolean,
	@Value("\${byeolmyeong.auth.session-days}") private val sessionDays: Int,
) {
	private val random = SecureRandom()
	private val log = LoggerFactory.getLogger(javaClass)

	/** 개발용 로그인(결정 005). 꺼져 있으면 null. 켜져 있으면 개발용 계정을 찾거나 만들고 새 세션 토큰을 준다. */
	fun devLogin(): String? {
		if (!devEnabled) return null
		val accountId = accounts.findOrCreate(DEV_PROVIDER, DEV_USER)
		val token = issueSession(accountId)
		events.publishEvent(AuditEvent(AuditAction.USER_LOGIN, AuditActor.user(accountId)))
		return token
	}

	/**
	 * 토큰의 주인과 정지 여부(결정 044 의 4 — 세션을 확인하는 같은 쿼리에서 읽는다).
	 * 토큰이 없는 것이거나 계정이 지워졌거나 마지막 사용 후 [sessionDays]일이 지났으면 null(결정 021).
	 */
	fun sessionOf(token: String): SessionOwner? = sessions.use(hash(token), sessionDays)

	/** 로그아웃. 이 세션만 지운다(다른 기기의 세션은 그대로). */
	fun logout(token: String) {
		sessions.delete(hash(token))
	}

	/**
	 * 계정과 그 사람의 모든 행(세션·출생 정보·뒤에 생기는 글)을 지운다. 감사 기록만 남는다.
	 * 지우기 전에 공급자 연결을 정리한다(결정 021): 애플은 refresh token 철회, 카카오는 연결 끊기.
	 * 실패해도 계정은 지운다 — 사용자는 지웠다고 믿는다(클라이언트가 로그를 남긴다).
	 */
	/** 계정 화면의 값(결정 025). 세션의 주인만 부른다. */
	fun account(ownerId: UUID): AccountResponse = checkNotNull(accounts.account(ownerId)) { "세션의 계정이 없다" }

	/**
	 * 로그인 중 닉네임 확인(결정 051). 규칙은 가입 중 확인 · 바꾸기와 같다 — 틀리면 400 `INVALID_NICKNAME`, 영문 대소문자는 같은 이름,
	 * 내 지금 닉네임(대소문자만 다른 것 포함)이면 쓸 수 있다.
	 */
	fun nicknameAvailable(ownerId: UUID, nickname: String?): Boolean {
		val name = normalizeNickname(nickname) ?: throw BadRequestException("INVALID_NICKNAME")
		return !accounts.nicknameTakenByOther(ownerId, name)
	}

	/**
	 * 보낸 칸만 바꾼다(결정 025 · 027 · 028 · 042). 닉네임이 틀리면 400 `INVALID_NICKNAME`, 프로필이 1~8 밖이면 400 `INVALID_AVATAR`,
	 * 받는 시간이 5분 단위 `HH:mm` 이 아니면 400 `INVALID_TODAY_SKY_TIME`, 닉네임을 다른 계정이 쓰면 409 `NICKNAME_TAKEN` — 모두 아무것도 바꾸지 않는다.
	 * 동의(마케팅 · 푸시)가 바뀔 때만 감사 기록(동의/철회만, 닉네임·이메일 없이). 프로필 · 오늘의 하늘 알림 바꾸기는 남기지 않는다.
	 */
	fun updateAccount(ownerId: UUID, patch: AccountPatchRequest): AccountResponse {
		val name = patch.nickname?.let { normalizeNickname(it) ?: throw BadRequestException("INVALID_NICKNAME") }
		val picture = patch.avatar?.let(::requireAvatar)
		val skyTime = patch.todaySkyTime?.let { parseTodaySkyTime(it) ?: throw BadRequestException("INVALID_TODAY_SKY_TIME") }
		val marketingConsent = patch.marketingConsent
		val pushConsent = patch.pushConsent
		tx.execute {
			name?.let { accounts.updateNickname(ownerId, it) }
			picture?.let { accounts.updateAvatar(ownerId, it) }
			if (patch.todaySkyPush != null || skyTime != null) accounts.updateTodaySky(ownerId, patch.todaySkyPush, skyTime)
			if (marketingConsent != null && accounts.setMarketingConsent(ownerId, marketingConsent)) {
				events.publishEvent(AuditEvent(AuditAction.MARKETING_CONSENT_CHANGED, AuditActor.user(ownerId), detail = mapOf("consent" to marketingConsent.toString())))
			}
			if (pushConsent != null && accounts.setPushConsent(ownerId, pushConsent)) {
				events.publishEvent(AuditEvent(AuditAction.PUSH_CONSENT_CHANGED, AuditActor.user(ownerId), detail = mapOf("consent" to pushConsent.toString())))
			}
		}
		return account(ownerId)
	}

	/** 관리자의 탈퇴 대행(결정 043 의 1). 앱의 계정 삭제와 같이 지우고, 감사 기록은 관리자 쪽(`USER_DELETED_BY_ADMIN`)만 남긴다. */
	@EventListener
	fun onDeletionRequested(event: AccountDeletionRequested) = deleteAccount(event.accountId, byUser = false)

	fun deleteAccount(ownerId: UUID, byUser: Boolean = true) {
		cleanUpProvider("apple_revoke_failed") { appleTokens.find(ownerId)?.let { apple.revoke(it.refreshToken, it.clientId) } }
		cleanUpProvider("kakao_unlink_failed") {
			accounts.identity(ownerId)?.takeIf { it.provider == Provider.KAKAO }?.let { kakao.unlink(it.subject) }
		}
		accounts.delete(ownerId)
		// 감사 기록은 계정과 함께 지워지지 않는다(결정 015). 지운 뒤에도 "이 계정이 지워졌다" 가 남는다.
		if (byUser) events.publishEvent(AuditEvent(AuditAction.ACCOUNT_DELETED, AuditActor.user(ownerId)))
	}

	/**
	 * 애플 서버 알림(결정 021). 사용자가 애플 쪽에서 이 앱의 사용을 멈췄으면(`consent-revoked`) 그 계정의 세션을 모두 지우고,
	 * 애플 계정을 지웠으면(`account-deleted`, 문서에 따라 `account-delete`) 계정을 지운다. 모르는 사용자 · 다른 종류는 아무것도 하지 않는다.
	 * 같은 알림이 여러 번 와도 결과가 같다.
	 */
	fun appleNotification(payload: String) {
		val event = apple.notification(payload)
		log.info("event=apple_notification type={}", event.type)
		val accountId = accounts.find(Provider.APPLE.name, event.subject) ?: return
		when (event.type) {
			// 알림보다 먼저 생긴 세션만 지운다 — 같은 알림이 늦게 다시 와도 그 뒤에 로그인한 세션은 남는다.
			"consent-revoked" -> sessions.deleteAll(accountId, createdBefore = event.eventTime)
			"account-deleted", "account-delete" -> {
				// 애플 계정이 없어졌으니 철회는 부르지 않는다. 사용자가 아니라 서버가 지운 것이다.
				accounts.delete(accountId)
				events.publishEvent(
					AuditEvent(AuditAction.ACCOUNT_DELETED, AuditActor.SYSTEM, "ACCOUNT", accountId.toString(), detail = mapOf("reason" to "apple-account-deleted")),
				)
			}
		}
	}

	/** 탈퇴 때 공급자 쪽 정리. 예상 밖 예외가 나도 계정 삭제는 계속한다(사용자는 지웠다고 믿는다). */
	private inline fun cleanUpProvider(event: String, block: () -> Unit) {
		try {
			block()
		} catch (e: Exception) {
			log.warn("event={} reason={}", event, e.javaClass.simpleName)
		}
	}

	/** 새 세션을 만들고 토큰 원문을 돌려준다(DB 에는 해시만). 개발용 로그인과 소셜 로그인 · 가입(`LoginService`)이 쓴다. */
	fun issueSession(accountId: UUID): String {
		val token = newToken()
		sessions.insert(hash(token), accountId)
		return token
	}

	/** 추측할 수 없는 토큰(32바이트, URL 에 안전한 Base64). 세션 토큰과 가입권이 쓴다. */
	fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

	/** 토큰을 DB 에 둘 때의 모양(SHA-256 16진수). */
	fun hash(token: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}

@Repository
class AccountRepository(private val jdbc: JdbcTemplate) {

	/** 계정 화면의 값(결정 025 · 027 · 028). 없으면 null. 닉네임·이메일은 로그에 남기지 않는다. */
	fun account(id: UUID): AccountResponse? = jdbc.query(
		"""
		select nickname, email, provider, marketing_consented_at, push_consented_at, avatar, suspended_at is not null as suspended,
		       coalesce(today_sky_push, push_consented_at is not null) as today_sky_on, to_char(today_sky_time, 'HH24:MI') as today_sky_time
		from accounts where id = ?
		""".trimIndent(),
		{ rs, _ ->
			val consentedAt = rs.getTimestamp("marketing_consented_at")?.toInstant()
			val pushAt = rs.getTimestamp("push_consented_at")?.toInstant()
			AccountResponse(
				rs.getString("nickname"), rs.getString("email"), rs.getString("provider"), consentedAt != null, consentedAt,
				pushAt != null, pushAt, (rs.getObject("avatar") as Number?)?.toInt(), rs.getBoolean("suspended"),
				rs.getBoolean("today_sky_on"), rs.getString("today_sky_time"),
			)
		},
		id,
	).firstOrNull()

	fun updateNickname(id: UUID, nickname: String) {
		if (nicknameTakenByOther(id, nickname)) throw NicknameTakenException()
		nicknameUnique { jdbc.update("update accounts set nickname = ? where id = ?", nickname, id) }
	}

	/** 다른 계정이 이 닉네임을 쓰는가(영문 대소문자는 같게 본다, 결정 025). */
	fun nicknameTaken(nickname: String): Boolean =
		jdbc.queryForObject("select exists(select 1 from accounts where lower(nickname) = lower(?))", Boolean::class.java, nickname) == true

	/** 다른 계정이 이 닉네임을 쓰는가. 내 행은 빼고 본다(내 닉네임의 대소문자만 바꾸는 건 된다). */
	fun nicknameTakenByOther(id: UUID, nickname: String): Boolean = jdbc.queryForObject(
		"select exists(select 1 from accounts where lower(nickname) = lower(?) and id <> ?)", Boolean::class.java, nickname, id,
	) == true

	/**
	 * 닉네임 유일 색인에 걸리면 409 로 바꾼다. 다른 제약 위반은 그대로 둔다.
	 * 바꾸기 전에 [nicknameTakenByOther] 로 먼저 묻는다 — 색인 위반은 DB 서버 로그(DETAIL)에 닉네임 값을 남기므로,
	 * 색인은 동시에 같은 이름이 들어오는 드문 경우만 막는다.
	 */
	private fun <T> nicknameUnique(block: () -> T): T = try {
		block()
	} catch (e: org.springframework.dao.DuplicateKeyException) {
		if (e.message?.contains(NICKNAME_UNIQUE_INDEX) == true) throw NicknameTakenException() else throw e
	}

	/** 동의를 켜면 지금, 끄면 null. 이미 그 값이면 바꾸지 않고 false(일시가 그대로 남는다). */
	fun setMarketingConsent(id: UUID, consent: Boolean): Boolean =
		if (consent) {
			jdbc.update("update accounts set marketing_consented_at = now() where id = ? and marketing_consented_at is null", id) > 0
		} else {
			jdbc.update("update accounts set marketing_consented_at = null where id = ? and marketing_consented_at is not null", id) > 0
		}

	/** 푸시 수신 동의를 켜면 지금, 끄면 null(결정 027). 이미 그 값이면 바꾸지 않고 false(일시가 그대로 남는다). */
	fun setPushConsent(id: UUID, consent: Boolean): Boolean =
		if (consent) {
			jdbc.update("update accounts set push_consented_at = now() where id = ? and push_consented_at is null", id) > 0
		} else {
			jdbc.update("update accounts set push_consented_at = null where id = ? and push_consented_at is not null", id) > 0
		}

	/** 오늘의 하늘 알림(결정 042 의 3). null 인 칸은 그대로 둔다. */
	fun updateTodaySky(id: UUID, on: Boolean?, time: java.time.LocalTime?) {
		jdbc.update(
			"update accounts set today_sky_push = coalesce(?::boolean, today_sky_push), today_sky_time = coalesce(?::time, today_sky_time) where id = ?",
			on, time, id,
		)
	}

	/** 프로필 번호(1~8, 결정 028)를 바꾼다. */
	fun updateAvatar(id: UUID, avatar: Int) {
		jdbc.update("update accounts set avatar = ? where id = ?", avatar, id)
	}

	/** 계정의 공급자와 공급자의 사용자 식별자. 없으면 null. */
	fun identity(id: UUID): ProviderIdentity? = jdbc.query(
		"select provider, provider_user_id from accounts where id = ?",
		{ rs, _ -> ProviderIdentity(Provider.valueOf(rs.getString("provider")), rs.getString("provider_user_id")) },
		id,
	).firstOrNull()

	/** 계정의 이메일을 고친다(결정 024). 값은 로그에 남기지 않는다. */
	fun updateEmail(id: UUID, email: String) {
		jdbc.update("update accounts set email = ? where id = ? and email is distinct from ?", email, id, email)
	}

	/** 가입할 때 닉네임과 마케팅 수신 동의 일시(null = 동의 안 함)를 둔다(결정 025). 값은 로그에 남기지 않는다. */
	fun setNicknameAndConsent(id: UUID, nickname: String, marketingConsentedAt: java.time.Instant?) {
		if (nicknameTakenByOther(id, nickname)) throw NicknameTakenException()
		nicknameUnique {
			jdbc.update(
				"update accounts set nickname = ?, marketing_consented_at = ? where id = ?",
				nickname, marketingConsentedAt?.let { java.sql.Timestamp.from(it) }, id,
			)
		}
	}

	/**
	 * 가입할 때 동의한 약관 · 처리방침 버전과 그 시각(서버 시각), 푸시 수신 동의 일시(null = 동의 안 함), 프로필 번호를 둔다(결정 026 · 027 · 028).
	 * 값은 로그에 남기지 않는다.
	 */
	fun setTermsPushAvatar(id: UUID, termsVersion: String, privacyVersion: String, agreedAt: java.time.Instant, pushConsentedAt: java.time.Instant?, avatar: Int) {
		val agreed = java.sql.Timestamp.from(agreedAt)
		jdbc.update(
			"""
			update accounts set terms_version = ?, terms_agreed_at = ?, privacy_version = ?, privacy_agreed_at = ?, push_consented_at = ?, avatar = ?
			where id = ?
			""".trimIndent(),
			termsVersion, agreed, privacyVersion, agreed, pushConsentedAt?.let { java.sql.Timestamp.from(it) }, avatar, id,
		)
	}

	/** 공급자·사용자의 계정. 없으면 null. */
	fun find(provider: String, providerUserId: String): UUID? = jdbc.queryForList(
		"select id from accounts where provider = ? and provider_user_id = ?", UUID::class.java, provider, providerUserId,
	).firstOrNull()

	/**
	 * 새 계정을 넣고 그 id 를 준다. 같은 공급자·사용자의 계정이 이미 있으면 넣지 않고 null — 다른 트랜잭션이 넣고 아직 커밋하지 않았으면
	 * 그 결과를 기다렸다가 판정한다. 그래서 동시에 불러도 id 를 받는 쪽은 하나다.
	 */
	fun create(provider: String, providerUserId: String): UUID? = jdbc.query(
		"insert into accounts (id, provider, provider_user_id) values (?, ?, ?) on conflict (provider, provider_user_id) do nothing returning id",
		{ rs, _ -> rs.getObject("id", UUID::class.java) },
		UUID.randomUUID(), provider, providerUserId,
	).firstOrNull()

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

/** 세션의 주인. [suspended] 면 열린 경로 말고 403 `ACCOUNT_SUSPENDED`(결정 044). */
data class SessionOwner(val accountId: UUID, val suspended: Boolean)

/** 세션 확인 한 번에 읽는 값. */
private class SessionUse(val accountId: UUID, val expired: Boolean, val stale: Boolean, val unseen: Boolean, val suspended: Boolean)

@Repository
class SessionRepository(private val jdbc: JdbcTemplate) {

	fun insert(tokenHash: String, accountId: UUID) {
		jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", tokenHash, accountId)
	}

	/**
	 * 살아 있는 세션의 계정. 마지막 사용 후 [days]일이 지났으면 그 행을 지우고 null.
	 * 마지막 사용이 하루 넘게 지났을 때만 지금으로 고쳐 쓴다(요청마다 쓰지 않게).
	 * 계정의 마지막 접속은 10분이 지났거나 한국 날짜가 바뀌었을 때만 쓰고, 그때 그날의 접속 줄을 넣는다(결정 043 의 2).
	 */
	fun use(tokenHash: String, days: Int): SessionOwner? {
		val row = jdbc.query(
			"""
			select s.account_id,
			       s.last_used_at < now() - make_interval(days => ?) as expired,
			       s.last_used_at < now() - interval '1 day' as stale,
			       a.last_seen_at is null
			         or a.last_seen_at < now() - interval '10 minutes'
			         or (a.last_seen_at at time zone 'Asia/Seoul')::date <> (now() at time zone 'Asia/Seoul')::date as unseen,
			       a.suspended_at is not null as suspended
			from sessions s join accounts a on a.id = s.account_id
			where s.token_hash = ?
			""".trimIndent(),
			{ rs, _ ->
				SessionUse(
					rs.getObject("account_id", UUID::class.java), rs.getBoolean("expired"), rs.getBoolean("stale"), rs.getBoolean("unseen"),
					rs.getBoolean("suspended"),
				)
			},
			days, tokenHash,
		).firstOrNull() ?: return null
		if (row.expired) {
			delete(tokenHash)
			return null
		}
		if (row.stale) jdbc.update("update sessions set last_used_at = now() where token_hash = ?", tokenHash)
		if (row.unseen) markSeen(row.accountId)
		return SessionOwner(row.accountId, row.suspended)
	}

	/** 마지막 접속을 지금으로, 그날(한국 날짜) 처음이면 접속 줄 하나. 서버 여러 대가 같이 써도 줄은 하나다. */
	private fun markSeen(accountId: UUID) {
		jdbc.update("update accounts set last_seen_at = now() where id = ?", accountId)
		jdbc.update(
			"""
			insert into daily_active (kst_date, account_id, first_hour_kst)
			values ((now() at time zone 'Asia/Seoul')::date, ?, extract(hour from now() at time zone 'Asia/Seoul')::smallint)
			on conflict do nothing
			""".trimIndent(),
			accountId,
		)
	}

	fun delete(tokenHash: String) {
		jdbc.update("delete from sessions where token_hash = ?", tokenHash)
	}

	/** 그 계정의 세션을 지운다. [createdBefore] 가 있으면 그보다 먼저 생긴 것만. */
	fun deleteAll(accountId: UUID, createdBefore: java.time.Instant? = null) {
		if (createdBefore == null) jdbc.update("delete from sessions where account_id = ?", accountId)
		else jdbc.update("delete from sessions where account_id = ? and created_at < ?", accountId, java.sql.Timestamp.from(createdBefore))
	}
}
