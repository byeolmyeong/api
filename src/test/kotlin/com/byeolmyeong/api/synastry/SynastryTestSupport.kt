package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.birth.Places
import com.byeolmyeong.api.birth.toUtc
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.computeChart
import com.byeolmyeong.api.chart.synastryScore
import com.byeolmyeong.api.generation.GenerationRequest
import com.byeolmyeong.api.generation.TextGenerator
import com.byeolmyeong.api.http
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// 궁합 통합 테스트가 함께 쓰는 것: 가짜 생성기와, 서버와 같은 계산으로 기대값을 구하는 도우미.

const val MAPO = 1841277
const val SUMMARY_PURPOSE = "synastry_summary"
val REPORT_AREAS = listOf("ATTRACTION", "TALK", "CONFLICT", "LASTING")

val mapper = JsonMapper()

/** 나: 시각을 아는 프로필. */
const val MY_DATE = "1990-03-15"
const val MY_TIME = "14:30"
const val ME = """{"date":"$MY_DATE","time":"$MY_TIME","placeId":$MAPO}"""

fun chartOf(date: String, time: String?): Chart {
	val place = Places().find(MAPO)!!
	return computeChart(toUtc(LocalDate.parse(date), time?.let(LocalTime::parse)), place.lat, place.lon, timeKnown = time != null)
}

/** 서버와 같은 계산으로 구한 점수(상대는 시각 모름). */
fun expectedScore(partnerDate: String, relation: Relation, myTime: String? = MY_TIME, partnerTime: String? = null): Int =
	synastryScore(chartOf(MY_DATE, myTime), chartOf(partnerDate, partnerTime), relation)

/** 조건에 맞는 점수가 나오는 상대의 생일(시각 모름)을 1985-01-01 부터 하루씩 찾는다. [skip] 개는 건너뛴다. */
fun partnerDate(relation: Relation = Relation.LOVE, skip: Int = 0, wanted: (Int) -> Boolean): String =
	generateSequence(LocalDate.of(1985, 1, 1)) { it.plusDays(1) }
		.map { it.toString() }
		.filter { wanted(expectedScore(it, relation)) }
		.drop(skip)
		.first()

fun directBody(name: String = "지우", relation: String = "LOVE", date: String = "1988-07-21", time: String? = null, placeId: Int? = null): String =
	mapper.writeValueAsString(
		buildMap {
			put("name", name)
			put("relation", relation)
			put("date", date)
			put("time", time)
			if (placeId != null) put("placeId", placeId)
		},
	)

fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

fun summaryJson(keywords: List<String> = listOf("가짜키워드하나", "가짜키워드둘", "가짜키워드셋"), headline: String = "가짜한줄요약"): JsonNode =
	mapper.valueToTree(mapOf("keywords" to keywords, "headline" to headline))

/** 리포트 영역 요청의 구조(스키마)에서 근거로 고를 수 있게 한 말. */
fun vocabularyOf(request: GenerationRequest): List<String> = mapper.valueToTree<JsonNode>(request.schema)
	.get("properties").get("paragraphs").get("items").get("properties").get("basis").get("items").get("enum")
	.values().map { it.asString() }

fun areaOf(request: GenerationRequest): String = request.purpose.removePrefix("synastry_report_").uppercase()

/** 오래가기 영역의 작은 실천 셋(결정 053 의 3). */
val PRACTICES = listOf("가짜실천하나 해 보기", "가짜실천둘 해 보기", "가짜실천셋 해 보기")

fun areaJson(
	request: GenerationRequest,
	call: Int,
	count: Int = 3,
	basis: List<String> = vocabularyOf(request).take(2),
	practices: List<String> = PRACTICES,
): JsonNode = mapper.valueToTree(
	buildMap {
		put("title", "가짜영역제목")
		put("paragraphs", (1..count).map { mapOf("title" to "가짜문단제목$it", "body" to "가짜본문-$call", "basis" to basis) })
		if (areaOf(request) == "LASTING") put("practices", practices)
	},
)

/** `POST /synastry` 로 만들고(202) [expected] 가 될 때까지 `GET /synastry/{id}` 를 다시 불러 그 응답을 준다. */
fun createSynastry(port: Int, token: String, body: String, expected: String = "DONE"): JsonNode {
	val response = http(port, "POST", "/synastry", body, token)
	check(response.statusCode() == 202) { "만들기가 202 가 아니다: ${response.statusCode()} ${response.body()}" }
	return awaitSynastry(port, token, mapper.readTree(response.body())["id"].asString(), expected)
}

/** [expected] 상태가 될 때까지 `GET /synastry/{id}` 를 다시 부른다. */
fun awaitSynastry(port: Int, token: String, id: String, expected: String = "DONE"): JsonNode {
	val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
	while (true) {
		val response = http(port, "GET", "/synastry/$id", token = token)
		check(response.statusCode() == 200) { "읽기가 200 이 아니다: ${response.statusCode()} ${response.body()}" }
		val json = mapper.readTree(response.body())
		if (json["status"].asString() == expected) return json
		check(System.nanoTime() < deadline) { "상태가 $expected 가 되지 않는다: ${json["status"]}" }
		Thread.sleep(20)
	}
}

fun validAnswer(request: GenerationRequest, call: Int): JsonNode? =
	if (request.purpose == SUMMARY_PURPOSE) summaryJson() else areaJson(request, call)

/** 실제 생성기 대신 끼우는 가짜. 받은 요청을 기억하고, 문([gate])이 닫혀 있으면 리포트 영역 요청은 열릴 때까지 답하지 않는다. */
class FakeSynastryGenerator : TextGenerator {
	val requests = CopyOnWriteArrayList<GenerationRequest>()

	@Volatile
	var respond: (GenerationRequest, Int) -> JsonNode? = ::validAnswer

	@Volatile
	var gate: CountDownLatch? = null

	override fun generate(request: GenerationRequest): JsonNode? {
		requests += request
		val call = requests.size
		if (request.purpose != SUMMARY_PURPOSE) gate?.await(30, TimeUnit.SECONDS)
		return respond(request, call)
	}

	val summaries get() = requests.filter { it.purpose == SUMMARY_PURPOSE }
	val areas get() = requests.filter { it.purpose != SUMMARY_PURPOSE }

	fun reset() {
		requests.clear()
		respond = ::validAnswer
		gate = null
	}
}

@TestConfiguration(proxyBeanMethods = false)
class FakeSynastryConfig {
	@Bean
	@Primary
	fun fakeSynastryGenerator() = FakeSynastryGenerator()
}
