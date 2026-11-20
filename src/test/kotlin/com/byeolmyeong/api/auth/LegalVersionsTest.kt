package com.byeolmyeong.api.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val NEW = "2026-11-20"
private const val OLD = "2026-11-19"

/** 결정 045: 지금 버전과 함께 받는 옛 버전 목록을 약관 · 처리방침 각각 보고, 받으면 사람이 보낸 두 값을 돌려준다(계정 · 감사 기록에 남길 값). */
class LegalVersionsTest {

	private val legal = LegalVersions(terms = NEW, privacy = NEW, termsPrevious = OLD, privacyPrevious = OLD)

	@Test
	fun `지금 버전 둘이면 받고 보낸 두 값을 돌려준다`() {
		assertEquals(AgreedVersions(NEW, NEW), legal.agreed(NEW, NEW))
	}

	@Test
	fun `옛 목록의 버전 둘이면 받고 보낸 옛 버전을 그대로 돌려준다 — 지금 버전으로 바꾸지 않는다`() {
		assertEquals(AgreedVersions(OLD, OLD), legal.agreed(OLD, OLD))
	}

	@Test
	fun `한쪽만 옛 버전이어도 각각 받는 목록에 있으면 받고 섞인 그대로 돌려준다`() {
		assertEquals(AgreedVersions(OLD, NEW), legal.agreed(OLD, NEW))
		assertEquals(AgreedVersions(NEW, OLD), legal.agreed(NEW, OLD))
	}

	@Test
	fun `없거나 지금 버전 · 옛 목록 밖이면 null — 글자 그대로 같아야 한다`() {
		val rejected = listOf(
			null to null, null to NEW, NEW to null, OLD to null,
			"2026-11-18" to NEW, NEW to "2026-11-18", "2026-01-01" to OLD,
			"" to "", " $NEW" to NEW, "$OLD " to NEW, NEW to "$OLD,$NEW",
		)
		for ((terms, privacy) in rejected) assertNull(legal.agreed(terms, privacy), "terms=$terms privacy=$privacy")
	}

	@Test
	fun `옛 목록이 비어 있으면 지금 버전만 받는다`() {
		val only = LegalVersions(terms = NEW, privacy = NEW, termsPrevious = "", privacyPrevious = "  ")
		assertEquals(AgreedVersions(NEW, NEW), only.agreed(NEW, NEW))
		assertNull(only.agreed(OLD, NEW))
		assertNull(only.agreed(NEW, OLD))
	}

	@Test
	fun `옛 목록은 약관 · 처리방침 따로다 — 처리방침의 옛 목록에 없는 버전은 처리방침으로 받지 않는다`() {
		val termsOnly = LegalVersions(terms = NEW, privacy = NEW, termsPrevious = OLD, privacyPrevious = "")
		assertEquals(AgreedVersions(OLD, NEW), termsOnly.agreed(OLD, NEW))
		assertNull(termsOnly.agreed(NEW, OLD))
	}

	@Test
	fun `옛 목록은 쉼표로 여럿을 적고 앞뒤 공백 · 빈 칸은 무시한다`() {
		val many = LegalVersions(terms = NEW, privacy = NEW, termsPrevious = " 2026-11-18 , $OLD,", privacyPrevious = OLD)
		assertEquals(AgreedVersions("2026-11-18", OLD), many.agreed("2026-11-18", OLD))
		assertEquals(AgreedVersions(OLD, OLD), many.agreed(OLD, OLD))
		assertNull(many.agreed("", OLD))
	}
}
