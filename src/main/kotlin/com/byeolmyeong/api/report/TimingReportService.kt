package com.byeolmyeong.api.report

import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.birth.ChartInput
import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * 시기 풀이(결정 029 · 033). 마디 · 구간을 계산하고(평생 한 번에 약 0.3초 — 새로 시작할 때만, 자리를 잡기 전에) 모델에게 맡길 줄을 9줄씩 묶어
 * 약 4번을 한꺼번에 부른다. 모든 묶음이 검증을 통과해야 저장한다. 구간 이름은 표의 제목 그대로다.
 * "지금" 과 대표 구간(`current.mainId`)은 저장하지 않고 읽을 때 오늘(한국 날짜)로 정한다. 자리 잡기 · 심장박동 · 끝내기는 `DetailReportService`.
 */
@Service
class TimingReportService(private val profiles: BirthProfileService, private val details: DetailReportService) {

	/** 지금 차트의 시기 풀이 상태. 프로필이 없거나 만든 적이 없으면 `NONE`. */
	fun current(ownerId: UUID): TimingReportStatusResponse {
		val birth = profiles.chartInput(ownerId) ?: return TimingReportStatusResponse(ReportStatus.NONE)
		val (status, timing) = details.find(ownerId, DetailReportKind.TIMING, birth.hash, ReportTiming::class.java)
			?: return TimingReportStatusResponse(ReportStatus.NONE)
		return TimingReportStatusResponse(status, timing?.let { timingReportNow(it, LocalDate.now(KOREA_ZONE)) })
	}

	/** 지금 차트의 시기 풀이를 만들기 시작한다. 이미 완성됐거나 쓰는 중이면 시작하지 않고 그 상태를 돌려준다. [appVersion] 은 나 탭 풀이와 같다. */
	fun request(ownerId: UUID, appVersion: String?): ReportStatus {
		val birth = profiles.chartInput(ownerId) ?: throw NotFoundException("NO_PROFILE")
		return details.request(ownerId, DetailReportKind.TIMING, birth.hash, appVersion) { timingJob(birth) }
	}

	private fun timingJob(birth: ChartInput): DetailJob<TimingBatchTexts> {
		val chart = birth.chart()
		val draft = computeTiming(chart, birth.utc)
		val batches = timingBatches(timingItemsForModel(draft, LocalDate.now(KOREA_ZONE)))
		return DetailJob(
			parts = batches.mapIndexed { index, batch ->
				DetailPart("BATCH${index + 1}", timingReportRequest(chart, batch, draft.timing), ::parseTimingBatch) { timingBatchProblem(it, batch) }
			},
			assemble = { texts -> draft.timing.withReportTexts(texts, draft.sources) },
			done = ::TimingReportDone,
		)
	}
}
