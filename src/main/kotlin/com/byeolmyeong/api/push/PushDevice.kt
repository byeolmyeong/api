package com.byeolmyeong.api.push

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import com.byeolmyeong.api.web.parseAppVersion
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Expo 푸시 토큰의 모양(결정 042 의 2): `ExponentPushToken[…]` · `ExpoPushToken[…]`, 200자까지. */
private val EXPO_TOKEN = Regex("Expo(nent)?PushToken\\[[A-Za-z0-9_-]+]")
private const val TOKEN_MAX = 200

private val PLATFORMS = setOf("IOS", "ANDROID")

/** `POST /push/devices`. */
data class PushDeviceRequest(val expoToken: String? = null, val platform: String? = null)

/** 로그에서 토큰을 가리킬 때 쓰는 값: SHA-256 의 앞 8글자. 토큰 원문은 로그에 남기지 않는다(결정 042 의 1). */
internal fun tokenRef(token: String): String =
	HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray())).take(8)

/** 기기 토큰 등록 · 지우기(결정 042 의 2). 소유자는 세션에서만 얻는다. 웹은 부르지 않는다. */
@RestController
class PushDeviceController(private val devices: PushDeviceService, private val owner: CurrentOwner) {

	@PostMapping("/push/devices")
	fun register(@RequestBody request: PushDeviceRequest, @RequestHeader("X-App-Version", required = false) appVersion: String?): ResponseEntity<Void> {
		devices.register(owner.id(), request.expoToken, request.platform, appVersion)
		return ResponseEntity.noContent().build()
	}

	@DeleteMapping("/push/devices/{expoToken}")
	fun remove(@PathVariable expoToken: String): ResponseEntity<Void> {
		devices.remove(owner.id(), expoToken)
		return ResponseEntity.noContent().build()
	}
}

@Service
class PushDeviceService(private val repository: PushDeviceRepository) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * 토큰이 틀리면 400 `INVALID_PUSH_TOKEN`, 플랫폼이 `IOS` · `ANDROID` 가 아니면 400 `INVALID_PLATFORM`.
	 * 같은 토큰이 있으면 이 계정으로 옮기고 플랫폼 · 앱 버전 · 마지막 등록 시각을 고친다. 앱 버전은 읽을 수 있을 때만 둔다.
	 */
	fun register(ownerId: UUID, expoToken: String?, platform: String?, appVersion: String?) {
		if (expoToken == null || expoToken.length > TOKEN_MAX || !EXPO_TOKEN.matches(expoToken)) throw BadRequestException("INVALID_PUSH_TOKEN")
		if (platform !in PLATFORMS) throw BadRequestException("INVALID_PLATFORM")
		repository.upsert(expoToken, ownerId, platform!!, appVersion?.takeIf { parseAppVersion(it) != null })
		log.info("event=push_device_registered platform={} tokenRef={}", platform, tokenRef(expoToken))
	}

	/** 내 기기만 지운다. 남의 것 · 없는 것은 404 `NOT_FOUND`. */
	fun remove(ownerId: UUID, expoToken: String) {
		if (!repository.delete(expoToken, ownerId)) throw NotFoundException("NOT_FOUND")
		log.info("event=push_device_removed tokenRef={}", tokenRef(expoToken))
	}
}

@Repository
class PushDeviceRepository(private val jdbc: JdbcTemplate) {

	fun upsert(expoToken: String, accountId: UUID, platform: String, appVersion: String?) {
		jdbc.update(
			"""
			insert into push_devices (expo_token, account_id, platform, app_version) values (?, ?, ?, ?)
			on conflict (expo_token) do update
			set account_id = excluded.account_id, platform = excluded.platform, app_version = excluded.app_version, last_seen_at = now()
			""".trimIndent(),
			expoToken, accountId, platform, appVersion,
		)
	}

	/** 지웠으면 true. */
	fun delete(expoToken: String, accountId: UUID): Boolean =
		jdbc.update("delete from push_devices where expo_token = ? and account_id = ?", expoToken, accountId) > 0
}
