package com.byeolmyeong.api.report

import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.MilestoneKind
import com.byeolmyeong.api.chart.computeChart
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 시기 영역의 모양·표의 글·모델에게 맡길 줄 고르기·연도 대조·"지금"(순수 함수). 출생 시각은 테스트용 가짜 값이다.
class ReportTimingTest {

	private val mapper = JsonMapper()

	// 서울 1990-03-15 14:30 KST (chart/ChartReference.kt 의 SEOUL_1990 과 같은 값)
	private val knownUtc = Instant.parse("1990-03-15T05:30:00Z")
	private val known by lazy { computeTiming(computeChart(knownUtc, 37.5665, 126.9780, timeKnown = true), knownUtc) }

	// 시각 모름: 2000-06-15 한국 정오
	private val unknownUtc = Instant.parse("2000-06-15T03:00:00Z")
	private val unknown by lazy { computeTiming(computeChart(unknownUtc, 0.0, 0.0, timeKnown = false), unknownUtc) }

	private val today = LocalDate.of(2026, 11, 11)

	private fun texts(items: List<TimingItem>, change: (TimingItem, MutableMap<String, Any>) -> Unit = { _, _ -> }): JsonNode =
		mapper.valueToTree(
			mapOf(
				"items" to items.map { item ->
					mutableMapOf<String, Any>(
						"id" to item.id, "fromYear" to item.fromYear, "toYear" to item.toYear,
						"title" to "모델제목-${item.id}", "body" to "모델본문-${item.id}",
					).also { change(item, it) }
				},
			),
		)

	private fun problem(root: JsonNode, items: List<TimingItem>): String? {
		val parsed = parseTimingTexts(root) ?: return "UNUSABLE_RESPONSE"
		return timingTextProblem(parsed, items)
	}

	@Test
	fun `마디 네 개가 날짜와 해와 만 나이와 함께 시간 순서로 온다`() {
		val milestones = known.timing.milestones
		assertEquals(MilestoneKind.entries, milestones.map { it.kind })
		// 기준값(JPL Horizons, chart/timeline-horizons-reference.tsv 의 A): 처음 겹치는 때의 한국 날짜
		assertEquals(
			listOf(LocalDate.of(2011, 11, 9), LocalDate.of(2020, 1, 17), LocalDate.of(2034, 8, 20), LocalDate.of(2049, 2, 21)),
			milestones.map { it.date },
		)
		assertEquals(listOf(2011 to 21, 2020 to 29, 2034 to 44, 2049 to 58), milestones.map { it.year to it.age })
		assertEquals(listOf(ChangeSize.SMALL, ChangeSize.BIG, ChangeSize.BIG, ChangeSize.BIG), milestones.map { it.size })
		for (milestone in milestones) {
			assertTrue(milestone.from <= milestone.date && milestone.date <= milestone.to)
			assertEquals(milestone.date.year, milestone.year)
		}
	}

	@Test
	fun `구간마다 겹치지 않는 id 와 날짜와 영역과 글이 있고 시작 순서다`() {
		val intervals = known.timing.intervals
		assertTrue(intervals.size > 150, "평생 구간: ${intervals.size}")
		assertEquals(intervals.size, intervals.map { it.id }.toSet().size)
		assertEquals(intervals.sortedBy { it.from }, intervals)
		assertEquals(IntervalKind.entries.toSet(), intervals.map { it.kind }.toSet())
		// 이 차트는 태양·달·금성이 2·8하우스에 없어 돈 영역이 나오지 않는다
		assertEquals(setOf(LifeArea.PEOPLE, LifeArea.WORK, LifeArea.SELF), intervals.map { it.area }.toSet())
		for (interval in intervals) {
			assertTrue(interval.from <= interval.to)
			assertTrue(interval.title.isNotBlank() && interval.body.isNotBlank())
			assertEquals(interval.kind == IntervalKind.CHANGE, interval.size != null, "크기는 변화 구간에만 있다")
			if (interval.kind == IntervalKind.FATED) assertEquals(LifeArea.PEOPLE, interval.area)
		}
		assertTrue(known.timing.timeKnown)
		assertNull(known.timing.current, "지금은 읽을 때 붙인다")
	}

	@Test
	fun `시각을 모르면 운명적 만남 구간이 없고 timeKnown 이 false 다`() {
		assertFalse(unknown.timing.timeKnown)
		assertEquals(0, unknown.timing.intervals.count { it.kind == IntervalKind.FATED })
		assertEquals(4, unknown.timing.milestones.size)
		assertTrue(unknown.timing.intervals.none { it.area == LifeArea.MONEY }, "하우스가 없어 돈 영역은 나오지 않는다(결정 008)")
		// 상승점·중천·하우스·버텍스가 대상인 줄이 모델에게 가지 않는다
		for (item in unknown.items) {
			for (word in listOf("하우스", "상승점", "중천", "버텍스")) assertFalse(item.line.contains(word), item.line)
		}
	}

	@Test
	fun `표의 글에는 점성술 용어와 단정하는 말과 연도가 없다`() {
		val jargon = listOf(
			"태양", "수성", "금성", "화성", "목성", "토성", "천왕성", "해왕성", "명왕성", "별자리", "하우스", "상승점", "중천", "교점",
			"릴리스", "행운점", "버텍스", "각도", "사분", "역행", "트랜짓", "차트", "행성", "점성술", "귀환", "대립",
		)
		val asserting = listOf("반드시", "틀림없이", "될 거예요", "할 운명", "만나게 됩니다", "귀인", "수익", "투자", "합격")
		val all = (known.timing.milestones.flatMap { listOf(it.title, it.body) } +
			(known.timing.intervals + unknown.timing.intervals).flatMap { listOf(it.title, it.body) }).distinct()
		assertTrue(all.size >= 15, "표의 글 가짓수: ${all.size}")
		for (text in all) {
			for (word in jargon + asserting) assertFalse(text.contains(word), "'$word' 가 있다: $text")
			assertFalse(Regex("\\d").containsMatchIn(text), "숫자가 있다: $text")
		}
		// F4 가 정한 말
		assertTrue(known.timing.intervals.filter { it.kind == IntervalKind.FATED }.all { it.title == "운명처럼 느껴지는 만남이 생기기 쉬운 시기" })
		assertTrue(known.timing.intervals.any { it.title == "도움이 되는 사람을 만나기 쉬운 시기" })
		assertTrue(known.timing.intervals.any { it.title == "인연이 열리기 쉬운 시기" })
	}

	@Test
	fun `모델에게는 마디 네 개와 오늘 걸쳐 있거나 10년 안에 시작하는 구간만 맡긴다`() {
		val chosen = timingItemsForModel(known, today)
		assertEquals(MilestoneKind.entries.map { it.name }, chosen.take(4).map { it.id })
		val intervals = chosen.drop(4)
		val expected = known.timing.intervals.filter { it.to >= today && it.from <= today.plusYears(10) }
		// 결정 008 의 표: 차트 A 는 2026-11-11 기준 24개
		assertEquals(24, expected.size)
		assertEquals(expected.map { it.id }, intervals.map { it.id })
		for (item in intervals) {
			val interval = known.timing.intervals.first { it.id == item.id }
			assertEquals(interval.from.year, item.fromYear)
			assertEquals(interval.to.year, item.toYear)
		}
		// 줄에는 해만 있다: 달·날이 없다
		for (item in chosen) {
			assertTrue(item.line.contains("${item.fromYear}~${item.toYear}"), item.line)
			assertFalse(Regex("\\d{4}-\\d{2}").containsMatchIn(item.line), item.line)
			assertFalse(item.line.contains("월") || item.line.contains("세"), item.line)
		}
		// 한 살 때를 오늘로 치면 10년 창에 드는 구간만, 상한 30개
		assertTrue(timingItemsForModel(known, LocalDate.of(1991, 3, 15)).size <= 4 + 30)
		// 만 80세 뒤에는 마디만
		assertEquals(4, timingItemsForModel(known, LocalDate.of(2080, 1, 1)).size)
	}

	@Test
	fun `해를 그대로 옮겨 적은 글은 받아들이고 그 줄에만 붙인다`() {
		val chosen = timingItemsForModel(known, today)
		val parsed = assertNotNull(parseTimingTexts(texts(chosen)))
		assertNull(timingTextProblem(parsed, chosen))

		val written = known.timing.withTexts(parsed)
		assertTrue(written.milestones.all { it.title == "모델제목-${it.kind}" && it.body == "모델본문-${it.kind}" })
		val ids = chosen.map { it.id }.toSet()
		for ((before, after) in known.timing.intervals.zip(written.intervals)) {
			if (after.id in ids) {
				assertEquals("모델제목-${after.id}", after.title)
				assertEquals(before.copy(title = after.title, body = after.body), after, "글 말고는 바뀌지 않는다")
			} else {
				assertEquals(before, after, "맡기지 않은 구간은 표의 글 그대로다")
			}
		}
	}

	@Test
	fun `해가 계산값과 다른 글은 버린다`() {
		val chosen = timingItemsForModel(known, today)
		val target = chosen[5]
		assertEquals("YEAR_MISMATCH", problem(texts(chosen) { item, map -> if (item == target) map["fromYear"] = item.fromYear + 1 }, chosen))
		assertEquals("YEAR_MISMATCH", problem(texts(chosen) { item, map -> if (item == target) map["toYear"] = item.toYear - 1 }, chosen))
		// 글 안에 그 줄의 기간 밖의 해를 적었다
		val wrong = target.toYear + 3
		assertEquals("YEAR_MISMATCH", problem(texts(chosen) { item, map -> if (item == target) map["body"] = "${wrong}년에 새 일을 시작해요" }, chosen))
		assertEquals("YEAR_MISMATCH", problem(texts(chosen) { item, map -> if (item == target) map["title"] = "$wrong 의 전환" }, chosen))
		// 그 줄의 기간 안의 해는 틀린 것이 아니다
		assertNull(problem(texts(chosen) { item, map -> if (item == target) map["body"] = "${item.fromYear}년 무렵의 주제" }, chosen))
	}

	@Test
	fun `줄이 빠지거나 더해지거나 글이 비면 버린다`() {
		val chosen = timingItemsForModel(known, today)
		assertEquals("MALFORMED", problem(texts(chosen.drop(1)), chosen))
		assertEquals("MALFORMED", problem(texts(chosen + chosen.last()), chosen))
		assertEquals("MALFORMED", problem(texts(chosen) { item, map -> if (item == chosen[0]) map["id"] = "i99999" }, chosen))
		assertEquals("MALFORMED", problem(texts(chosen) { item, map -> if (item == chosen[2]) map["title"] = " " }, chosen))
		assertEquals("MALFORMED", problem(texts(chosen) { item, map -> if (item == chosen[2]) map["body"] = "" }, chosen))
		assertEquals("UNUSABLE_RESPONSE", problem(mapper.readTree("""{"items":[{"id":"SATURN_SQUARE","title":"가"}]}"""), chosen))
		assertEquals("UNUSABLE_RESPONSE", problem(texts(chosen) { item, map -> if (item == chosen[2]) map["fromYear"] = "2020" }, chosen))
		assertEquals("UNUSABLE_RESPONSE", problem(mapper.readTree("""{"title":"가"}"""), chosen))
	}

	@Test
	fun `요청에는 시기 지시문과 차트와 시기 목록이 가고 id 는 목록의 것만 고를 수 있다`() {
		val chart = computeChart(knownUtc, 37.5665, 126.9780, timeKnown = true)
		val chosen = timingItemsForModel(known, today)
		val request = reportTimingRequest(chart, chosen)
		assertEquals("report_timing", request.purpose)
		assertTrue(request.instructions.contains("점성술 용어를 하나도 쓰지 않습니다"), "공통 글쓰기 규칙")
		assertTrue(request.instructions.contains("이 시기의 주제"))
		assertTrue(request.instructions.contains("무슨 일이 일어난다고 쓰지 않습니다"))
		assertTrue(request.userText.contains("태양: 물고기"), "차트 글")
		assertTrue(request.userText.contains("[시기 목록]"))
		for (item in chosen) assertTrue(request.userText.contains(item.line))
		val schema = mapper.valueToTree<JsonNode>(request.schema)
		val idEnum = schema["properties"]["items"]["items"]["properties"]["id"]["enum"].values().map { it.asString() }
		assertEquals(chosen.map { it.id }, idEnum)
		// 출생 날짜·시각·태어난 해가 없다
		val all = request.instructions + request.userText + schema.toString()
		for (secret in listOf("1990", "03-15", "14:30", "3월")) assertFalse(all.contains(secret), "프롬프트에 '$secret' 이 있다")
	}

	@Test
	fun `지금 나의 구간은 주어진 오늘로 가린다`() {
		val timing = known.timing
		val now = timingNow(timing, today)
		assertEquals(today, now.date)
		// 기준값 파일의 A: 2026-11-11 에 걸친 구간은 둘(2026-02~2028-01, 2026-10~2027-06)
		assertEquals(2, now.intervals.size)
		assertEquals(timing.intervals.filter { it.from <= today && today <= it.to }, now.intervals)
		assertNull(now.milestone, "지금은 마디의 기간 안이 아니다")
		assertEquals(MilestoneKind.URANUS_OPPOSITION, now.nextMilestone?.kind)
		assertEquals(MilestoneKind.SATURN_RETURN, now.nearestMilestone, "2020-01 이 2034-08 보다 가깝다")
		assertEquals(timing.intervals.first { it.from > today }, now.nextInterval)

		// 시간이 지나면 답이 달라진다: 같은 저장본, 다른 오늘
		val later = timingNow(timing, LocalDate.of(2034, 9, 1))
		assertEquals(MilestoneKind.URANUS_OPPOSITION, later.milestone?.kind, "천왕성 대립의 기간(2034-07~2035-06) 안이다")
		assertEquals(MilestoneKind.SECOND_SATURN_RETURN, later.nextMilestone?.kind)
		assertEquals(MilestoneKind.URANUS_OPPOSITION, later.nearestMilestone)
		assertTrue(later.intervals != now.intervals)

		// 태어나기 전·모든 것이 지난 뒤
		val before = timingNow(timing, LocalDate.of(1990, 3, 1))
		assertTrue(before.intervals.isEmpty())
		assertEquals(MilestoneKind.SATURN_SQUARE, before.nextMilestone?.kind)
		val after = timingNow(timing, LocalDate.of(2090, 1, 1))
		assertTrue(after.intervals.isEmpty())
		assertNull(after.nextMilestone)
		assertNull(after.nextInterval)
		assertEquals(MilestoneKind.SECOND_SATURN_RETURN, after.nearestMilestone)
	}
}
