package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.SkyTransit
import java.util.zip.CRC32

// 이번 달 문구표(결정 032): 눈여겨볼 날의 칩 · 문구. 원본은 docs/drafts/2026-11-20-month-phrases.md 3 · 7 · 8 · 9절
// (소유자 2026-11-20 "초안 그대로 써" — 글자까지 초안 그대로). 이번 달(sky)과 그날의 하늘(계획 S④)이 같이 쓴다.
// 문구를 고치는 것은 결정이 아니라 데이터 수정이다 — 같은 날의 문구가 바뀔 수 있음을 PR 에 적는다(결정 032 의 결과).

/** 눈여겨볼 날의 칩: 사람 · 일 · 마음. */
enum class DayCategory { PEOPLE, WORK, MIND }

/** 각도의 결: 합(그 자리에 힘이 모인다) · 어울림(육분 · 삼분) · 부딪힘(사분 · 충). */
enum class AspectTone { CONJ, HARMONY, TENSION }

fun toneOf(type: AspectType): AspectTone = when (type) {
	AspectType.CONJUNCTION -> AspectTone.CONJ
	AspectType.SEXTILE, AspectType.TRINE -> AspectTone.HARMONY
	AspectType.SQUARE, AspectType.OPPOSITION -> AspectTone.TENSION
}

// 칩 표(초안 3절): 지나가는 별마다 내 점 열둘(이 순서)의 칩. 각도와 상관없다.
private val CATEGORY_TARGETS = listOf("SUN", "MOON", "MERCURY", "VENUS", "MARS", "JUPITER", "SATURN", "URANUS", "NEPTUNE", "PLUTO", "ASC", "MC")
private val P = DayCategory.PEOPLE
private val W = DayCategory.WORK
private val M = DayCategory.MIND
private val CATEGORIES: Map<Body, List<DayCategory>> = mapOf(
	Body.SUN to listOf(M, M, P, P, W, W, W, M, M, M, P, W),
	Body.MERCURY to listOf(P, P, P, P, W, W, W, W, M, W, P, W),
	Body.VENUS to listOf(P, M, P, P, P, P, P, M, M, M, P, W),
	Body.MARS to listOf(W, M, W, P, W, W, W, W, M, W, W, W),
	Body.JUPITER to listOf(M, M, W, P, W, W, W, M, M, M, P, W),
)

/** 지나가는 별 × 내 점의 칩(초안 3절). [target] 은 `Body` · `ChartAngle` 의 이름. */
fun dayCategory(mover: Body, target: String): DayCategory {
	val column = CATEGORY_TARGETS.indexOf(target)
	require(column >= 0) { "내 점이 아니다" }
	return CATEGORIES.getValue(mover)[column]
}

/**
 * 문구표: 칸 id(`{지나가는 별}.{내 별}.{결}` — 대표 조합 33칸, `{지나가는 별}.ANY.{결}` — 기본 15칸) → 문구 1~n번(3~5개).
 * 대표 조합의 칸이 없으면 기본 칸을 쓴다(초안 9절).
 */
val MONTH_PHRASES: Map<String, List<String>> = mapOf(
	"SUN.MOON.HARMONY" to listOf("마음과 할 일이 한 방향을 보기 쉬운 날", "하고 싶은 일을 편하게 시작하기 좋은 날", "집이 유난히 편안하게 느껴지기 쉬운 날", "내 마음을 있는 그대로 받아들이기 좋은 날"),
	"SUN.MOON.TENSION" to listOf("마음이 바라는 것을 먼저 챙기기 좋은 날", "할 일과 쉼 사이의 순서를 정하기 좋은 날", "오르내리는 기분을 너그럽게 봐 주기 좋은 날"),
	"SUN.MERCURY.CONJ" to listOf("내 생각을 또렷하게 말하기 좋은 날", "미뤄 둔 연락을 하기 좋은 날", "생각을 말로 꺼내기 쉬운 날"),
	"SUN.VENUS.HARMONY" to listOf("마음을 표현하기 쉬운 날", "좋아하는 사람과 시간을 보내기 좋은 날", "작은 호의가 반갑게 오가기 쉬운 날", "나를 꾸미는 일이 즐거운 날"),
	"SUN.MARS.CONJ" to listOf("미뤄 둔 일을 밀고 나가기 좋은 날", "힘이 붙어 새 일을 시작하기 좋은 날", "망설이던 일에 먼저 손을 들기 좋은 날"),
	"SUN.SATURN.TENSION" to listOf("욕심을 줄이고 하나에 집중하기 좋은 날", "해야 할 일의 순서를 다시 세우기 좋은 날", "더딘 일도 차근차근 해 나가기 좋은 날", "혼자 짊어진 일을 나눠 보기 좋은 날"),
	"SUN.NEPTUNE.CONJ" to listOf("혼자만의 시간이 반가운 날", "상상에 마음껏 빠져 보기 좋은 날", "좋아하는 음악이나 영화에 젖기 좋은 날", "큰 결정은 미뤄 두기 좋은 날"),
	"SUN.ASC.CONJ" to listOf("나다운 모습이 잘 드러나기 쉬운 날", "처음 만나는 자리에서 편하게 나서기 좋은 날", "새로운 모습으로 나서 보기 좋은 날"),
	"SUN.MC.CONJ" to listOf("내가 하는 일이 눈에 띄기 쉬운 날", "앞으로의 목표를 다시 그려 보기 좋은 날", "해 온 일을 드러내 보이기 좋은 날"),
	"MERCURY.SUN.CONJ" to listOf("생각이 또렷해지기 쉬운 날", "내 생각을 말로 정리해 보기 좋은 날", "중요한 연락을 하기 좋은 날"),
	"MERCURY.SUN.HARMONY" to listOf("대화가 잘 풀리기 쉬운 날", "하고 싶던 이야기를 꺼내기 좋은 날", "내 생각에 귀 기울여 주는 사람이 반가운 날", "배운 것을 나누기 좋은 날"),
	"MERCURY.MOON.HARMONY" to listOf("속마음을 편하게 이야기하기 좋은 날", "가까운 사람과의 수다가 반가운 날", "오래 연락 못 한 사람에게 안부를 묻기 좋은 날"),
	"MERCURY.MERCURY.TENSION" to listOf("말을 한 번 고르고 꺼내기 좋은 날", "오해가 생기면 바로 되묻기 좋은 날", "약속과 일정을 한 번 더 살펴보기 좋은 날"),
	"MERCURY.VENUS.CONJ" to listOf("다정한 말이 잘 전해지기 쉬운 날", "고마운 마음을 말로 전하기 좋은 날", "좋아하는 것에 대해 이야기하기 좋은 날"),
	"MERCURY.MARS.TENSION" to listOf("급한 말보다 한 박자 쉬어 가기 좋은 날", "의견이 부딪혀도 요점만 짚기 좋은 날", "서두르지 말고 한 번 더 확인하기 좋은 날"),
	"MERCURY.JUPITER.HARMONY" to listOf("새로운 것을 배우기 좋은 날", "큰 그림을 그려 보기 좋은 날", "떠오른 생각을 나누기 좋은 날", "넓게 생각해 보기 좋은 날"),
	"MERCURY.SATURN.HARMONY" to listOf("하던 일을 정리하기 좋은 날", "계획을 꼼꼼히 세우기 좋은 날", "차분히 집중해 공부하기 좋은 날", "미뤄 둔 서류를 처리하기 좋은 날"),
	"VENUS.SUN.CONJ" to listOf("나를 아끼는 마음이 커지기 쉬운 날", "사람들과 어울리는 일이 즐거운 날", "나에게 작은 기쁨을 주기 좋은 날"),
	"VENUS.MOON.HARMONY" to listOf("마음이 포근해지기 쉬운 날", "좋아하는 사람 곁에서 편안해지기 쉬운 날", "집을 아늑하게 가꾸기 좋은 날", "맛있는 것을 나누기 좋은 날"),
	"VENUS.VENUS.CONJ" to listOf("좋아하는 것이 또렷해지기 쉬운 날", "아름다운 것을 가까이 두기 좋은 날", "관계의 즐거움을 다시 느끼기 쉬운 날"),
	"VENUS.MARS.HARMONY" to listOf("마음이 가는 사람에게 다가가기 좋은 날", "설렘이 반가운 날", "함께 활동하며 가까워지기 쉬운 날"),
	"VENUS.SATURN.TENSION" to listOf("서운한 마음을 차분히 말해 보기 좋은 날", "관계에서 바라는 것을 정리하기 좋은 날", "혼자 있는 시간도 괜찮다고 여기기 좋은 날", "기대를 조금 내려놓기 좋은 날"),
	"VENUS.MC.HARMONY" to listOf("일터에서 호감을 얻기 쉬운 날", "함께 일하는 사람과 사이가 부드러워지기 쉬운 날", "일에 나만의 감각을 더하기 좋은 날"),
	"MARS.SUN.CONJ" to listOf("힘이 넘쳐 무엇이든 시작하기 좋은 날", "미뤄 둔 일에 먼저 덤벼 보기 좋은 날", "하고 싶은 것을 분명히 말하기 좋은 날"),
	"MARS.SUN.TENSION" to listOf("서두르기보다 한 박자 늦추기 좋은 날", "부딪히는 일은 잠시 두고 숨을 고르기 좋은 날", "힘을 한곳에 모으기 좋은 날"),
	"MARS.MOON.TENSION" to listOf("욱하는 마음을 잠깐 내려놓기 좋은 날", "마음이 바쁠 때 잠깐 쉬어 가기 좋은 날", "가까운 사람에게 말을 부드럽게 하기 좋은 날"),
	"MARS.MERCURY.TENSION" to listOf("급한 답장보다 한 번 더 읽어 보기 좋은 날", "결정은 한 번 더 생각하고 내리기 좋은 날", "날 선 말을 부드럽게 고쳐 말하기 좋은 날"),
	"MARS.VENUS.HARMONY" to listOf("마음을 먼저 표현하기 좋은 날", "좋아하는 일에 열정이 붙기 쉬운 날", "함께 새로운 곳에 가 보기 좋은 날"),
	"MARS.SATURN.HARMONY" to listOf("꾸준히 밀고 가기 좋은 날", "오래 걸리던 일을 끝까지 해내기 좋은 날", "계획한 대로 차근차근 해내기 쉬운 날"),
	"MARS.NEPTUNE.TENSION" to listOf("무리하지 말고 쉬어 가기 좋은 날", "흐릿한 일은 다음으로 미뤄 두기 좋은 날", "혼자 조용히 마음을 고르기 좋은 날"),
	"JUPITER.SUN.CONJ" to listOf("마음이 넉넉해지기 쉬운 날", "나를 믿고 한 걸음 내디디기 좋은 날", "새로운 가능성을 떠올려 보기 좋은 날"),
	"JUPITER.VENUS.HARMONY" to listOf("사람들과의 자리가 즐거운 날", "작은 친절을 베풀기 좋은 날", "좋아하는 사람과 기쁨을 나누기 좋은 날"),
	"JUPITER.MC.HARMONY" to listOf("일에서 한 걸음 넓혀 보기 좋은 날", "해 보고 싶던 일에 손을 들기 좋은 날", "도움을 청하면 잘 이어지기 쉬운 날"),
	"SUN.ANY.CONJ" to listOf("나에게 집중하기 좋은 날", "내가 원하는 것을 또렷이 하기 좋은 날", "새로 마음먹기 좋은 날"),
	"SUN.ANY.HARMONY" to listOf("하는 일이 순조롭기 쉬운 날", "나답게 움직이기 쉬운 날", "마음먹은 일을 해 보기 좋은 날"),
	"SUN.ANY.TENSION" to listOf("욕심을 덜고 하나만 고르기 좋은 날", "나를 너그럽게 대하기 좋은 날", "계획을 한 번 고쳐 보기 좋은 날"),
	"MERCURY.ANY.CONJ" to listOf("생각을 정리하기 좋은 날", "연락을 주고받기 좋은 날", "메모를 남겨 두기 좋은 날"),
	"MERCURY.ANY.HARMONY" to listOf("이야기가 술술 풀리기 쉬운 날", "배우고 묻기 좋은 날", "아이디어를 나누기 좋은 날"),
	"MERCURY.ANY.TENSION" to listOf("말하기 전에 한 번 더 듣기 좋은 날", "일정을 다시 확인하기 좋은 날", "짧은 답장보다 직접 묻기 좋은 날"),
	"VENUS.ANY.CONJ" to listOf("좋아하는 것을 즐기기 좋은 날", "마음을 부드럽게 쓰기 좋은 날", "주변을 예쁘게 가꾸기 좋은 날"),
	"VENUS.ANY.HARMONY" to listOf("사람 사이가 다정해지기 쉬운 날", "작은 즐거움이 반가운 날", "함께 웃는 시간이 반가운 날"),
	"VENUS.ANY.TENSION" to listOf("바라는 것을 솔직하게 말해 보기 좋은 날", "비교하는 마음을 내려놓기 좋은 날", "나를 먼저 챙기기 좋은 날"),
	"MARS.ANY.CONJ" to listOf("힘을 내어 시작하기 좋은 날", "부지런히 움직이기 좋은 날", "망설임을 접고 해 보기 좋은 날"),
	"MARS.ANY.HARMONY" to listOf("하던 일에 속도가 붙기 쉬운 날", "용기를 내기 좋은 날", "앞장서 보기 좋은 날"),
	"MARS.ANY.TENSION" to listOf("급할수록 한 번 쉬어 가기 좋은 날", "다툼보다 거리를 두기 좋은 날", "힘을 아껴 두기 좋은 날"),
	"JUPITER.ANY.CONJ" to listOf("마음을 크게 먹기 좋은 날", "배움을 시작하기 좋은 날", "넓게 바라보기 좋은 날"),
	"JUPITER.ANY.HARMONY" to listOf("여유가 반가운 날", "기회에 손을 들기 좋은 날", "도움을 주고받기 좋은 날"),
	"JUPITER.ANY.TENSION" to listOf("욕심의 크기를 재어 보기 좋은 날", "약속을 줄이기 좋은 날", "할 수 있는 만큼만 맡기 좋은 날"),
)

/** 고른 문구. [id] 는 `{칸 id}.{번호}`(예: `MERCURY.SUN.HARMONY.2`). */
data class DayPhrase(val id: String, val text: String, val category: DayCategory)

// 조합의 칸 id — 번호의 해시에 쓴다(조합 칸이 없어 기본 칸을 쓸 때도).
private fun combinationCell(transit: SkyTransit): String = "${transit.mover}.${transit.target}.${toneOf(transit.type)}"

// 실제로 문구를 꺼내는 칸: 조합 칸이 없으면 기본 칸.
private fun usedCell(transit: SkyTransit): String =
	combinationCell(transit).takeIf { it in MONTH_PHRASES } ?: "${transit.mover}.ANY.${toneOf(transit.type)}"

// 번호(0부터) = CRC32("{YYYY-MM-DD}|{조합 칸 id}") mod 그 칸의 문구 수(초안 7절). 같은 날 · 같은 조합은 늘 같은 번호다.
private fun firstIndex(transit: SkyTransit, size: Int): Int {
	val crc = CRC32().apply { update("${transit.date}|${combinationCell(transit)}".toByteArray(Charsets.UTF_8)) }.value
	return (crc % size).toInt()
}

private fun phraseAt(transit: SkyTransit, cell: String, index: Int): DayPhrase =
	DayPhrase("$cell.${index + 1}", MONTH_PHRASES.getValue(cell)[index], dayCategory(transit.mover, transit.target))

/** 그날 · 그 조합의 문구 하나(초안 7절). 그날의 하늘이 눈여겨볼 날이면 이번 달 목록과 같은 말이다. */
fun dayPhrase(transit: SkyTransit): DayPhrase {
	val cell = usedCell(transit)
	return phraseAt(transit, cell, firstIndex(transit, MONTH_PHRASES.getValue(cell).size))
}

/** 한 달의 눈여겨볼 날(날짜 순)의 문구. 같은 글자가 다시 나오면 뒤의 것을 그 칸의 다음 번호로 바꾼다(끝이면 처음으로). */
fun monthPhrases(days: List<SkyTransit>): List<DayPhrase> {
	val used = mutableSetOf<String>()
	return days.map { transit ->
		val cell = usedCell(transit)
		val size = MONTH_PHRASES.getValue(cell).size
		val first = firstIndex(transit, size)
		val phrase = (0 until size).map { phraseAt(transit, cell, (first + it) % size) }.first { it.text !in used }
		used += phrase.text
		phrase
	}
}
