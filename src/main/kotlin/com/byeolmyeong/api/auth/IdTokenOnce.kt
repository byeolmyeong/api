package com.byeolmyeong.api.auth

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.sql.Timestamp

/**
 * 한 번만 받기 대상인가(결정 047): 구글 토큰 가운데 발급받은 쪽(`azp`)이 웹 클라이언트([webClientId])인 것만.
 * 앱(iOS · 안드로이드)도 웹 클라이언트를 대상(`aud`)으로 받으므로 `aud` 로는 가리지 않고, `azp` 가 없으면 걸지 않는다 — 앱 로그인이 깨지지 않게.
 * [webClientId] 가 비면 아무것도 대상이 아니다(검사 꺼짐).
 */
internal fun needsOnce(identity: ProviderIdentity, webClientId: String): Boolean =
	webClientId.isNotBlank() && identity.provider == Provider.GOOGLE && identity.authorizedParty == webClientId

/**
 * 웹 클라이언트가 받은 구글 ID 토큰은 만료까지 한 번만 받는다(결정 047). 웹 리디렉트의 ID 토큰은 방문 기록에 남아 다시 쓸 수 있어서다.
 * `jti` 를 DB 에 선점한다 — 배포 중 두 서버가 함께 떠도 한 번이다(결정 046). 토큰 · `jti` 는 로그에 남기지 않는다.
 */
@Service
class IdTokenOnceService(
	private val jdbc: JdbcTemplate,
	@Value("\${byeolmyeong.auth.google.web-client-id}") webClientId: String,
	@Value("\${byeolmyeong.auth.google.client-ids}") clientIds: String,
	/** 앱 클라이언트 ID(비밀 아님) — 로그의 이름표에만 쓴다. 받는 대상(`client-ids`) · 한 번만 판정과는 상관없다. */
	@Value("\${byeolmyeong.auth.google.ios-client-ids}") iosClientIds: String,
	@Value("\${byeolmyeong.auth.google.android-client-ids}") androidClientIds: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val webClientId = webClientId.trim()
	private val clientIds = idSet(clientIds)
	private val iosClientIds = idSet(iosClientIds)
	private val androidClientIds = idSet(androidClientIds)

	/** 켜졌는지 시작할 때 남긴다. 값이 구글 클라이언트 목록에 없으면(오타) 어떤 토큰도 대상이 아니라 꺼진 것과 같다. */
	@PostConstruct
	fun logState() {
		when {
			webClientId.isEmpty() -> log.warn("event=id_token_once_off reason=NO_WEB_CLIENT_ID")
			webClientId !in clientIds -> log.warn("event=id_token_once_off reason=WEB_CLIENT_ID_NOT_IN_CLIENT_IDS")
			else -> log.info("event=id_token_once_on")
		}
	}

	/** 서명 · 대상 · 만료를 확인한 [identity] 를 받는다. 대상이면 `jti` 를 선점하고, 없거나 이미 쓴 것이면 401 `INVALID_ID_TOKEN`. */
	fun claim(identity: ProviderIdentity) {
		val once = needsOnce(identity, webClientId)
		if (identity.provider == Provider.GOOGLE) {
			// 어느 클라이언트가 받은 토큰인지 — 클라이언트 ID 대신 이름표(WEB · IOS · ANDROID · OTHER · NONE)로 남긴다.
			log.info("event=google_token_client azp={} aud={} once={}", label(identity.authorizedParty), label(identity.audience), once)
		}
		if (!once) return
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

	/**
	 * 날마다 한국 시각 04:15 에 끝난 줄을 지운다. 확인은 exp 뒤 60초(시계 차이)까지 받으므로 2분 더 지난 줄만 지운다.
	 * 같은 지우기라 서버 둘이 함께 돌아도 결과가 같다.
	 */
	@Scheduled(cron = "0 15 4 * * *", zone = "Asia/Seoul")
	fun deleteExpired() {
		val count = jdbc.update("delete from used_id_tokens where expires_at < now() - interval '2 minutes'")
		log.info("event=used_id_tokens_expired_deleted count={}", count)
	}

	private fun label(clientId: String?): String = when {
		clientId == null -> "NONE"
		webClientId.isNotEmpty() && clientId == webClientId -> "WEB"
		clientId in iosClientIds -> "IOS"
		clientId in androidClientIds -> "ANDROID"
		else -> "OTHER"
	}
}

private fun idSet(raw: String): Set<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
