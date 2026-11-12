package com.byeolmyeong.api.direction

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.Milestone
import com.byeolmyeong.api.chart.MilestoneKind
import com.byeolmyeong.api.chart.TransitTarget
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 미래 주제의 "지금의 시기": 오늘 걸쳐 있는 것과 다음 것을 고르고, 그 재료가 같은 동안(대상)을 정한다(결정 011). */
class DirectionFutureTest {

	private fun noon(date: String): Instant = LocalDate.parse(date).atTime(12, 0).atZone(KOREA_ZONE).toInstant()

	private fun interval(kind: IntervalKind, mover: Body, target: TransitTarget, from: String, to: String, area: LifeArea) = Interval(
		kind, mover, AspectType.CONJUNCTION, target, noon(from), noon(to), area, ChangeSize.SMALL.takeIf { kind == IntervalKind.CHANGE },
	)

	private val a = interval(IntervalKind.CHANGE, Body.SATURN, TransitTarget.SUN, "2026-01-10", "2026-06-20", LifeArea.WORK)
	private val b = interval(IntervalKind.OPPORTUNITY, Body.JUPITER, TransitTarget.VENUS, "2026-05-01", "2027-02-01", LifeArea.PEOPLE)
	private val c = interval(IntervalKind.FATED, Body.PLUTO, TransitTarget.VERTEX, "2028-03-03", "2028-04-04", LifeArea.PEOPLE)
	private val intervals = listOf(a, b, c)
	private val milestones = listOf(
		Milestone(MilestoneKind.SATURN_RETURN, noon("2026-08-01"), noon("2026-06-01"), noon("2026-12-31"), 2026, 29, ChangeSize.BIG),
		Milestone(MilestoneKind.URANUS_OPPOSITION, noon("2040-02-02"), noon("2040-01-01"), noon("2040-09-09"), 2040, 43, ChangeSize.BIG),
	)

	private fun at(date: String) = futureSituation(milestones, intervals, LocalDate.parse(date))

	private val saturnOnSun = "변화 구간(작은 변화) | 토성 – 태양: 겹침 | 영역: 일"
	private val jupiterOnVenus = "기회 구간 | 목성 – 금성: 겹침 | 영역: 사람·인연"
	private val plutoOnVertex = "운명적 만남 구간 | 명왕성 – 버텍스: 겹침 | 영역: 사람·인연"
	private val saturnReturn = "마디(큰 변화) | 토성이 태어날 때의 제자리로 처음 돌아옴 | 영역: 삶 전반"
	private val uranusOpposition = "마디(큰 변화) | 천왕성이 태어날 때의 제자리 맞은편에 옴 | 영역: 삶 전반"

	@Test
	fun `오늘 걸쳐 있는 구간 전부와 오늘 뒤에 처음 시작하는 구간과 마디를 고른다`() {
		val now = at("2026-05-15")

		assertEquals(
			listOf(
				"- 지금 지나는 중 | $saturnOnSun",
				"- 지금 지나는 중 | $jupiterOnVenus",
				"- 다음에 오는 것 | $plutoOnVertex",
				"- 다음에 오는 것 | $saturnReturn",
			),
			now.lines,
		)
		// 재료가 같은 동안: 마지막으로 무엇이 시작한 날부터, 다음에 무엇이 시작하거나 끝나기 전날까지
		assertEquals(LocalDate.parse("2026-05-01"), now.from)
		assertEquals(LocalDate.parse("2026-05-31"), now.until)
	}

	@Test
	fun `구간의 끝 날까지는 지나는 중이고 다음 날부터 새 동안이다`() {
		val lastDay = at("2026-06-20")
		assertEquals(
			listOf(
				"- 지금 지나는 중 | $saturnReturn",
				"- 지금 지나는 중 | $saturnOnSun",
				"- 지금 지나는 중 | $jupiterOnVenus",
				"- 다음에 오는 것 | $plutoOnVertex",
				"- 다음에 오는 것 | $uranusOpposition",
			),
			lastDay.lines,
		)
		assertEquals(LocalDate.parse("2026-06-01"), lastDay.from)
		assertEquals(LocalDate.parse("2026-06-20"), lastDay.until)

		val dayAfter = at("2026-06-21")
		assertFalse(dayAfter.lines.any { it.contains("토성 – 태양") })
		assertEquals(LocalDate.parse("2026-06-21"), dayAfter.from)
		assertEquals(LocalDate.parse("2026-12-31"), dayAfter.until)
	}

	@Test
	fun `지나는 것이나 다음 것이 없으면 없다고 적고 경계가 없는 쪽의 날짜는 없다`() {
		val before = at("2020-01-01")
		assertEquals(listOf("- 지금 지나는 중 | (없음)", "- 다음에 오는 것 | $saturnOnSun", "- 다음에 오는 것 | $saturnReturn"), before.lines)
		assertNull(before.from)
		assertEquals(LocalDate.parse("2026-01-09"), before.until)

		val after = at("2050-01-01")
		assertEquals(listOf("- 지금 지나는 중 | (없음)", "- 다음에 오는 것 | (없음)"), after.lines)
		assertEquals(LocalDate.parse("2040-09-10"), after.from)
		assertNull(after.until)
	}

	@Test
	fun `재료에는 해도 날짜도 나이도 없다`() {
		for (date in listOf("2020-01-01", "2026-05-15", "2026-06-20", "2050-01-01")) {
			assertFalse(at(date).lines.joinToString("\n").any { it.isDigit() }, date)
		}
	}

	@Test
	fun `대상은 차트 해시와 그 동안의 첫날과 마지막 날이고 그 사이의 날만 덮는다`() {
		val target = futureTarget("abc", at("2026-05-15"))
		assertEquals("abc|2026-05-01|2026-05-31", target)
		assertTrue(futureTargetCovers(target, LocalDate.parse("2026-05-01")))
		assertTrue(futureTargetCovers(target, LocalDate.parse("2026-05-31")))
		assertFalse(futureTargetCovers(target, LocalDate.parse("2026-04-30")))
		assertFalse(futureTargetCovers(target, LocalDate.parse("2026-06-01")))

		val open = futureTarget("abc", at("2050-01-01"))
		assertEquals("abc|2040-09-10|", open)
		assertTrue(futureTargetCovers(open, LocalDate.parse("2099-01-01")))
		assertFalse(futureTargetCovers(open, LocalDate.parse("2040-09-09")))
		assertTrue(futureTargetCovers(futureTarget("abc", at("2020-01-01")), LocalDate.parse("1999-01-01")))
	}

	@Test
	fun `글에 숫자가 있으면 버린다`() {
		val draft = DirectionDraft(
			title = "익숙한 방식을 다시 살펴볼 만한 때",
			items = listOf(
				DirectionItem("맡은 일을 적어 보기", "요즘은 책임이 무겁게 느껴지기 쉬워요. 하나씩 적어 보면 좋아요.", listOf("태양")),
				DirectionItem("고마운 사람에게 안부 전해 보기", "사람에게 마음이 열리기 쉬운 때예요. 먼저 연락해 볼 만해요.", listOf("금성")),
				DirectionItem("하루에 잠깐 혼자 걸어 보기", "생각이 많아지기 쉬워요. 짧게 걸어 보는 것도 방법이에요.", listOf("달")),
			),
		)
		val vocabulary = listOf("태양", "달", "금성")
		assertNull(draftProblem(draft, vocabulary, numbers = false))
		for (body in listOf("2027년에는 달라져 있어요. 적어 보면 좋아요.", "하루 10분만 걸어 보면 좋아요.", "스물아홉 무렵, 29세의 고비예요. 적어 볼 만해요.")) {
			val broken = draft.copy(items = draft.items.mapIndexed { i, item -> if (i == 1) item.copy(body = body) else item })
			assertEquals("HAS_NUMBER", draftProblem(broken, vocabulary, numbers = false), body)
			assertNull(draftProblem(broken, vocabulary, numbers = true), "숫자를 막지 않는 주제(이성)에서는 통과한다")
		}
		assertEquals("HAS_NUMBER", draftProblem(draft.copy(title = "2026년을 준비해 볼 만한 때"), vocabulary, numbers = false))
		assertEquals("HAS_NUMBER", draftProblem(draft.copy(items = draft.items.map { it.copy(title = "3가지 적어 보기") }), vocabulary, numbers = false))
	}
}
