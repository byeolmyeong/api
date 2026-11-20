package com.byeolmyeong.api.report

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.TransitTarget
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.generation.ReasonWhen
import com.byeolmyeong.api.generation.TransitBasis
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

/** 시기 풀이(결정 033): 9줄 묶음 · 묶음마다 돌려받을 구조 · 검증 · 구간 이름 고정 · 근거 · 대표 구간(순수 함수). 출생 시각은 가짜 값이다. */
class TimingReportDraftTest {

	private val mapper = JsonMapper()

	// 서울 1990-03-15 14:30 KST(ReportTimingTest 와 같은 값)
	private val knownUtc = Instant.parse("1990-03-15T05:30:00Z")
	private val chart by lazy { computeChart(knownUtc, 37.5665, 126.9780, timeKnown = true) }
	private val draft by lazy { computeTiming(chart, knownUtc) }
	private val today = LocalDate.of(2026, 11, 20)
	private val items by lazy { timingItemsForModel(draft, today) }
	private val batches by lazy { timingBatches(items) }

	/** 검증을 통과하는 한 묶음의 답. [change] 로 줄마다 고쳐 틀린 답을 만든다. */
	private fun answer(batch: List<TimingItem>, change: (TimingItem, MutableMap<String, Any>) -> Unit = { _, _ -> }): JsonNode =
		mapper.valueToTree(
			mapOf(
				"milestones" to batch.filter { it.milestone }.map { item ->
					mutableMapOf<String, Any>("id" to item.id, "fromYear" to item.fromYear, "toYear" to item.toYear, "title" to "마디제목-${item.id}", "body" to "마디본문-${item.id}")
						.also { change(item, it) }
				},
				"intervals" to batch.filter { !it.milestone }.map { item ->
					mutableMapOf<String, Any>(
						"id" to item.id, "fromYear" to item.fromYear, "toYear" to item.toYear,
						"summary" to "한줄-${item.id}", "body" to "설명-${item.id}",
						"detail" to mapOf("changes" to "달라짐-${item.id}", "howTo" to "지내기-${item.id}", "after" to "지나면-${item.id}"),
						"practices" to listOf("하나 해 보기", "둘 적어 두기", "셋 나누기"),
					).also { change(item, it) }
				},
			),
		)

	private fun problem(root: JsonNode, batch: List<TimingItem>): String? {
		val parsed = parseTimingBatch(root) ?: return "UNUSABLE_RESPONSE"
		return timingBatchProblem(parsed, batch)
	}

	@Test
	fun `마디 넷과 맡길 구간을 9줄씩 묶는다 - 약 4번`() {
		assertTrue(items.size in 5..34, "${items.size}")
		assertEquals(items, batches.flatten(), "순서 그대로 나눈다")
		assertTrue(batches.all { it.size <= 9 })
		assertEquals((items.size + 8) / 9, batches.size)
		assertEquals(4, batches.first().count { it.milestone }, "마디는 첫 묶음에")
	}

	@Test
	fun `묶음의 요청 - 줄마다 해만 있고 구간에는 고정된 이름이 붙으며 구조는 마디 · 구간을 나눠 받는다`() {
		val batch = batches.first()
		val request = timingReportRequest(chart, batch, draft.timing)
		assertEquals("timing_report", request.purpose)
		val lines = request.userText.substringAfter("[시기 목록]\n").lines()
		assertEquals(batch.size, lines.size)
		for ((item, line) in batch.zip(lines)) {
			assertTrue(line.startsWith("- ${item.id} | ${item.fromYear}~${item.toYear} | "), line)
			val interval = draft.timing.intervals.firstOrNull { it.id == item.id }
			if (interval != null) assertTrue(line.endsWith(" | 이름: ${interval.title}"), line) else assertFalse(line.contains("이름:"), line)
		}
		val schema = mapper.valueToTree<JsonNode>(request.schema)["properties"]
		assertEquals(listOf("milestones", "intervals"), schema.propertyNames().toList())
		assertEquals(batch.filter { it.milestone }.map { it.id }, schema["milestones"]["items"]["properties"]["id"]["enum"].values().map { it.asString() })
		assertEquals(
			listOf("id", "fromYear", "toYear", "summary", "body", "detail", "practices"),
			schema["intervals"]["items"]["properties"].propertyNames().toList(),
			"구간은 제목을 쓰지 않는다",
		)
		assertTrue(request.instructions.contains("[시기 목록]"))
		// 마디가 없는 묶음도 구조가 비지 않는다(빈 enum 을 보내지 않는다)
		val later = batches.last()
		if (later.none { it.milestone }) {
			val laterSchema = mapper.valueToTree<JsonNode>(timingReportRequest(chart, later, draft.timing).schema)["properties"]
			assertNull(laterSchema["milestones"]["items"]["properties"]["id"]["enum"])
		}
	}

	@Test
	fun `맞는 답은 통과하고 줄 · 칸 · 해 · 실천이 틀리면 버린다`() {
		val batch = batches.first()
		val firstInterval = batch.first { !it.milestone }.id
		assertNull(problem(answer(batch), batch))
		assertEquals("UNUSABLE_RESPONSE", problem(mapper.readTree("""{"milestones":[]}"""), batch))
		val broken = listOf<Pair<String, (TimingItem, MutableMap<String, Any>) -> Unit>>(
			"MALFORMED" to { item, map -> if (item.id == firstInterval) map["practices"] = listOf("하나 해 보기", "둘 적어 두기") },
			"MALFORMED" to { item, map -> if (item.id == firstInterval) map["summary"] = " " },
			"MALFORMED" to { item, map -> if (item.id == firstInterval) map["detail"] = mapOf("changes" to "가", "howTo" to "", "after" to "다") },
			"MALFORMED" to { item, map -> if (item.milestone) map["title"] = "" },
			"YEAR_MISMATCH" to { item, map -> if (item.id == "SATURN_RETURN") map["fromYear"] = item.fromYear + 1 },
			"YEAR_MISMATCH" to { item, map -> if (item.id == firstInterval) map["body"] = "2099년에 달라져요" },
			"NOT_ADVICE_FORM" to { item, map -> if (item.id == firstInterval) map["practices"] = listOf("해 봐요", "둘 적어 두기", "셋 나누기") },
			"NOT_ADVICE_FORM" to { item, map -> if (item.id == firstInterval) map["detail"] = mapOf("changes" to "반드시 바뀌어요", "howTo" to "가", "after" to "나") },
		)
		for ((code, change) in broken) assertEquals(code, problem(answer(batch, change), batch))
		// 줄이 빠지거나 다른 묶음의 줄이 오면
		val dropped = mapper.valueToTree<JsonNode>(mapOf("milestones" to answer(batch)["milestones"], "intervals" to answer(batch)["intervals"].values().drop(1)))
		assertEquals("MALFORMED", problem(dropped, batch))
		if (batches.size > 1) assertEquals("MALFORMED", problem(answer(batches[1]), batch))
	}

	@Test
	fun `글을 붙이면 마디는 모델의 제목 · 본문, 구간은 표의 이름 그대로 설명 · 자세히 · 실천 · 근거가 맡긴 줄에만 붙는다`() {
		val texts = batches.map { batch -> assertNotNull(parseTimingBatch(answer(batch))) }

		val timing = draft.timing.withReportTexts(texts, draft.sources)

		assertTrue(timing.milestones.all { it.title == "마디제목-${it.kind}" && it.body == "마디본문-${it.kind}" })
		val asked = items.filter { !it.milestone }.map { it.id }.toSet()
		for ((before, after) in draft.timing.intervals.zip(timing.intervals)) {
			assertEquals(before.title, after.title, "구간 이름은 표의 제목 그대로(결정 033 의 3)")
			assertEquals(listOf(before.from, before.to, before.area, before.size), listOf(after.from, after.to, after.area, after.size))
			if (after.id in asked) {
				assertEquals("설명-${after.id}", after.body)
				assertEquals("한줄-${after.id}", after.summary)
				assertEquals(TimingDetail("달라짐-${after.id}", "지내기-${after.id}", "지나면-${after.id}"), after.detail)
				assertEquals(listOf("하나 해 보기", "둘 적어 두기", "셋 나누기").map(::TimingPractice), after.practices)
				assertEquals(transitReason(draft.sources.getValue(after.id)), after.reason)
			} else {
				assertEquals(before, after, "맡기지 않은 구간은 표의 글뿐이고 칸이 없다")
				assertNull(after.summary ?: after.detail ?: after.practices ?: after.reason)
			}
		}
	}

	private fun interval(target: TransitTarget, aspect: AspectType?) = Interval(
		IntervalKind.OPPORTUNITY, Body.JUPITER, aspect, target, knownUtc, knownUtc, LifeArea.PEOPLE, size = null,
	)

	@Test
	fun `근거는 그 구간을 만든 지나가는 별 · 대상 · 각도, 하우스 통과면 하우스다`() {
		assertEquals(ReasonWhen.NOW, transitReason(interval(TransitTarget.SUN, AspectType.TRINE)).`when`)
		assertEquals(listOf(TransitBasis(Body.JUPITER, "SUN", AspectType.TRINE)), transitReason(interval(TransitTarget.SUN, AspectType.TRINE)).items)
		assertEquals(listOf(TransitBasis(Body.JUPITER, "ASC", AspectType.CONJUNCTION)), transitReason(interval(TransitTarget.ASCENDANT, AspectType.CONJUNCTION)).items)
		assertEquals(listOf(TransitBasis(Body.JUPITER, "MC", AspectType.TRINE)), transitReason(interval(TransitTarget.MIDHEAVEN, AspectType.TRINE)).items)
		assertEquals(listOf(TransitBasis(Body.JUPITER, "VERTEX", AspectType.CONJUNCTION)), transitReason(interval(TransitTarget.VERTEX, AspectType.CONJUNCTION)).items)
		assertEquals(listOf(TransitBasis(Body.JUPITER, house = 7)), transitReason(interval(TransitTarget.HOUSE_7, null)).items)
		assertEquals(listOf(TransitBasis(Body.JUPITER, "VENUS", AspectType.CONJUNCTION)), transitReason(interval(TransitTarget.VENUS, AspectType.CONJUNCTION)).items)
	}

	private fun shown(id: String, kind: IntervalKind, size: ChangeSize?, from: String) = TimingInterval(
		id, kind, LocalDate.parse(from), LocalDate.parse("2027-12-31"), LifeArea.SELF, size, "제목", "본문",
	)

	@Test
	fun `대표 구간 - 오늘 걸친 것 가운데 변화 큰 · 작은 · 운명적 만남 · 기회 순, 같으면 늦게 시작한 것`() {
		val opportunity = shown("i1", IntervalKind.OPPORTUNITY, null, "2026-01-01")
		val fated = shown("i2", IntervalKind.FATED, null, "2026-02-01")
		val smallEarly = shown("i3", IntervalKind.CHANGE, ChangeSize.SMALL, "2026-03-01")
		val smallLate = shown("i4", IntervalKind.CHANGE, ChangeSize.SMALL, "2026-04-01")
		val big = shown("i5", IntervalKind.CHANGE, ChangeSize.BIG, "2025-01-01")

		assertEquals("i5", mainIntervalId(listOf(opportunity, fated, smallEarly, smallLate, big)))
		assertEquals("i4", mainIntervalId(listOf(opportunity, fated, smallEarly, smallLate)), "같으면 늦게 시작한 것")
		assertEquals("i2", mainIntervalId(listOf(opportunity, fated)))
		assertEquals("i1", mainIntervalId(listOf(opportunity)))
		assertNull(mainIntervalId(emptyList()))

		// 읽을 때 오늘(고정 날짜)로 정한다 — 저장하지 않는다
		val timing = draft.timing.withReportTexts(batches.map { assertNotNull(parseTimingBatch(answer(it))) }, draft.sources)
		val now = timingReportNow(timing, today)
		val current = assertNotNull(now.current)
		assertEquals(mainIntervalId(current.intervals), current.mainId)
		assertEquals(timingNow(timing, today).intervals, current.intervals)
		assertNull(timing.current, "저장할 본문에는 지금이 없다")
		val empty = timingReportNow(timing, LocalDate.of(2150, 1, 1))
		assertNull(empty.current!!.mainId, "걸친 구간이 없으면 키가 없다")
	}
}
