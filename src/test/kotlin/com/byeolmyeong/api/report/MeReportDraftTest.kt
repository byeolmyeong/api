package com.byeolmyeong.api.report

import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.computeWorkStyle
import com.byeolmyeong.api.generation.BasisCandidate
import com.byeolmyeong.api.generation.ReasonWhen
import com.byeolmyeong.api.generation.Star
import com.byeolmyeong.api.generation.basisCandidates
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 나 탭 풀이(결정 030 · 031 · 038): 영역마다 돌려받을 구조, 응답을 글로 읽기, 검증, 고른 근거 id 를 구조로 바꾸기. */
class MeReportDraftTest {

	private val mapper = JsonMapper()

	// 서울 1990-03-15 14:30(시각 앎)과 같은 날 시각 모름(한국 정오). 계산은 chart 의 순수 함수다.
	private val known: Chart = computeChart(Instant.parse("1990-03-15T05:30:00Z"), 37.5665, 126.9780, timeKnown = true)
	private val unknown: Chart = computeChart(Instant.parse("1990-03-15T03:00:00Z"), 0.0, 0.0, timeKnown = false)
	private val knownCandidates: List<BasisCandidate> = basisCandidates(known)
	private val ids: Set<String> = knownCandidates.map { it.id }.toSet()

	/** 검증을 통과하는 한 영역의 응답. [change] 로 고쳐 틀린 답을 만든다. */
	private fun valid(area: MeReportArea, change: (MutableMap<String, Any>) -> Unit = {}): JsonNode {
		val json = mutableMapOf<String, Any>("title" to "영역 제목", "keywords" to listOf("가", "나", "다"))
		if (area != MeReportArea.LOVE) json["paragraphs"] = (1..4).map { mapOf("title" to "문단 제목", "body" to "문단 본문이에요.", "basis" to listOf("P-SUN", "P-MOON")) }
		when (area) {
			MeReportArea.PERSONALITY -> json["glance"] = mapOf("shining" to listOf("빛남 가", "빛남 나"), "tiring" to listOf("지침 가", "지침 나"))
			MeReportArea.LOVE -> {
				json["practices"] = listOf("마음을 자주 전하기", "혼자 쉬는 시간을 말해 두기", "서운한 마음을 그날 말하기").map { mapOf("title" to it, "body" to "이유와 방법이에요.") }
				json["basis"] = listOf("P-VENUS")
			}
			MeReportArea.RELATIONSHIP -> json["closer"] = mapOf("first" to "조용히 살펴요", "close" to "장난이 늘어요", "longtime" to "먼저 챙겨요")
			MeReportArea.WORK -> {}
			MeReportArea.EMOTION -> json["shaken"] = mapOf("when" to "무시당했다고 느낄 때", "comfort" to "혼자 걸을 때")
		}
		change(json)
		return mapper.valueToTree(json)
	}

	private fun parse(area: MeReportArea, change: (MutableMap<String, Any>) -> Unit = {}): MeAreaDraft =
		assertNotNull(parseMeArea(area, valid(area, change)), "$area")

	private fun problem(area: MeReportArea, change: (MutableMap<String, Any>) -> Unit): String? = meAreaProblem(parse(area, change), ids)

	@Suppress("UNCHECKED_CAST")
	private fun MutableMap<String, Any>.paragraph(index: Int, key: String, value: Any) {
		val paragraphs = (this["paragraphs"] as List<Map<String, Any>>).toMutableList()
		paragraphs[index] = paragraphs[index] + (key to value)
		this["paragraphs"] = paragraphs
	}

	@Test
	fun `영역마다 그 영역의 칸만 읽고 다섯 모두 검증을 통과한다`() {
		for (area in MeReportArea.entries) {
			val draft = parse(area)
			assertNull(meAreaProblem(draft, ids), "$area")
			assertEquals(if (area == MeReportArea.LOVE) 0 else 4, draft.paragraphs.size)
			assertEquals(area == MeReportArea.PERSONALITY, draft.glance != null)
			assertEquals(area == MeReportArea.LOVE, draft.practices != null && draft.basis != null)
			assertEquals(area == MeReportArea.RELATIONSHIP, draft.closer != null)
			assertEquals(area == MeReportArea.EMOTION, draft.shaken != null)
		}
		assertEquals(MeShaken("무시당했다고 느낄 때", "혼자 걸을 때"), parse(MeReportArea.EMOTION).shaken)
	}

	@Test
	fun `영역의 칸이 빠졌거나 타입이 틀리면 null 이다`() {
		assertNull(parseMeArea(MeReportArea.PERSONALITY, valid(MeReportArea.PERSONALITY) { it.remove("glance") }), "한눈에 보기가 없다")
		assertNull(parseMeArea(MeReportArea.LOVE, valid(MeReportArea.LOVE) { it.remove("basis") }), "연애의 근거가 없다")
		assertNull(parseMeArea(MeReportArea.LOVE, valid(MeReportArea.LOVE) { it.remove("practices") }), "실천이 없다")
		assertNull(parseMeArea(MeReportArea.RELATIONSHIP, valid(MeReportArea.RELATIONSHIP) { it["closer"] = mapOf("first" to "가", "close" to "나") }), "오래된 사이가 없다")
		assertNull(parseMeArea(MeReportArea.EMOTION, valid(MeReportArea.EMOTION) { it.remove("shaken") }), "흔들릴 때가 없다")
		assertNull(parseMeArea(MeReportArea.WORK, valid(MeReportArea.WORK) { it.remove("paragraphs") }), "문단이 없다")
		assertNull(parseMeArea(MeReportArea.WORK, valid(MeReportArea.WORK) { it["keywords"] = listOf(1, 2, 3) }), "키워드가 문자열이 아니다")
		assertNull(parseMeArea(MeReportArea.WORK, mapper.readTree("""{"title":"제목"}""")), "필드가 빠졌다")
	}

	@Test
	fun `개수 · 빈 글 · 근거가 틀리면 MALFORMED 다`() {
		val malformed = listOf<Pair<MeReportArea, (MutableMap<String, Any>) -> Unit>>(
			MeReportArea.WORK to { it["keywords"] = listOf("가", "나") },
			MeReportArea.WORK to { it["keywords"] = listOf("가", "나", "가") },
			MeReportArea.WORK to { it["paragraphs"] = (it["paragraphs"] as List<*>).take(3) },
			MeReportArea.WORK to { it["paragraphs"] = (it["paragraphs"] as List<*>) + (it["paragraphs"] as List<*>).take(1) },
			MeReportArea.WORK to { it["title"] = " " },
			MeReportArea.WORK to { it.paragraph(0, "body", "\n") },
			MeReportArea.WORK to { it.paragraph(1, "basis", emptyList<String>()) },
			MeReportArea.WORK to { it.paragraph(1, "basis", listOf("P-SUN", "P-MOON", "P-MARS")) },
			MeReportArea.WORK to { it.paragraph(2, "basis", listOf("P-SUN", "P-SUN")) },
			MeReportArea.PERSONALITY to { it["glance"] = mapOf("shining" to listOf("하나"), "tiring" to listOf("가", "나")) },
			MeReportArea.PERSONALITY to { it["glance"] = mapOf("shining" to listOf("가", "나"), "tiring" to listOf("가", "나", "다")) },
			MeReportArea.LOVE to { it["practices"] = (it["practices"] as List<*>).take(2) },
			MeReportArea.LOVE to { it["basis"] = emptyList<String>() },
			MeReportArea.RELATIONSHIP to { it["closer"] = mapOf("first" to "가", "close" to "", "longtime" to "다") },
			MeReportArea.EMOTION to { it["shaken"] = mapOf("when" to "가", "comfort" to " ") },
		)
		for ((index, case) in malformed.withIndex()) assertEquals("MALFORMED", problem(case.first, case.second), "$index: ${case.first}")
	}

	@Test
	fun `후보에 없는 근거 · 숫자 · 단정 · 기로 끝나지 않는 실천은 버린다`() {
		assertEquals("BASIS_NOT_IN_CHART", problem(MeReportArea.WORK) { it.paragraph(0, "basis", listOf("P-SUN", "P-NOPE")) })
		assertEquals("BASIS_NOT_IN_CHART", problem(MeReportArea.LOVE) { it["basis"] = listOf("상승점") })
		// 일 방식의 수치는 서버만 말한다(결정 030 의 6) — 어느 영역의 어느 칸이든
		assertEquals("HAS_NUMBER", problem(MeReportArea.WORK) { it.paragraph(3, "body", "혼자 일하는 쪽이 70 정도예요.") })
		assertEquals("HAS_NUMBER", problem(MeReportArea.WORK) { it.paragraph(3, "body", "열에 일곱, 칠십 % 쯤이에요.") })
		assertEquals("HAS_NUMBER", problem(MeReportArea.EMOTION) { it["keywords"] = listOf("가", "나", "퍼센트") })
		assertEquals("HAS_NUMBER", problem(MeReportArea.PERSONALITY) { it["glance"] = mapOf("shining" to listOf("가", "나"), "tiring" to listOf("가", "2번째")) })
		// 실천 한 줄의 검사(generation.adviceProblem): "~기" 끝맺음 · 단정 · 앞일의 끝맺음
		assertEquals("NOT_ADVICE_FORM", problem(MeReportArea.LOVE) { it["practices"] = listOf("전해요", "말하기", "듣기").map { t -> mapOf("title" to t, "body" to "이유예요.") } })
		assertEquals("NOT_ADVICE_FORM", problem(MeReportArea.LOVE) { it["practices"] = listOf("전하기.", "말하기", "듣기").map { t -> mapOf("title" to t, "body" to "이유예요.") } })
		assertEquals("NOT_ADVICE_FORM", problem(MeReportArea.RELATIONSHIP) { it.paragraph(0, "body", "곧 좋은 사람을 만날 거예요.") })
		assertEquals("NOT_ADVICE_FORM", problem(MeReportArea.EMOTION) { it["shaken"] = mapOf("when" to "운명처럼 끌릴 때", "comfort" to "가") })
		// "보기" 도 "기" 로 끝난다(결정 037)
		assertNull(problem(MeReportArea.LOVE) { it["practices"] = listOf("말로 전해 보기", "말하기", "듣기").map { t -> mapOf("title" to t, "body" to "이유예요.") } })
	}

	@Test
	fun `고른 id 를 태어날 때의 근거로 바꾸고 별을 만들고 일에만 서버가 계산한 일 방식을 넣는다`() {
		val areas = MeReportArea.entries.map { meArea(parse(it), known, knownCandidates) }

		for (area in areas) {
			assertEquals(if (area.area == MeReportArea.LOVE) 0 else 4, area.paragraphs.size)
			for (paragraph in area.paragraphs) {
				assertEquals(ReasonWhen.BIRTH, paragraph.reason.`when`)
				assertEquals(listOf("P-SUN", "P-MOON").map { id -> knownCandidates.single { it.id == id }.item }, paragraph.reason.items)
			}
			assertEquals(area.area == MeReportArea.WORK, area.workStyle != null)
			assertEquals(area.area == MeReportArea.LOVE, area.reason != null)
		}
		val sun = known.positions[0].sign
		val moon = known.positions[1].sign
		assertEquals(listOf(Star(body = "SUN", sign = sun), Star(body = "MOON", sign = moon)), areas[0].stars, "문단 근거에서 겹치지 않게")
		val love = areas.single { it.area == MeReportArea.LOVE }
		assertEquals(ReasonWhen.BIRTH, love.reason!!.`when`)
		assertEquals(listOf(Star(body = "VENUS", sign = known.positions[3].sign)), love.stars, "연애는 영역의 근거에서")
		assertEquals(computeWorkStyle(known), areas.single { it.area == MeReportArea.WORK }.workStyle)
		assertTrue(isComplete(MeReport(areas)))
		assertFalse(isComplete(MeReport(areas.drop(1))), "영역이 빠졌다")
		assertFalse(isComplete(MeReport(areas.reversed())), "순서가 틀렸다")
	}

	@Test
	fun `응답 JSON 은 영역의 칸만 두고 시각을 모르면 하우스 키가 없다`() {
		val json = jacksonObjectMapper()
		val candidates = basisCandidates(unknown)
		val areas = MeReportArea.entries.map { area ->
			// 후보를 고루 쓴다(각도 · 다스리는 별까지)
			val draft = parse(area) { map ->
				if (area != MeReportArea.LOVE) for (i in 0..3) map.paragraph(i, "basis", listOf(candidates[(i * 3) % candidates.size].id))
				else map["basis"] = listOf(candidates.last().id)
			}
			meArea(draft, unknown, candidates)
		}
		val tree = json.valueToTree<JsonNode>(MeReport(areas))
		val expectedKeys = mapOf(
			MeReportArea.PERSONALITY to "glance",
			MeReportArea.LOVE to "practices,reason",
			MeReportArea.RELATIONSHIP to "closer",
			MeReportArea.WORK to "workStyle",
			MeReportArea.EMOTION to "shaken",
		)
		for (area in tree["areas"].values()) {
			val extra = expectedKeys.getValue(MeReportArea.valueOf(area["area"].asString())).split(",")
			assertEquals(listOf("area", "title", "keywords", "stars", "paragraphs") + extra, area.propertyNames().toList())
		}
		assertEquals(listOf("together", "flexible", "fast"), tree["areas"][3]["workStyle"].propertyNames().toList())
		assertEquals(listOf("when", "comfort"), tree["areas"][4]["shaken"].propertyNames().toList())
		val text = tree.toString()
		for (word in listOf("\"house\"", "\"ASC\"", "\"MC\"", "CLUSTER", "VERTEX")) assertFalse(text.contains(word), "시각 모름에 $word")
		assertEquals(MeReport(areas), json.treeToValue(tree, MeReport::class.java), "저장본을 다시 읽으면 같다")
	}

	@Test
	fun `요청 - 영역마다 지시문이 다르고 근거는 후보 id 에서만 고르며 일 방식은 묻지 않는다`() {
		val requests = MeReportArea.entries.associateWith { meAreaRequest(it, known, knownCandidates) }
		assertEquals(MeReportArea.entries.map { "me_report_${it.name.lowercase()}" }, requests.values.map { it.purpose })
		assertEquals(1, requests.values.map { it.userText }.distinct().size, "차트는 다섯 영역이 같다")
		assertEquals(5, requests.values.map { it.instructions }.distinct().size)
		for (request in requests.values) {
			assertTrue(request.userText.contains("[근거 후보]"))
			assertFalse(request.instructions.contains("계산되지 않았습니다"))
		}
		val schemas = requests.mapValues { (_, request) -> mapper.valueToTree<JsonNode>(request.schema) }
		assertEquals(listOf("title", "keywords", "paragraphs"), schemas.getValue(MeReportArea.WORK)["properties"].propertyNames().toList(), "일 방식은 모델이 쓰지 않는다")
		assertEquals(listOf("title", "keywords", "practices", "basis"), schemas.getValue(MeReportArea.LOVE)["properties"].propertyNames().toList(), "연애는 문단이 없다")
		val enum = schemas.getValue(MeReportArea.WORK)["properties"]["paragraphs"]["items"]["properties"]["basis"]["items"]["enum"].values().map { it.asString() }
		assertEquals(knownCandidates.map { it.id }, enum)
		assertEquals(enum, schemas.getValue(MeReportArea.LOVE)["properties"]["basis"]["items"]["enum"].values().map { it.asString() })

		val timeUnknown = meAreaRequest(MeReportArea.WORK, unknown, basisCandidates(unknown))
		assertTrue(timeUnknown.instructions.contains("하우스·상승점·중천·행운점·버텍스는 계산되지 않았습니다"))
		for (word in listOf("하우스", "상승점", "중천")) assertFalse(timeUnknown.userText.contains(word), word)
	}
}
