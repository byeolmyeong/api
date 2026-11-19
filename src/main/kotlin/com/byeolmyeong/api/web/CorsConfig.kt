package com.byeolmyeong.api.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 웹 앱(다른 주소에서 열린 브라우저)이 이 서버를 부를 수 있게 한다.
 * 허용할 주소는 설정 `byeolmyeong.cors.allowed-origins`(환경 변수 `ALLOWED_ORIGINS`, 쉼표로 구분)에만 둔다.
 * 비어 있으면 아무 주소도 허용하지 않는다. 앱 경로는 자격 증명(웹의 세션 쿠키 `bm_session`, 결정 021)을 허용하고,
 * 관리자 경로는 허용하지 않는다(관리 화면은 `Authorization` 헤더만 쓴다).
 *
 * 관리 화면은 주소가 따로다(결정 014): `/admin/` 아래는 `byeolmyeong.cors.admin-allowed-origins`(환경 변수 `ADMIN_ALLOWED_ORIGINS`)의
 * 주소만, 나머지 경로는 앱의 주소만 허용한다. 서로의 주소는 서로의 경로에서 거절된다.
 */
@Configuration
class CorsConfig(
	@Value("\${byeolmyeong.cors.allowed-origins:}") private val allowedOrigins: List<String>,
	@Value("\${byeolmyeong.cors.admin-allowed-origins:}") private val adminAllowedOrigins: List<String>,
) : WebMvcConfigurer {

	override fun addCorsMappings(registry: CorsRegistry) {
		val origins = clean(allowedOrigins)
		val adminOrigins = clean(adminAllowedOrigins)
		if (origins.isEmpty() && adminOrigins.isEmpty()) return
		// 먼저 적은 경로가 먼저 맞는다. 관리 화면의 주소가 비어 있어도 이 줄을 둬야 `/admin/` 아래가 아래의 "/**" 로 넘어가지 않는다
		// (허용 주소가 빈 목록이면 모든 출처를 거절한다).
		registry.addMapping("/admin/**")
			.allowedOrigins(*adminOrigins.toTypedArray())
			.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
			.allowedHeaders("Authorization", "Content-Type")
		if (origins.isEmpty()) return
		registry.addMapping("/**")
			.allowedOrigins(*origins.toTypedArray())
			.allowedMethods("GET", "POST", "PUT", "DELETE")
			.allowedHeaders("Authorization", "Content-Type")
			.allowCredentials(true)
	}

	private fun clean(origins: List<String>): List<String> = origins.map { it.trim() }.filter { it.isNotEmpty() }
}
