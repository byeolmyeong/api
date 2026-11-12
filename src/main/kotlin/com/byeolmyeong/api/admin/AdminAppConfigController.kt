package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import com.byeolmyeong.api.web.parseAppVersion
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URISyntaxException
import java.time.OffsetDateTime
import java.util.UUID

private val PLATFORMS = setOf("ios", "android")
private val KEYS = listOf("minVersion", "storeUrl", "message", "blockNow")
private const val STORE_URL_MAX = 500
private const val MESSAGE_MAX = 200

/** 관리자가 보는 한 플랫폼의 앱 설정. 값이 없으면 null 이다. [updatedBy] 는 아무도 고치지 않았으면 null. */
data class AdminPlatformConfig(
	val minVersion: String?,
	val storeUrl: String?,
	val message: String?,
	val blockNow: Boolean,
	val updatedAt: String,
	val updatedBy: AdminRef?,
)

data class AdminAppConfig(val ios: AdminPlatformConfig, val android: AdminPlatformConfig)

/** 검증을 통과한 새 값. */
class AppConfigUpdate(val minVersion: String?, val storeUrl: String?, val message: String?, val blockNow: Boolean)

/**
 * 앱 설정 편집(결정 016): 플랫폼마다 최소 버전·스토어 주소·안내 글·막기. 앱은 `GET /app-config`(`appconfig` 패키지)로 읽고,
 * 막기가 켜지면 낮은 버전의 요청이 426 이 된다. 관리자 API 는 그 막기에 걸리지 않는다.
 */
@RestController
class AdminAppConfigController(private val configs: AdminAppConfigService, private val current: CurrentAdmin) {

	@GetMapping("/admin/app-config")
	fun get(): AdminAppConfig = configs.get()

	/** 네 값을 모두 보내야 한다(빠진 키가 있으면 `INVALID_REQUEST`) — 보내지 않은 값이 모르는 사이 남거나 지워지지 않게. */
	@PutMapping("/admin/app-config/{platform}")
	fun update(@PathVariable platform: String, @RequestBody body: JsonNode): AdminPlatformConfig {
		if (platform !in PLATFORMS) throw NotFoundException("NOT_FOUND")
		body.requireObject()
		if (KEYS.any { !body.has(it) }) throw BadRequestException("INVALID_REQUEST")
		val minVersion = body.textOrNull("minVersion")
		val storeUrl = body.textOrNull("storeUrl")
		val message = body.textOrNull("message")?.trim()?.ifEmpty { null }
		val blockNow = body.booleanOrNull("blockNow") ?: throw BadRequestException("INVALID_REQUEST")

		if (minVersion != null && parseAppVersion(minVersion) == null) throw BadRequestException("INVALID_VERSION")
		if (storeUrl != null && !validStoreUrl(storeUrl)) throw BadRequestException("INVALID_STORE_URL")
		if (message != null && (message.codePointLength() > MESSAGE_MAX || NUL in message)) throw BadRequestException("INVALID_MESSAGE")
		if (blockNow && minVersion == null) throw BadRequestException("BLOCK_REQUIRES_MIN_VERSION")
		return configs.update(platform, current.get().id, AppConfigUpdate(minVersion, storeUrl, message, blockNow))
	}

	/** `https://` 로 시작하고 호스트가 있는 500자 이하의 주소. 공백·제어 문자가 있으면 틀리다. 앞뒤 공백을 떼지 않는다. */
	private fun validStoreUrl(url: String): Boolean {
		if (url.length > STORE_URL_MAX || !url.startsWith("https://") || url.any { it.isWhitespace() || it.isISOControl() }) return false
		return try {
			!URI(url).host.isNullOrEmpty()
		} catch (_: URISyntaxException) {
			false
		}
	}
}

/** 로그에는 이벤트 이름과 플랫폼만 남긴다(안내 글·주소 없음). */
@Service
class AdminAppConfigService(
	private val repository: AdminAppConfigRepository,
	private val audit: AdminAudit,
	private val events: ApplicationEventPublisher,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun get(): AdminAppConfig {
		val rows = repository.findAll()
		return AdminAppConfig(rows.getValue("ios"), rows.getValue("android"))
	}

	/** [platform] 은 `ios`·`android` 중 하나다(컨트롤러가 봤다). 바꾼 뒤 캐시를 비우게 알리고 감사 기록을 남긴다(안내 글·주소는 남기지 않는다). */
	fun update(platform: String, adminId: UUID, update: AppConfigUpdate): AdminPlatformConfig {
		repository.update(platform, adminId, update)
		log.info("event=admin_app_config_updated platform={} block_now={}", platform, update.blockNow)
		events.publishEvent(AppConfigChanged())
		audit.record(AuditAction.APP_CONFIG_UPDATED, "APP_CONFIG", platform, buildMap {
			put("platform", platform)
			if (update.minVersion != null) put("minVersion", update.minVersion)
			put("blockNow", update.blockNow)
		})
		return repository.findAll().getValue(platform)
	}
}

/** `app_configs` 를 관리자 쪽에서 읽고 쓴다. 행은 마이그레이션이 넣은 둘뿐이다. `appconfig` 패키지의 코드는 쓰지 않는다. */
@Repository
class AdminAppConfigRepository(private val jdbc: JdbcTemplate) {

	/** 플랫폼(`ios`·`android`) → 설정. */
	fun findAll(): Map<String, AdminPlatformConfig> {
		val rows = HashMap<String, AdminPlatformConfig>()
		jdbc.query(
			"""
			select c.platform, c.min_version, c.store_url, c.message, c.block_now, c.updated_at, c.updated_by, a.name as updater_name
			from app_configs c left join admin_accounts a on a.id = c.updated_by
			""",
		) { rs ->
			rows[rs.getString("platform")] = AdminPlatformConfig(
				minVersion = rs.getString("min_version"),
				storeUrl = rs.getString("store_url"),
				message = rs.getString("message"),
				blockNow = rs.getBoolean("block_now"),
				updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).utcSeconds(),
				updatedBy = rs.getObject("updated_by", UUID::class.java)?.let { AdminRef(it, rs.getString("updater_name")) },
			)
		}
		return rows
	}

	fun update(platform: String, adminId: UUID, update: AppConfigUpdate) {
		jdbc.update(
			"update app_configs set min_version = ?, store_url = ?, message = ?, block_now = ?, updated_at = now(), updated_by = ? where platform = ?",
			update.minVersion, update.storeUrl, update.message, update.blockNow, adminId, platform,
		)
	}
}
