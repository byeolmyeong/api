package com.byeolmyeong.api.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminPasswordsTest {

	private val passwords = AdminPasswords(4)

	@Test
	fun `임시 비밀번호는 영문 대소문자 · 숫자 12자를 xxxx-xxxx-xxxx 로 묶고 비밀번호 규칙에 맞는다`() {
		val made = List(200) { passwords.temporary() }

		for (password in made) {
			assertTrue(Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}").matches(password), password)
			assertTrue(passwords.acceptable(password))
		}
		assertEquals(200, made.toSet().size, "겹치지 않는다")
		assertTrue(passwords.matches(made[0], passwords.hash(made[0])))
	}
}
