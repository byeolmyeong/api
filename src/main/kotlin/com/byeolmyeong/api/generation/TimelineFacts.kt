package com.byeolmyeong.api.generation

import com.byeolmyeong.api.chart.AspectType
import com.byeolmyeong.api.chart.Body
import com.byeolmyeong.api.chart.ChangeSize
import com.byeolmyeong.api.chart.Interval
import com.byeolmyeong.api.chart.IntervalKind
import com.byeolmyeong.api.chart.LifeArea
import com.byeolmyeong.api.chart.Milestone
import com.byeolmyeong.api.chart.MilestoneKind
import com.byeolmyeong.api.chart.PointKind
import com.byeolmyeong.api.chart.TransitTarget

// 서버가 계산한 시기(마디·구간) 한 줄을 모델이 읽을 글로 옮긴다. 언제인지(해·날짜·나이)는 넣지 않는다 — 넣을지는 쓰는 쪽이 정한다.
// 심층 리포트의 시기 글과 방향성 제시(미래)가 같이 쓴다(결정 011).

private fun sizeLabel(size: ChangeSize): String = if (size == ChangeSize.BIG) "큰 변화" else "작은 변화"

/** 마디 하나를 모델이 읽을 글로: 종류(크기) · 무엇이 무엇에 닿는지 · 영역. */
fun timelineFact(milestone: Milestone): String {
	val what = when (milestone.kind) {
		MilestoneKind.SATURN_SQUARE -> "${Body.SATURN.label}이 태어날 때의 제자리와 직각을 이룸"
		MilestoneKind.SATURN_RETURN -> "${Body.SATURN.label}이 태어날 때의 제자리로 처음 돌아옴"
		MilestoneKind.URANUS_OPPOSITION -> "${Body.URANUS.label}이 태어날 때의 제자리 맞은편에 옴"
		MilestoneKind.SECOND_SATURN_RETURN -> "${Body.SATURN.label}이 태어날 때의 제자리로 두 번째 돌아옴"
	}
	return "마디(${sizeLabel(milestone.size)}) | $what | 영역: 삶 전반"
}

/** 구간 하나를 모델이 읽을 글로: 종류(크기) · 무엇이 무엇에 닿는지 · 영역. */
fun timelineFact(interval: Interval): String {
	val kind = when (interval.kind) {
		IntervalKind.CHANGE -> "변화 구간(${sizeLabel(checkNotNull(interval.size))})"
		IntervalKind.OPPORTUNITY -> "기회 구간"
		IntervalKind.FATED -> "운명적 만남 구간"
	}
	val target = when (interval.target) {
		TransitTarget.SUN -> Body.SUN.label
		TransitTarget.MOON -> Body.MOON.label
		TransitTarget.VENUS -> Body.VENUS.label
		TransitTarget.ASCENDANT -> ASCENDANT_LABEL
		TransitTarget.MIDHEAVEN -> MIDHEAVEN_LABEL
		TransitTarget.VERTEX -> PointKind.VERTEX.label
		TransitTarget.HOUSE_5 -> houseLabel(5)
		TransitTarget.HOUSE_7 -> houseLabel(7)
		TransitTarget.HOUSE_11 -> houseLabel(11)
	}
	val how = when (interval.aspect) {
		AspectType.CONJUNCTION -> "겹침"
		AspectType.SQUARE -> "직각으로 부딪힘"
		AspectType.OPPOSITION -> "맞은편에서 마주 봄"
		AspectType.TRINE -> "서로 도움"
		AspectType.SEXTILE -> error("육분은 구간에 없다")
		null -> "지나감"
	}
	val area = when (interval.area) {
		LifeArea.PEOPLE -> "사람·인연"
		LifeArea.WORK -> "일"
		LifeArea.MONEY -> "돈"
		LifeArea.SELF -> "나 자신"
	}
	return "$kind | ${interval.mover.label} – $target: $how | 영역: $area"
}
