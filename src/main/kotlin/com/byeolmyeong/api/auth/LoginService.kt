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

/** 만 14세 미만이다. 403 `UNDER_AGE`. 계정·출생 정보는 만들지 않았다. */
class UnderAgeException : RuntimeException("UNDER_AGE")

/** 로그인의 결과: 계정이 있으면 세션, 없으면 가입권. */
sealed interface LoginOutcome {
	data class SignedIn(val sessionToken: String) : LoginOutcome
	data class SignupRequired(val signupToken: String, val expiresAt: Instant) : LoginOutcome
}

/** `POST /auth/login` 의 공급자 부분. 값 검증은 [LoginService.identify] 가 한다. */
data class ProviderCredential(val provider: String?, val idToken: String?, val code: String?, val redirectUri: String?)

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
	private val birthProfiles: BirthProfileService,
	private val birthParser: BirthRequestParser,
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

	fun login(identity: ProviderIdentity): LoginOutcome {
		val accountId = accounts.find(identity.provider.name, identity.subject)
		if (accountId != null) {
			val session = auth.issueSession(accountId)
			events.publishEvent(AuditEvent(AuditAction.USER_LOGIN, AuditActor.user(accountId)))
			return LoginOutcome.SignedIn(session)
		}
		val ticket = auth.newToken()
		val expiresAt = Instant.now().plus(SIGNUP_TTL)
		tickets.insert(auth.hash(ticket), identity, expiresAt)
		return LoginOutcome.SignupRequired(ticket, expiresAt)
	}

	/**
	 * 가입. 순서: 가입권(401) → 출생 정보 검증(400, 가입권은 남는다) → 만 14세(403, 가입권을 지운다) →
	 * 한 트랜잭션에 가입권 쓰기 · 계정 · 출생 정보 · 세션. 같은 사람이 가입권 두 장으로 동시에 와도 계정은 하나다.
	 * 세션 토큰을 돌려준다.
	 */
	fun signup(signupToken: String?, birth: BirthRequest): String {
		val hash = signupToken?.takeIf { it.isNotBlank() }?.let(auth::hash) ?: throw SignupExpiredException()
		if (!tickets.isValid(hash)) throw SignupExpiredException()
		val parsed = birthParser.parse(birth)
		if (!isAtLeast14(parsed.date, koreaToday())) {
			tickets.delete(hash)
			throw UnderAgeException()
		}
		val profile = BirthProfile(parsed.date, parsed.time, parsed.place?.id)
		val (accountId, session) = checkNotNull(
			tx.execute {
				val identity = tickets.consume(hash) ?: throw SignupExpiredException()
				val accountId = accounts.findOrCreate(identity.provider.name, identity.subject)
				birthProfiles.save(accountId, profile)
				accountId to auth.issueSession(accountId)
			},
		)
		events.publishEvent(AuditEvent(AuditAction.USER_LOGIN, AuditActor.user(accountId)))
		events.publishEvent(AuditEvent(AuditAction.PROFILE_SAVED, AuditActor.user(accountId), detail = mapOf("change" to "CREATED")))
		return session
	}
}

@Repository
class SignupTicketRepository(private val jdbc: JdbcTemplate) {

	/** 새 가입권. 넣을 때 만료된 가입권을 함께 치운다. */
	fun insert(tokenHash: String, identity: ProviderIdentity, expiresAt: Instant) {
		jdbc.update("delete from signup_tickets where expires_at < now()")
		jdbc.update(
			"insert into signup_tickets (token_hash, provider, provider_user_id, expires_at) values (?, ?, ?, ?)",
			tokenHash, identity.provider.name, identity.subject, Timestamp.from(expiresAt),
		)
	}

	fun isValid(tokenHash: String): Boolean =
		jdbc.queryForObject("select count(*) from signup_tickets where token_hash = ? and expires_at > now()", Int::class.java, tokenHash)!! > 0

	/** 살아 있는 가입권을 지우며 그 사용자를 돌려준다. 동시에 두 번 써도 한 번만 돌려준다. */
	fun consume(tokenHash: String): ProviderIdentity? = jdbc.query(
		"delete from signup_tickets where token_hash = ? and expires_at > now() returning provider, provider_user_id",
		{ rs, _ -> ProviderIdentity(Provider.valueOf(rs.getString("provider")), rs.getString("provider_user_id")) },
		tokenHash,
	).firstOrNull()

	fun delete(tokenHash: String) {
		jdbc.update("delete from signup_tickets where token_hash = ?", tokenHash)
	}
}
