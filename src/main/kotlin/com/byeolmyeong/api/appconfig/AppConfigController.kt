package com.byeolmyeong.api.appconfig

import com.byeolmyeong.api.admin.AppConfigChanged
import com.byeolmyeong.api.web.parseAppVersion
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

/** 한 플랫폼의 설정. 값이 없으면 null 로 보낸다(키를 빼지 않는다). [latestVersion] 은 앱이 업데이트를 권할 기준이다(결정 040 — 서버는 막지 않는다). */
data class PlatformConfig(val minVersion: String?, val latestVersion: String?, val storeUrl: String?, val message: String?, val blockNow: Boolean)

/** 점검(결정 017). [active] 면 앱의 모든 요청이 503 이다. */
data class Maintenance(val active: Boolean, val message: String?)

/** `GET /app-config` 의 응답. */
data class AppConfig(val ios: PlatformConfig, val android: PlatformConfig, val maintenance: Maintenance)

/**
 * 앱 설정의 공개 조회(결정 016). 세션 없이 읽는다 — 앱이 시작할 때, 그리고 426 을 받았을 때 읽는다.
 * 값은 관리자가 `/admin/app-config` 로 쓴다(`admin` 패키지). 이 패키지는 `app_configs`·`app_maintenance` 를 읽기만 한다.
 */
@RestController
class AppConfigController(private val configs: AppConfigService) {

	/** 1분 동안 캐시해도 된다. */
	@GetMapping("/app-config")
	fun get(): ResponseEntity<AppConfig> =
		ResponseEntity.ok().header("Cache-Control", "public, max-age=60").body(configs.current())
}

/**
 * 두 플랫폼의 설정을 서버 메모리에 잠깐 둔다(`byeolmyeong.app-config.cache-seconds`) — 모든 요청이 강제 업데이트를 확인하므로
 * 요청마다 DB 를 읽지 않게 한다. 관리자가 고치면(`admin.AppConfigChanged`) 바로 비운다.
 */
@Service
class AppConfigService(
	private val repository: AppConfigRepository,
	@Value("\${byeolmyeong.app-config.cache-seconds}") cacheSeconds: Long,
) {
	private class Cached(val config: AppConfig, val loadedAt: Long)

	private val ttlNanos = cacheSeconds * 1_000_000_000L

	@Volatile
	private var cached: Cached? = null

	fun current(): AppConfig {
		val now = System.nanoTime()
		cached?.takeIf { now - it.loadedAt < ttlNanos }?.let { return it.config }
		val rows = repository.findAll()
		val empty = PlatformConfig(null, null, null, null, false)
		return AppConfig(rows["ios"] ?: empty, rows["android"] ?: empty, repository.findMaintenance()).also { cached = Cached(it, now) }
	}

	/** 관리자가 설정을 고쳤다: 캐시를 비워 다음 요청이 새 값을 읽게 한다. 다른 서버는 캐시 시간이 지나면 따라온다. */
	@EventListener
	fun on(@Suppress("UNUSED_PARAMETER") event: AppConfigChanged) {
		cached = null
	}

	/**
	 * 이 요청을 막아야 하는가: 그 플랫폼의 막기가 켜져 있고, 최소 버전이 있고, 요청의 버전이 그보다 낮을 때만 true.
	 * 헤더가 없거나, 모르는 플랫폼이거나, 버전을 읽을 수 없으면 막지 않는다(모르면 통과).
	 */
	fun blocks(platformHeader: String?, versionHeader: String?): Boolean {
		if (platformHeader == null || versionHeader == null) return false
		val version = parseAppVersion(versionHeader.trim()) ?: return false
		val config = when (platformHeader.trim().lowercase(Locale.ROOT)) {
			"ios" -> current().ios
			"android" -> current().android
			else -> return false
		}
		val minimum = config.minVersion?.let(::parseAppVersion) ?: return false
		return config.blockNow && version < minimum
	}
}

@Repository
class AppConfigRepository(private val jdbc: JdbcTemplate) {

	/** 플랫폼(`ios`·`android`) → 설정. */
	fun findAll(): Map<String, PlatformConfig> {
		val rows = HashMap<String, PlatformConfig>()
		jdbc.query("select platform, min_version, latest_version, store_url, message, block_now from app_configs") { rs ->
			rows[rs.getString("platform")] = PlatformConfig(
				rs.getString("min_version"), rs.getString("latest_version"), rs.getString("store_url"), rs.getString("message"), rs.getBoolean("block_now"),
			)
		}
		return rows
	}

	/** 점검 행은 하나다(마이그레이션이 넣는다). */
	fun findMaintenance(): Maintenance =
		jdbc.query("select active, message from app_maintenance") { rs, _ -> Maintenance(rs.getBoolean("active"), rs.getString("message")) }
			.firstOrNull() ?: Maintenance(false, null)
}
