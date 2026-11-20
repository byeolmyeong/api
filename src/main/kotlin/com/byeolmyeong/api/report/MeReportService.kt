package com.byeolmyeong.api.report

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.chart.Chart
import com.byeolmyeong.api.generation.basisCandidates
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 나 탭 풀이(결정 029 · 030). 조각은 영역 다섯(한꺼번에)이고, 다섯 영역이 모두 검증을 통과해야 저장한다.
 * 자리 잡기 · 심장박동 · 그만두기 · 끝내기는 시기 풀이와 같은 고리(`DetailReportService`)가 한다.
 */
@Service
class MeReportService(private val profiles: BirthProfileService, private val details: DetailReportService) {

	/** 지금 차트의 나 탭 풀이 상태. 프로필이 없거나 만든 적이 없으면 `NONE`. */
	fun current(ownerId: UUID): MeReportStatusResponse {
		val birth = profiles.chartInput(ownerId) ?: return MeReportStatusResponse(ReportStatus.NONE)
		val (status, report) = details.find(ownerId, DetailReportKind.ME, birth.hash, MeReport::class.java) ?: return MeReportStatusResponse(ReportStatus.NONE)
		return MeReportStatusResponse(status, report)
	}

	/** 지금 차트의 나 탭 풀이를 만들기 시작한다. 이미 완성됐거나 쓰는 중이면 시작하지 않고 그 상태를 돌려준다. 차트는 새로 시작할 때만 계산한다. */
	fun request(ownerId: UUID): ReportStatus {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		return details.request(ownerId, DetailReportKind.ME, birth.hash) { meJob(birth.chart()) }
	}

	private fun meJob(chart: Chart): DetailJob<MeAreaDraft> {
		val candidates = basisCandidates(chart)
		val candidateIds = candidates.map { it.id }.toSet()
		return DetailJob(
			parts = MeReportArea.entries.map { area ->
				DetailPart(area.name, meAreaRequest(area, chart, candidates), { parseMeArea(area, it) }, { meAreaProblem(it, candidateIds) })
			},
			assemble = { drafts -> MeReport(drafts.map { meArea(it, chart, candidates) }).takeIf(::isComplete) },
			done = ::MeReportDone,
		)
	}
}
