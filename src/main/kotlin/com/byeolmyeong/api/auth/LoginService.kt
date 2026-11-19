package com.byeolmyeong.api.auth

import com.byeolmyeong.api.birth.BirthProfile
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.BirthRequest
import com.byeolmyeong.api.web.BirthRequestParser
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** 가입권의 수명(결정 021). 출생 정보를 입력하는 동안 쓴다. */
private val SIGNUP_TTL = Duration.ofMinutes(30)

/** 가입권이 없거나 썼거나 만료됐다. 401 `SIGNUP_EXPIRED`. */
class SignupExpiredException : RuntimeException("SIGNUP_EXPIRED")

/** 처음 온 사람의 토큰에 이메일이 없다. 400 `EMAIL_REQUIRED`(결정 024). 가입권을 만들지 않았다. */
class EmailRequiredException : RuntimeException("EMAIL_REQUIRED")

/** 만 14세 미만이다. 403 `UNDER_AGE`. 계정·출생 정보는 만들지 않았다. */
class UnderAgeException : RuntimeException("UNDER_AGE")

/** 로그인의 결과: 계정이 있으면 세션, 없으면 가입권. */
sealed interface LoginOutcome {
	data class SignedIn(val sessionToken: String) : LoginOutcome
	data class SignupRequired(val signupToken: String, val expiresAt: Instant) : LoginOutcome
}

/** `POST /auth/login` 의 공급자 부분. 값 검증은 [LoginService.identify] 가 한다. */
data class ProviderCredential(val provider: String?, val idToken: String?, val code: String?, val redirectUri: String?)

/** 애플 refresh token 과 그것을 받은 클라이언트. 철회할 때 둘 다 쓴다. */
data class AppleToken(val clientId: String, val refreshToken: String)

/** 쓴 가입권의 내용. 이메일은 [identity] 에 있다. */
data class SignupTicket(val identity: ProviderIdentity, val appleToken: AppleToken?)

/**
 * 소셜 로그인과 가입(결정 021). 공급자 토큰을 확인하고, 계정이 있으면 세션을, 없으면 가입권을 준다.
 * 가입은 가입권 + 출생 정보로 하고 만 14세 이상일 때만 계정·출생 정보·세션을 한 트랜잭션에 만든다.
 * 공급자 토큰·가입권·출생 정보를 로그에 남기지 않는다.
 */
@Service
class LoginService(
	private val accounts: AccountRepository,
	private val tickets: SignupTicketRepository,
	private val auth: AuthService,
	verifiers: List<ProviderTokenVerifier>,
	private val kakao: KakaoOAuth,
	private val apple: AppleSignIn,
	private val appleTokens: AppleTokenRepository,
	private val birthProfiles: BirthProfileService,
	private val birthParser: BirthRequestParser,
	private val legal: LegalVersions,
	private val tx: TransactionTemplate,
	private val events: ApplicationEventPublisher,
) {
	private val verifiers = verifiers.associateBy { it.provider }

	/**
	 * 요청의 공급자 자격 증명을 확인해 사용자를 얻는다. `idToken` 이나 (`code` + `redirectUri`) 중 하나만 와야 하고,
	 * `code` 는 카카오 웹만 쓴다. 모양이 틀리면 400 `INVALID_REQUEST`.
	 */
	fun identify(credential: ProviderCredential): ProviderIdentity {
		val provider = when (credential.provider) {
			"GOOGLE" -> Provider.GOOGLE
			"APPLE" -> Provider.APPLE
			"KAKAO" -> Provider.KAKAO
			else -> throw BadRequestException("INVALID_REQUEST")
		}
		val idToken = credential.idToken?.takeIf { it.isNotBlank() }
		val code = credential.code?.takeIf { it.isNotBlank() }
		if ((idToken == null) == (code == null)) throw BadRequestException("INVALID_REQUEST")
		val token = if (code != null) {
			val redirectUri = credential.redirectUri?.takeIf { it.isNotBlank() }
			if (provider != Provider.KAKAO || redirectUri == null) throw BadRequestException("INVALID_REQUEST")
			kakao.exchange(code, redirectUri)
		} else {
			checkNotNull(idToken)
		}
		return verifiers.getValue(provider).verify(token)
	}

	/**
	 * 계정이 있으면 세션, 없으면 가입권. 애플이고 [appleAuthorizationCode] 가 오면 바로 refresh token 으로 바꿔 둔다
	 * (탈퇴 때 철회용, 코드는 5분만 유효하다). 바꾸지 못해도 로그인은 된다. [appleRedirectUri] 는 웹 로그인이 쓴 돌아올 주소.
	 */
	fun login(identity: ProviderIdentity, appleAuthorizationCode: String? = null, appleRedirectUri: String? = null): LoginOutcome {
		val appleToken = appleToken(identity, appleAuthorizationCode, appleRedirectUri)
		val accountId = accounts.find(identity.provider.name, identity.subject)
		if (accountId != null) {
			// 이미 있는 계정은 이메일이 없어도 로그인된다(잠그지 않는다). 있으면 공급자의 지금 값으로 고쳐 둔다(결정 024).
			identity.email?.let { accounts.updateEmail(accountId, it) }
			if (appleToken != null) appleTokens.save(accountId, appleToken)
			val session = auth.issueSession(accountId)
			events.publishEvent(AuditEvent(AuditAction.USER_LOGIN, AuditActor.user(accountId)))
			return LoginOutcome.SignedIn(session)
		}
		if (identity.email == null) {
			// 받아 둔 애플 토큰은 계정을 만들지 않으니 철회한다.
			if (appleToken != null) runCatching { apple.revoke(appleToken.refreshToken, appleToken.clientId) }
			throw EmailRequiredException()
		}
		val ticket = auth.newToken()
		val expiresAt = Instant.now().plus(SIGNUP_TTL)
		tickets.insert(auth.hash(ticket), identity, expiresAt, appleToken)
		return LoginOutcome.SignupRequired(ticket, expiresAt)
	}

	private fun appleToken(identity: ProviderIdentity, code: String?, redirectUri: String?): AppleToken? {
		if (identity.provider != Provider.APPLE || code.isNullOrBlank()) return null
		val clientId = identity.audience ?: return null
		return apple.exchange(code, clientId, redirectUri?.takeIf { it.isNotBlank() })?.let { AppleToken(clientId, it) }
	}

	/** 가입 전에 닉네임을 쓸 수 있는지(결정 025). 유효한 가입권이 있어야 한다(401). 규칙에 어긋나면 400. */
	fun nicknameAvailable(signupToken: String?, nickname: String?): Boolean {
		val hash = signupToken?.takeIf { it.isNotBlank() }?.let(auth::hash) ?: throw SignupExpiredException()
		if (!tickets.isValid(hash)) throw SignupExpiredException()
		val name = normalizeNickname(nickname) ?: throw BadRequestException("INVALID_NICKNAME")
		return !accounts.nicknameTaken(name)
	}

	/**
	 * 가입. 순서: 가입권(401) → 약관 · 처리방침 버전(400) → 닉네임 규칙(400) → 프로필(400) → 출생 정보 검증(400) — 여기까지 가입권은 남는다 —
	 * → 만 14세(403, 가입권을 지운다) → 한 트랜잭션에 가입권 쓰기 · 계정 · 출생 정보 · 닉네임 · 프로필 · 동의 · 세션.
	 * 닉네임을 다른 계정이 쓰면 트랜잭션을 되돌리고 409(가입권은 남는다). 같은 사람이 가입권 두 장으로 동시에 와도 계정은 하나이고,
	 * 계정을 채우는 것(출생 정보 · 닉네임 · 약관 · 동의 · 프로필 · 가입 감사 기록)은 계정을 실제로 만든 요청 하나뿐이다.
	 * 동의 시각은 서버 시각이다(결정 026 · 027). 세션 토큰을 돌려준다.
	 */
	fun signup(
		signupToken: String?,
		birth: BirthRequest,
		nickname: String?,
		avatar: Int?,
		termsVersion: String?,
		privacyVersion: String?,
		marketingConsent: Boolean,
		pushConsent: Boolean,
	): String {
		val hash = signupToken?.takeIf { it.isNotBlank() }?.let(auth::hash) ?: throw SignupExpiredException()
		if (!tickets.isValid(hash)) throw SignupExpiredException()
		// 지금 약관 · 처리방침에 동의하지 않았으면 가입권을 쓰지 않고 돌려보낸다(앱이 약관 단계로 돌아가 다시 보낸다, 결정 026).
		if (!legal.agreed(termsVersion, privacyVersion)) throw BadRequestException("TERMS_REQUIRED")
		// 닉네임 · 프로필이 틀려도 가입권을 쓰지 않고 돌려보낸다(고쳐서 다시 보낸다, 결정 025 · 028).
		val name = normalizeNickname(nickname) ?: throw BadRequestException("INVALID_NICKNAME")
		val picture = requireAvatar(avatar)
		val parsed = birthParser.parse(birth)
		if (!isAtLeast14(parsed.date, koreaToday())) {
			// 계정을 만들지 않으므로 받아 둔 애플 토큰도 철회한다. 철회가 터져도 403 으로 답한다.
			val appleToken = tickets.consume(hash)?.appleToken
			if (appleToken != null) runCatching { apple.revoke(appleToken.refreshToken, appleToken.clientId) }
			throw UnderAgeException()
		}
		val profile = BirthProfile(parsed.date, parsed.time, parsed.place?.id)
		val (accountId, session, created) = checkNotNull(
			tx.execute {
				val ticket = tickets.consume(hash) ?: throw SignupExpiredException()
				val (provider, subject) = ticket.identity.provider.name to ticket.identity.subject
				// 새 계정인지는 넣기의 결과로 판정한다. 남은 가입권으로 다시 오거나, 같은 사람의 다른 가입권이 동시에 와서 먼저 만들었으면
				// 이미 있는 계정의 출생 정보 · 닉네임 · 약관 시각 · 동의 · 프로필을 바꾸지 않고 세션만 준다(결정 026 · 독립 리뷰).
				val createdId = accounts.create(provider, subject)
				val accountId = createdId ?: checkNotNull(accounts.find(provider, subject))
				ticket.identity.email?.let { accounts.updateEmail(accountId, it) }
				if (createdId != null) {
					birthProfiles.save(accountId, profile)
					val now = Instant.now()
					accounts.setNicknameAndConsent(accountId, name, now.takeIf { marketingConsent })
					accounts.setTermsPushAvatar(accountId, legal.terms, legal.privacy, now, now.takeIf { pushConsent }, picture)
				}
				ticket.appleToken?.let { appleTokens.save(accountId, it) }
				Triple(accountId, auth.issueSession(accountId), createdId != null)
			},
		)
		events.publishEvent(AuditEvent(AuditAction.USER_LOGIN, AuditActor.user(accountId)))
		if (created) {
			events.publishEvent(AuditEvent(AuditAction.PROFILE_SAVED, AuditActor.user(accountId), detail = mapOf("change" to "CREATED")))
			// 동의한 판만 남긴다(결정 026). 이메일 · 닉네임은 넣지 않는다.
			events.publishEvent(
				AuditEvent(AuditAction.TERMS_AGREED, AuditActor.user(accountId), detail = mapOf("termsVersion" to legal.terms, "privacyVersion" to legal.privacy)),
			)
		}
		return session
	}
}

@Repository
class SignupTicketRepository(private val jdbc: JdbcTemplate) {

	/** 새 가입권. 넣을 때 만료된 가입권을 함께 치운다. */
	fun insert(tokenHash: String, identity: ProviderIdentity, expiresAt: Instant, appleToken: AppleToken?) {
		jdbc.update("delete from signup_tickets where expires_at < now()")
		jdbc.update(
			"""
			insert into signup_tickets (token_hash, provider, provider_user_id, expires_at, apple_client_id, apple_refresh_token, email)
			values (?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			tokenHash, identity.provider.name, identity.subject, Timestamp.from(expiresAt), appleToken?.clientId, appleToken?.refreshToken, identity.email,
		)
	}

	fun isValid(tokenHash: String): Boolean =
		jdbc.queryForObject("select count(*) from signup_tickets where token_hash = ? and expires_at > now()", Int::class.java, tokenHash)!! > 0

	/** 살아 있는 가입권을 지우며 그 내용을 돌려준다. 동시에 두 번 써도 한 번만 돌려준다. */
	fun consume(tokenHash: String): SignupTicket? = jdbc.query(
		"""
		delete from signup_tickets where token_hash = ? and expires_at > now()
		returning provider, provider_user_id, apple_client_id, apple_refresh_token, email
		""".trimIndent(),
		{ rs, _ ->
			val clientId = rs.getString("apple_client_id")
			val refresh = rs.getString("apple_refresh_token")
			SignupTicket(
				ProviderIdentity(Provider.valueOf(rs.getString("provider")), rs.getString("provider_user_id"), email = rs.getString("email")),
				if (clientId != null && refresh != null) AppleToken(clientId, refresh) else null,
			)
		},
		tokenHash,
	).firstOrNull()
}

@Repository
class AppleTokenRepository(private val jdbc: JdbcTemplate) {

	fun save(accountId: java.util.UUID, token: AppleToken) {
		jdbc.update(
			"""
			insert into apple_tokens (account_id, client_id, refresh_token) values (?, ?, ?)
			on conflict (account_id) do update set client_id = excluded.client_id, refresh_token = excluded.refresh_token, updated_at = now()
			""".trimIndent(),
			accountId, token.clientId, token.refreshToken,
		)
	}

	fun find(accountId: java.util.UUID): AppleToken? = jdbc.query(
		"select client_id, refresh_token from apple_tokens where account_id = ?",
		{ rs, _ -> AppleToken(rs.getString("client_id"), rs.getString("refresh_token")) },
		accountId,
	).firstOrNull()
}
