package com.byeolmyeong.api.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 개발용 로그인을 켤 수 있는 배포 환경(결정 022). */
private val DEV_LOGIN_ENVS = setOf("local", "staging")

/**
 * 운영 안전장치(결정 022). 개발용 로그인(결정 005)은 배포 환경(`DEPLOY_ENV`)이 local·staging 일 때만 켤 수 있다.
 * 그 밖(prod·빈 값·모르는 이름)에서 켜져 있으면 이 빈을 만들 때 예외를 던져 서버가 시작하지 않는다.
 * 404 로 숨기지 않는 이유: 켜진 채 운영에 뜬 것이 배포의 상태 확인에서 바로 드러나야 한다.
 */
@Component
class DevLoginGuard(
	@Value("\${byeolmyeong.auth.dev-enabled}") devEnabled: Boolean,
	@Value("\${byeolmyeong.deploy-env}") deployEnv: String,
) {
	init {
		val env = deployEnv.trim()
		if (devEnabled && env !in DEV_LOGIN_ENVS) {
			LoggerFactory.getLogger(DevLoginGuard::class.java).error(
				"event=startup_refused reason=dev_login_outside_local_staging deploy_env={}",
				env.ifEmpty { "empty_as_prod" },
			)
			throw IllegalStateException("개발용 로그인(AUTH_DEV_ENABLED)은 DEPLOY_ENV 가 local·staging 일 때만 켤 수 있다(결정 022)")
		}
	}
}
