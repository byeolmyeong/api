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

	/** 아이디만 받는다(결정 043 의 5). 이름은 아이디 그대로, 비밀번호는 서버가 만든 임시 비밀번호 — 이 응답에서 한 번만 보인다. */
	@PostMapping("/admin/admins")
	fun create(@RequestBody body: JsonNode): ResponseEntity<CreatedAdmin> {
		val loginId = body.requireObject().textOrNull("loginId")?.let(::normalizeLoginId)
		if (loginId == null || !validLoginId(loginId)) throw BadRequestException("INVALID_LOGIN_ID")
		return ResponseEntity.status(201).body(accounts.create(loginId))
	}

	/** 다른 관리자에게 새 임시 비밀번호(결정 043 의 5). 그 관리자의 세션은 모두 끝나고 다음 로그인 때 바꿔야 한다. */
	@PostMapping("/admin/admins/{id}/password-reset")
	fun reissue(@PathVariable id: String): ReissuedPassword = accounts.reissue(current.get().id, pathId(id))

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
