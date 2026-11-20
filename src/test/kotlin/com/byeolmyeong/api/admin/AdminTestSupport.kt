package com.byeolmyeong.api.admin

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.http
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
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

private val testEncoder = BCryptPasswordEncoder(4)

/**
 * 관리자를 API 로 하나 더 만들고(임시 비밀번호 — 결정 043 의 5) 비밀번호를 [password] 로, 첫 로그인 변경은 끝난 것으로 DB 에 바로 둔다.
 * 첫 로그인 변경 흐름은 따로 본다(AdminTemporaryPasswordApiIntegrationTest). 이름은 아이디 그대로다.
 */
fun createAdmin(port: Int, token: String, loginId: String, password: String, jdbc: JdbcTemplate): UUID {
	val response = http(port, "POST", "/admin/admins", json(mapOf("loginId" to loginId)), token)
	check(response.statusCode() == 201) { "관리자 만들기 실패: ${response.statusCode()} ${response.body()}" }
	val id = UUID.fromString(response.json()["admin"]["id"].asString())
	jdbc.update("update admin_accounts set password_hash = ?, must_change_password = false where id = ?", testEncoder.encode(password), id)
	return id
}

/** 첫 관리자만 남기고 관리자 쪽 데이터와 앱 사용자 데이터를 비운다. */
fun resetAdminData(jdbc: JdbcTemplate) {
	jdbc.update("delete from faqs")
	jdbc.update("update app_configs set min_version = null, latest_version = null, store_url = null, message = null, block_now = false, updated_by = null")
	jdbc.update("update app_maintenance set active = false, message = null, updated_by = null")
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
