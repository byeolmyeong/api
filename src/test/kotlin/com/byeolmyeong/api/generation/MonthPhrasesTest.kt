package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.SkyTransit
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 이번 달 문구표(결정 032, 원본은 docs/drafts/2026-11-20-month-phrases.md 3 · 7 · 8 · 9절 — 소유자 2026-11-20 "초안 그대로 써"). */
class MonthPhrasesTest {

	private fun transit(date: String, mover: Body, target: String, type: AspectType) =
		SkyTransit(LocalDate.parse(date), mover, target, type, orb = 0.1, score = 20.0)

	/** 초안 7절의 번호: CRC32("{날짜}|{지나가는 별}.{내 별}.{결}") mod 문구 수 + 1. */
	private fun expectedNumber(date: String, combination: String, size: Int): Int {
		val crc = CRC32().apply { update("$date|$combination".toByteArray(Charsets.UTF_8)) }.value
		return (crc % size).toInt() + 1
	}

	@Test
	fun `표 - 대표 조합 33칸과 기본 15칸, 칸마다 문구 3~5개, 모든 문구가 서로 다르다`() {
		assertEquals(33, MONTH_PHRASES.keys.count { !it.contains(".ANY.") })
		assertEquals(15, MONTH_PHRASES.keys.count { it.contains(".ANY.") })
		assertTrue(MONTH_PHRASES.values.all { it.size in 3..5 }, "칸마다 3~5")
		val all = MONTH_PHRASES.values.flatten()
		assertEquals(153, all.size)
		assertEquals(all.size, all.toSet().size, "모든 문구가 서로 다르다")
		for (mover in listOf(Body.SUN, Body.MERCURY, Body.VENUS, Body.MARS, Body.JUPITER)) {
			for (tone in AspectTone.entries) assertTrue("$mover.ANY.$tone" in MONTH_PHRASES, "기본 문구 $mover × $tone")
		}
	}

	@Test
	fun `문구는 모두 날 로 끝나고 숫자 · 점성술 용어 · 단정이 없다`() {
		val words = listOf("태양", "수성", "금성", "화성", "목성", "토성", "천왕성", "해왕성", "명왕성", "하우스", "상승점", "중천", "행성", "별자리", "각도", "역행")
		for (phrase in MONTH_PHRASES.values.flatten()) {
			assertTrue(phrase.endsWith("날"), phrase)
			assertTrue(phrase.none { it.isDigit() }, phrase)
			for (word in words) assertTrue(word !in phrase, "$phrase 에 $word")
		}
		assertNull(adviceProblem(emptyList(), MONTH_PHRASES.values.flatten()), "반드시 · 운명 · 앞일의 끝맺음이 없다")
	}

	@Test
	fun `칩은 지나가는 별 × 내 별의 표 - 각도와 상관없다`() {
		assertEquals(DayCategory.MIND, dayCategory(Body.SUN, "MOON"))
		assertEquals(DayCategory.PEOPLE, dayCategory(Body.MERCURY, "SUN"))
		assertEquals(DayCategory.WORK, dayCategory(Body.MERCURY, "SATURN"))
		assertEquals(DayCategory.MIND, dayCategory(Body.MERCURY, "NEPTUNE"))
		assertEquals(DayCategory.PEOPLE, dayCategory(Body.VENUS, "ASC"))
		assertEquals(DayCategory.WORK, dayCategory(Body.VENUS, "MC"))
		assertEquals(DayCategory.PEOPLE, dayCategory(Body.MARS, "VENUS"))
		assertEquals(DayCategory.MIND, dayCategory(Body.JUPITER, "PLUTO"))
		assertEquals(DayCategory.WORK, dayCategory(Body.SUN, "MC"))
		val trine = dayPhrase(transit("2026-11-21", Body.MERCURY, "SUN", AspectType.TRINE))
		assertEquals(DayCategory.PEOPLE, trine.category)
		assertEquals(trine.category, dayPhrase(transit("2026-11-21", Body.MERCURY, "SUN", AspectType.SQUARE)).category)
	}

	@Test
	fun `각도의 결 - 합 · 어울림(육분 · 삼분) · 부딪힘(사분 · 충)`() {
		assertEquals(AspectTone.CONJ, toneOf(AspectType.CONJUNCTION))
		assertEquals(AspectTone.HARMONY, toneOf(AspectType.SEXTILE))
		assertEquals(AspectTone.HARMONY, toneOf(AspectType.TRINE))
		assertEquals(AspectTone.TENSION, toneOf(AspectType.SQUARE))
		assertEquals(AspectTone.TENSION, toneOf(AspectType.OPPOSITION))
	}

	@Test
	fun `같은 날 · 같은 조합은 늘 같은 문구이고 번호는 CRC32 로 정해진다`() {
		val day = transit("2026-11-21", Body.MERCURY, "SUN", AspectType.TRINE)
		val phrase = dayPhrase(day)
		assertEquals(phrase, dayPhrase(day))
		val cell = MONTH_PHRASES.getValue("MERCURY.SUN.HARMONY")
		val number = expectedNumber("2026-11-21", "MERCURY.SUN.HARMONY", cell.size)
		assertEquals("MERCURY.SUN.HARMONY.$number", phrase.id)
		assertEquals(cell[number - 1], phrase.text)
		// 육분도 같은 칸(어울림)
		assertEquals(phrase, dayPhrase(transit("2026-11-21", Body.MERCURY, "SUN", AspectType.SEXTILE)))
		// 날이 바뀌면 대개 다른 문구다(한 달 안에 여러 번호가 나온다)
		val numbers = (1..30).map { dayPhrase(transit("2026-11-%02d".format(it), Body.MERCURY, "SUN", AspectType.TRINE)).id }.toSet()
		assertTrue(numbers.size > 1)
	}

	@Test
	fun `조합 칸이 비면 지나가는 별 × 결의 기본 문구를 쓴다 - 번호의 해시는 조합으로`() {
		val day = transit("2026-12-03", Body.SUN, "URANUS", AspectType.CONJUNCTION)
		val phrase = dayPhrase(day)
		val cell = MONTH_PHRASES.getValue("SUN.ANY.CONJ")
		val number = expectedNumber("2026-12-03", "SUN.URANUS.CONJ", cell.size)
		assertEquals("SUN.ANY.CONJ.$number", phrase.id)
		assertEquals(cell[number - 1], phrase.text)
		assertEquals(DayCategory.MIND, phrase.category, "칩은 3절 표 그대로")
		assertEquals("SUN.ANY.HARMONY", dayPhrase(transit("2026-12-03", Body.SUN, "ASC", AspectType.TRINE)).id.substringBeforeLast("."))
	}

	@Test
	fun `한 달 안에 같은 문구가 겹치면 뒤의 것을 그 칸의 다음 번호로 바꾼다`() {
		// 같은 칸(MERCURY.SUN.HARMONY)에서 번호가 같아지는 두 날을 찾는다
		val cell = MONTH_PHRASES.getValue("MERCURY.SUN.HARMONY")
		val dates = (1..30).map { "2026-11-%02d".format(it) }
		val byNumber = dates.groupBy { expectedNumber(it, "MERCURY.SUN.HARMONY", cell.size) }
		val (number, same) = byNumber.entries.first { it.value.size >= 2 }
		val first = transit(same[0], Body.MERCURY, "SUN", AspectType.TRINE)
		val second = transit(same[1], Body.MERCURY, "SUN", AspectType.SEXTILE)
		val other = transit("2026-11-30", Body.SUN, "MOON", AspectType.TRINE)

		val phrases = monthPhrases(listOf(first, second, other))

		assertEquals("MERCURY.SUN.HARMONY.$number", phrases[0].id)
		assertEquals("MERCURY.SUN.HARMONY.${number % cell.size + 1}", phrases[1].id, "다음 번호(끝이면 1)")
		assertNotEquals(phrases[0].text, phrases[1].text)
		assertEquals(dayPhrase(other), phrases[2])
		assertEquals(phrases.map { it.text }.distinct(), phrases.map { it.text }, "한 달의 문구 겹침 0")
	}
}
