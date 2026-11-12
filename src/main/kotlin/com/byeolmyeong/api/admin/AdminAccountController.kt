package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.BadRequestException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/** 관리자 목록·만들기·켜고 끄기(결정 014). 누가 하는지는 관리자 세션에서 얻는다. */
@RestController
class AdminAccountController(
	private val accounts: AdminAccountService,
	private val passwords: AdminPasswords,
	private val current: CurrentAdmin,
) {

	@GetMapping("/admin/admins")
	fun list(): List<AdminAccount> = accounts.list()

	@PostMapping("/admin/admins")
	fun create(@RequestBody body: JsonNode): ResponseEntity<AdminAccount> {
		body.requireObject()
		val loginId = body.textOrNull("loginId")?.let(::normalizeLoginId)
		val name = body.textOrNull("name")?.trim()
		val password = body.textOrNull("password")
		if (loginId == null || !validLoginId(loginId)) throw BadRequestException("INVALID_LOGIN_ID")
		if (name == null || !validName(name)) throw BadRequestException("INVALID_NAME")
		if (password == null || !passwords.acceptable(password)) throw BadRequestException("INVALID_PASSWORD")
		return ResponseEntity.status(201).body(accounts.create(loginId, name, password))
	}

	@PatchMapping("/admin/admins/{id}")
	fun setActive(@PathVariable id: String, @RequestBody body: JsonNode): AdminAccount {
		val targetId = pathId(id)
		val active = body.requireObject().booleanOrNull("active") ?: throw BadRequestException("INVALID_REQUEST")
		return accounts.setActive(current.get().id, targetId, active)
	}

	/** 다른 관리자의 비밀번호를 다시 정한다(결정 018). 자기 것은 `PUT /admin/me/password` 로만 바꾼다. 그 관리자의 세션은 모두 끝난다. */
	@PutMapping("/admin/admins/{id}/password")
	fun resetPassword(@PathVariable id: String, @RequestBody body: JsonNode): ResponseEntity<Void> {
		val targetId = pathId(id)
		val password = body.requireObject().textOrNull("password")
		if (password == null || !passwords.acceptable(password)) throw BadRequestException("INVALID_PASSWORD")
		accounts.resetPassword(current.get().id, targetId, password)
		return ResponseEntity.noContent().build()
	}
}

/**
 * 서버가 뜰 때 첫 관리자를 만든다: 관리자가 한 명도 없고 환경 변수 `ADMIN_BOOTSTRAP_ID`·`ADMIN_BOOTSTRAP_PASSWORD` 가 둘 다 있을 때만.
 * 없으면 관리자가 없는 채로 뜨고 `/admin/` 아래는 전부 닫혀 있다. 값은 로그에 남기지 않는다.
 */
@Component
class AdminBootstrap(
	private val accounts: AdminAccountService,
	@Value("\${byeolmyeong.admin.bootstrap-id}") private val loginId: String,
	@Value("\${byeolmyeong.admin.bootstrap-password}") private val password: String,
) : ApplicationRunner {

	override fun run(args: ApplicationArguments) {
		accounts.bootstrap(loginId, password)
	}
}
