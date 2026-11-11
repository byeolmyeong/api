package com.byeolmyeong.api.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 웹 앱(다른 주소에서 열린 브라우저)이 이 서버를 부를 수 있게 한다.
 * 허용할 주소는 설정 `byeolmyeong.cors.allowed-origins`(환경 변수 `ALLOWED_ORIGINS`, 쉼표로 구분)에만 둔다.
 * 비어 있으면 아무 주소도 허용하지 않는다. 세션은 쿠키가 아니라 `Authorization` 헤더로 받으므로 자격 증명 허용은 켜지 않는다.
 */
@Configuration
class CorsConfig(
	@Value("\${byeolmyeong.cors.allowed-origins:}") private val allowedOrigins: List<String>,
) : WebMvcConfigurer {

	override fun addCorsMappings(registry: CorsRegistry) {
		val origins = allowedOrigins.map { it.trim() }.filter { it.isNotEmpty() }
		if (origins.isEmpty()) return
		registry.addMapping("/**")
			.allowedOrigins(*origins.toTypedArray())
			.allowedMethods("GET", "POST", "PUT", "DELETE")
			.allowedHeaders("Authorization", "Content-Type")
	}
}
