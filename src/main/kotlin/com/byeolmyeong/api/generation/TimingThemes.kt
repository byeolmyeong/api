package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.MilestoneKind
import com.byeolmyeong.api.chart.TransitTarget

// 시기의 표의 글. 모든 마디와 구간에 먼저 이 글을 붙이고, 모델에게 맡긴 줄만 모델의 글로 바꾼다(결정 008).
// 리포트 · 시기 풀이(report)와 이번 달(sky)이 같이 쓰므로 여기 둔다 — 구간의 이름은 이 표 하나다(결정 032 · 033).
// 글쓰기 규칙은 모델의 글과 같다: 점성술 용어·연도를 쓰지 않고, "이 시기의 주제"만 말하고, 무슨 일이 일어난다고 쓰지 않는다.
// 기회·운명적 만남의 제목은 앱 저장소 release-1.0.0 F4 가 정한 말이다.

/** 제목과 본문. */
data class Theme(val title: String, val body: String)

fun themeOf(kind: MilestoneKind): Theme = when (kind) {
	MilestoneKind.SATURN_SQUARE -> Theme(
		"가던 길을 스스로 점검해 보는 시기",
		"지금 가는 길이 정말 내 길인지 되묻게 되기 쉬운 때예요. 남의 기준보다 내가 책임질 수 있는 선택이 무엇인지 살펴볼 만해요.",
	)
	MilestoneKind.SATURN_RETURN -> Theme(
		"삶의 틀을 스스로 세워 보는 시기",
		"그동안 쌓아 온 것을 돌아보고 오래 가져갈 것을 고르게 되기 쉬운 때예요. 무엇을 책임지고 무엇을 내려놓을지 정리해 볼 만해요.",
	)
	MilestoneKind.URANUS_OPPOSITION -> Theme(
		"살아온 방식을 새로 고쳐 보는 시기",
		"익숙한 삶이 문득 좁게 느껴지기 쉬운 때예요. 미뤄 둔 바람을 꺼내 보고, 작게라도 새로운 방식을 시험해 볼 만해요.",
	)
	MilestoneKind.SECOND_SATURN_RETURN -> Theme(
		"쌓아 온 것을 다음으로 이어 보는 시기",
		"지금까지 맡아 온 역할을 돌아보고 다음 걸음을 그려 보게 되기 쉬운 때예요. 남기고 싶은 것과 가볍게 해도 되는 것을 가려 볼 만해요.",
	)
}

fun themeOf(interval: Interval): Theme = when (interval.kind) {
	IntervalKind.CHANGE -> changeTheme(interval.mover, interval.area)
	IntervalKind.OPPORTUNITY -> opportunityTheme(interval.target, interval.area)
	IntervalKind.FATED -> Theme(
		"운명처럼 느껴지는 만남이 생기기 쉬운 시기",
		"우연히 마주친 사람이 오래 마음에 남기 쉬운 때예요. 낯선 모임에 나가 보거나 스쳐 가는 인연에 조금 더 마음을 열어 볼 만해요.",
	)
}

// 변화 구간: 지나가는 행성이 주제를, 영역이 그 주제가 드러나는 곳을 정한다.
private fun changeTheme(mover: Body, area: LifeArea): Theme {
	val (where, focus) = when (area) {
		LifeArea.PEOPLE -> "관계에서" to "특히 가까운 사람들과의 사이에서 이 주제가 두드러지기 쉬워요."
		LifeArea.WORK -> "일에서" to "특히 일과 맡은 역할에서 이 주제가 두드러지기 쉬워요."
		LifeArea.MONEY -> "살림에서" to "특히 가진 것을 쓰고 지키는 방식에서 이 주제가 두드러지기 쉬워요."
		LifeArea.SELF -> "내 안에서" to "특히 나 자신을 대하는 태도에서 이 주제가 두드러지기 쉬워요."
	}
	val (theme, body) = when (mover) {
		Body.SATURN -> "기준을 다시 세워 보는 시기" to "책임의 무게를 다시 재어 보게 되기 쉬운 때예요. 서두르기보다 오래 갈 틀을 차근차근 다져 볼 만해요."
		Body.URANUS -> "익숙한 틀을 바꿔 보는 시기" to "늘 하던 방식이 답답하게 느껴지기 쉬운 때예요. 작은 것부터 새로운 방식을 시험해 볼 만해요."
		Body.NEPTUNE -> "바라는 것을 다시 그려 보는 시기" to "바라는 모습과 지금 사이의 거리가 눈에 들어오기 쉬운 때예요. 결정은 천천히 하고 정말 원하는 것을 살펴볼 만해요."
		else -> "오래된 것을 내려놓아 보는 시기" to "더는 맞지 않는 습관이나 역할을 돌아보게 되기 쉬운 때예요. 꼭 지키고 싶은 것과 놓아도 되는 것을 가려 볼 만해요."
	}
	return Theme("$where $theme", "$body $focus")
}

// 기회 구간: 영역이 주제를 정한다. 사람·인연은 무엇이 닿았는지에 따라 말을 달리한다.
private fun opportunityTheme(target: TransitTarget, area: LifeArea): Theme = when (area) {
	LifeArea.PEOPLE -> when (target) {
		TransitTarget.HOUSE_7 -> Theme(
			"인연이 열리기 쉬운 시기",
			"마주 앉는 한 사람과의 사이가 넓어지기 쉬운 때예요. 먼저 안부를 묻거나 약속을 잡아 볼 만해요.",
		)
		TransitTarget.HOUSE_11 -> Theme(
			"뜻이 맞는 사람들과 이어지기 쉬운 시기",
			"같은 것을 바라보는 사람들이 눈에 들어오기 쉬운 때예요. 관심 있던 모임에 한 번 얼굴을 비쳐 볼 만해요.",
		)
		TransitTarget.HOUSE_5 -> Theme(
			"좋아하는 것을 함께할 사람이 가까워지기 쉬운 시기",
			"즐거움을 나누는 자리에서 마음이 열리기 쉬운 때예요. 좋아하는 일을 혼자 하지 말고 누군가와 같이 해 볼 만해요.",
		)
		else -> Theme(
			"도움이 되는 사람을 만나기 쉬운 시기",
			"사람을 만나는 자리에서 뜻밖의 도움이 오가기 쉬운 때예요. 평소보다 한 걸음 더 다가가 볼 만해요.",
		)
	}
	LifeArea.WORK -> Theme(
		"일에서 기회를 넓혀 보기 좋은 시기",
		"해 보고 싶던 일에 손을 들기 좋은 때예요. 배우고 싶던 것을 시작하거나 맡는 범위를 조금 넓혀 볼 만해요.",
	)
	LifeArea.MONEY -> Theme(
		"살림을 넉넉하게 가꿔 보기 좋은 시기",
		"가진 것을 어떻게 쓰고 모을지 여유 있게 살펴보기 좋은 때예요. 큰 결정보다 생활의 씀씀이를 정돈해 볼 만해요.",
	)
	LifeArea.SELF -> Theme(
		"나를 넓혀 보기 좋은 시기",
		"마음에 여유가 생기고 새로운 것에 눈이 가기 쉬운 때예요. 미뤄 둔 배움이나 경험을 하나 시작해 볼 만해요.",
	)
}

/**
 * 시기 구간의 우선순위(작을수록 앞): 변화(큰) 0 > 변화(작은) 1 > 운명적 만남 2 > 기회 3.
 * "이번 달 내내"(결정 032)와 시기 풀이의 대표 구간 `mainId`(결정 033)가 같은 순서를 쓴다.
 */
fun intervalPriority(kind: IntervalKind, size: ChangeSize?): Int = when (kind) {
	IntervalKind.CHANGE -> if (size == ChangeSize.BIG) 0 else 1
	IntervalKind.FATED -> 2
	IntervalKind.OPPORTUNITY -> 3
}
