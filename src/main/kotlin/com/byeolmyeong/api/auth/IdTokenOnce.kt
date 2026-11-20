package com.byeolmyeong.api.auth

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.sql.Timestamp

/**
 * 한 번만 받기 대상인가(결정 047): 구글 토큰 가운데 웹 클라이언트([webClientId])가 받은 것 — 발급받은 쪽(`azp`), 없으면 대상(`aud`)으로 가린다.
 * [webClientId] 가 비면 아무것도 대상이 아니다(검사 꺼짐).
 */
internal fun needsOnce(identity: ProviderIdentity, webClientId: String): Boolean =
	webClientId.isNotBlank() && identity.provider == Provider.GOOGLE && (identity.authorizedParty ?: identity.audience) == webClientId

/**
 * 웹 클라이언트가 받은 구글 ID 토큰은 만료까지 한 번만 받는다(결정 047). 웹 리디렉트의 ID 토큰은 방문 기록에 남아 다시 쓸 수 있어서다.
 * `jti` 를 DB 에 선점한다 — 배포 중 두 서버가 함께 떠도 한 번이다(결정 046). 토큰 · `jti` 는 로그에 남기지 않는다.
 */
@Service
class IdTokenOnceService(
	private val jdbc: JdbcTemplate,
	@Value("\${byeolmyeong.auth.google.web-client-id}") webClientId: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val webClientId = webClientId.trim()

	@PostConstruct
	fun warnWhenOff() {
		if (webClientId.isEmpty()) log.warn("event=id_token_once_off reason=NO_WEB_CLIENT_ID")
	}

	/** 서명 · 대상 · 만료를 확인한 [identity] 를 받는다. 대상이면 `jti` 를 선점하고, 없거나 이미 쓴 것이면 401 `INVALID_ID_TOKEN`. */
	fun claim(identity: ProviderIdentity) {
		if (!needsOnce(identity, webClientId)) return
		val jti = identity.tokenId ?: throw InvalidIdTokenException()
		val expiresAt = identity.expiresAt ?: throw InvalidIdTokenException()
		val claimed = jdbc.update(
			"insert into used_id_tokens (jti, expires_at) values (?, ?) on conflict do nothing",
			jti, Timestamp.from(expiresAt),
		) == 1
		if (!claimed) {
			log.warn("event=id_token_replayed provider=GOOGLE")
			throw InvalidIdTokenException()
		}
	}

	/** 날마다 한국 시각 04:15 에 끝난 줄을 지운다. 같은 지우기라 서버 둘이 함께 돌아도 결과가 같다. */
	@Scheduled(cron = "0 15 4 * * *", zone = "Asia/Seoul")
	fun deleteExpired() {
		val count = jdbc.update("delete from used_id_tokens where expires_at < now()")
		log.info("event=used_id_tokens_expired_deleted count={}", count)
	}
}
