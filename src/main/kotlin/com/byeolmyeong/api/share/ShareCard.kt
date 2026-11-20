package com.byeolmyeong.api.share

import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.chart.Sign
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDate

// 공유 카드의 모양과, 공개해도 되는 꼴로 줄이는 두 가지(이름 → 이니셜, 날짜 → 해 + 계절).
// 응답 형태의 원본은 docs/architecture.md "공유", 고른 것의 이유는 docs/decisions/012-share-cards-snapshot.md.
// 여기 적힌 필드가 공개 조회(`GET /shared/{shareId}`)에 나가는 전부다. 근거·본문·출생 정보·소유자를 더하지 않는다.

enum class ShareKind { READING, SCORE, RANKING, NEXT_TURN, NICKNAME, ME_AREA, DAY_SKY, NEXT_BIG }

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

sealed interface ShareCard {
	val kind: ShareKind
}

/** 요약 해석 카드: 한 줄 총평과 키워드. */
data class ReadingCard(val headline: String, val keywords: List<String>) : ShareCard {
	override val kind get() = ShareKind.READING
}

/** 궁합 점수 카드. [names] 는 공유하는 사람, 상대의 순서다. [scoreLabel] 은 60점 미만일 때만 있다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScoreCard(val names: List<String>, val score: Int, val scoreLabel: String?, val headline: String) : ShareCard {
	override val kind get() = ShareKind.SCORE
}

data class RankingCardEntry(val name: String, val score: Int)

/** 랭킹 카드: 그 관계 유형의 점수순 [RANKING_TOP] 명까지. */
data class RankingCard(val relation: Relation, val entries: List<RankingCardEntry>) : ShareCard {
	override val kind get() = ShareKind.RANKING
}

/** 다음 변곡점 카드: 시기는 해와 계절까지만, 글은 주제 한 줄만. */
data class NextTurnCard(val year: Int, val season: Season, val theme: String) : ShareCard {
	override val kind get() = ShareKind.NEXT_TURN
}

// 공유 v2(결정 036): 닉네임 · 해 별자리 · 날짜 · 해와 만 나이가 들어간다. 출생 날짜 · 시각 · 곳 · 다른 별자리 · 도수는 넣지 않는다.
// [nickname] 은 계정의 닉네임 — 옛 계정이라 없으면 키가 없다.

/** 내 별명 카드: 지금 차트의 요약 해석 v2 의 한 줄 별명과 해 별자리. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NicknameCard(val nickname: String?, val sunSign: Sign, val headline: String) : ShareCard {
	override val kind get() = ShareKind.NICKNAME
}

/** 나 탭 영역 카드: 연애는 영역 제목, 나머지는 첫 문단 제목. [area] 는 영역 이름(PERSONALITY · LOVE · RELATIONSHIP · WORK · EMOTION). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MeAreaCard(val nickname: String?, val area: String, val title: String) : ShareCard {
	override val kind get() = ShareKind.ME_AREA
}

/** 그날의 하늘 카드: 그 날짜(YYYY-MM-DD)와 저장된 그날의 하늘 제목. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DaySkyCard(val nickname: String?, val date: String, val title: String) : ShareCard {
	override val kind get() = ShareKind.DAY_SKY
}

/** 다음 큰 변화 카드: 시기 풀이의 다음 큰 마디의 해 · 만 나이 · 제목. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NextBigCard(val nickname: String?, val year: Int, val age: Int, val title: String) : ShareCard {
	override val kind get() = ShareKind.NEXT_BIG
}

/** `POST /shares` 의 응답. [card] 는 공개 조회가 주는 것과 같다. */
data class Share(val shareId: String, val card: ShareCard)

const val RANKING_TOP = 3

/** 3~5월 봄, 6~8월 여름, 9~11월 가을, 12·1·2월 겨울. 해는 그 날의 달력 해를 그대로 쓴다. */
fun seasonOf(date: LocalDate): Season = when (date.monthValue) {
	in 3..5 -> Season.SPRING
	in 6..8 -> Season.SUMMER
	in 9..11 -> Season.AUTUMN
	else -> Season.WINTER
}

private const val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
private const val SYLLABLES_PER_CHOSEONG = 21 * 28
private const val NO_INITIALS = "○"

/**
 * 이름을 이니셜로 가린다. 한글 음절은 첫소리 글자로(지영 → ㅈㅇ), 이미 낱자인 한글은 그대로,
 * 그 밖의 글자·숫자는 이어진 덩어리의 첫 글자만 대문자로(Mike → M). 공백·기호·그림 문자는 뺀다. 남는 것이 없으면 `○`.
 */
fun initialsOf(name: String): String {
	val initials = StringBuilder()
	var inRun = false
	for (c in name) {
		val startsRun = when {
			c in '가'..'힣' -> {
				initials.append(CHOSEONG[(c - '가') / SYLLABLES_PER_CHOSEONG])
				false
			}
			c in 'ㄱ'..'ㅎ' -> {
				initials.append(c)
				false
			}
			c.isLetterOrDigit() -> {
				if (!inRun) initials.append(c.uppercaseChar())
				true
			}
			else -> false
		}
		inRun = startsRun
	}
	return initials.toString().ifEmpty { NO_INITIALS }
}
