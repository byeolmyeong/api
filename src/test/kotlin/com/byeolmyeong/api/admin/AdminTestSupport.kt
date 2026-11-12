package com.byeolmyeong.api.admin

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.http
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// 관리자 API 통합 테스트가 함께 쓰는 것. 아이디·비밀번호는 테스트 안에서만 쓰는 가짜 값이다.

const val ROOT_ID = "root-admin"
const val ROOT_PASSWORD = "root-admin-test-password"
const val ADMIN_ORIGIN = "http://localhost:5173"
const val APP_ORIGIN = "http://localhost:8081"

/**
 * 첫 관리자([ROOT_ID])가 환경 변수로 만들어진 서버. 같은 설정을 쓰는 테스트 클래스는 서버 하나를 같이 쓴다.
 * 로그인 제한은 사실상 끄고(제한은 따로 본다), bcrypt 는 가장 싼 강도로 돌린다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		DEV_AUTH_ON,
		"byeolmyeong.admin.bootstrap-id=$ROOT_ID",
		"byeolmyeong.admin.bootstrap-password=$ROOT_PASSWORD",
		"byeolmyeong.admin.bcrypt-strength=4",
		"byeolmyeong.rate-limit.admin-login-per-ip=100000",
		"byeolmyeong.rate-limit.admin-login-per-id=100000",
		"byeolmyeong.rate-limit.inquiries-per-hour=100000",
		"byeolmyeong.rate-limit.faqs-per-minute=100000",
		"byeolmyeong.cors.allowed-origins=$APP_ORIGIN",
		"byeolmyeong.cors.admin-allowed-origins=$ADMIN_ORIGIN",
	],
)
annotation class AdminIntegrationTest

val mapper = JsonMapper()

fun json(value: Any?): String = mapper.writeValueAsString(value)

fun HttpResponse<String>.json(): JsonNode = mapper.readTree(body())

fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

fun login(port: Int, loginId: Any?, password: Any?): HttpResponse<String> =
	http(port, "POST", "/admin/auth/login", json(mapOf("loginId" to loginId, "password" to password)))

/** [ROOT_ID] (또는 준 관리자)로 로그인해 토큰을 받는다. */
fun adminToken(port: Int, loginId: String = ROOT_ID, password: String = ROOT_PASSWORD): String {
	val response = login(port, loginId, password)
	check(response.statusCode() == 200) { "관리자 로그인 실패: ${response.statusCode()}" }
	return response.json()["token"].asString()
}

/** 관리자를 API 로 하나 더 만들고 그 id 를 준다. */
fun createAdmin(port: Int, token: String, loginId: String, password: String, name: String = loginId): UUID {
	val response = http(port, "POST", "/admin/admins", json(mapOf("loginId" to loginId, "name" to name, "password" to password)), token)
	check(response.statusCode() == 201) { "관리자 만들기 실패: ${response.statusCode()} ${response.body()}" }
	return UUID.fromString(response.json()["id"].asString())
}

/** 첫 관리자만 남기고 관리자 쪽 데이터와 앱 사용자 데이터를 비운다. */
fun resetAdminData(jdbc: JdbcTemplate) {
	jdbc.update("delete from faqs")
	jdbc.update("delete from accounts")
	jdbc.update("delete from admin_sessions")
	jdbc.update("delete from admin_accounts where login_id <> ?", ROOT_ID)
	jdbc.update("update admin_accounts set active = true")
}

/** 앱 사용자 계정과 세션을 DB 에 직접 만든다. */
fun userAccount(jdbc: JdbcTemplate, name: String): Pair<UUID, String> {
	val id = UUID.randomUUID()
	val token = "user-session-token-of-$name"
	jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'TEST', ?)", id, name)
	jdbc.update("insert into sessions (token_hash, account_id) values (?, ?)", sha256(token), id)
	return id to token
}
