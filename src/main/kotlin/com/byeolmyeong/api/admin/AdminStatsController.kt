package com.byeolmyeong.api.admin

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.web.BadRequestException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private val REPORT_STATUSES = listOf("DONE", "WRITING", "FAILED")

/** 궁합의 쓰는 중이 멈췄다고 보는 기준(초). `synastry.STALE_SECONDS` 와 같다(결정 053). */
private const val SYNASTRY_STALE_SECONDS = 120
private val INQUIRY_STATUSES = listOf("NEW", "IN_PROGRESS", "ANSWERED", "DONE")
private val RANGES = setOf("today", "week", "month")

data class Total(val total: Int)
data class AccountStats(val total: Int, val last7Days: Int, val last30Days: Int)
data class StatusStats(val total: Int, val byStatus: Map<String, Int>)
data class TopicStats(val total: Int, val byTopic: Map<String, Int>)

/** 앱 사용자 계정 — 전체, 오늘(한국 날짜) 가입, 오늘 포함 최근 7일(한국 날짜) 가입. */
data class UserStats(val total: Int, val joinedToday: Int, val joined7d: Int)

/** 자세한 풀이(`detail_reports`): 완성된 나 탭 풀이 · 시기 풀이, 쓰는 중(`WRITING`). */
data class DetailReportStats(val me: Int, val timing: Int, val generating: Int)

/** 오늘(한국 날짜)의 생성 실패. [nickname] 은 실패가 저장되지 않아 셀 수 없다(언제나 null). */
data class FailureStats(val nickname: Int?, val report: Int)

/** 답을 기다리는 문의 수와 가장 오래 기다린 것의 만든 때(없으면 null). */
data class InquiryQueue(val waiting: Int, val oldestWaitingAt: String?)

/** 동의 비율(소수 셋째 자리)과 광고 푸시를 받을 수 있는 계정 수. */
data class ConsentStats(val pushRate: Double, val marketingRate: Double, val adReachable: Int)

/**
 * 현황 수치. 개수 · 비율뿐이다 — 사람별 행, 출생 정보, 글은 없다.
 * `users` 부터는 현황 v2(결정 043 의 3)가 더한 칸이다. [todaySky] · [pushToday] 는 셀 테이블이 아직 없어 언제나 null 이다.
 */
data class AdminStats(
	val generatedAt: String,
	val accounts: AccountStats,
	val profiles: Total,
	val readings: Total,
	val reports: StatusStats,
	val synastries: Total,
	val synastryReports: StatusStats,
	val directions: TopicStats,
	val inquiries: StatusStats,
	val users: UserStats,
	val nicknames: Total,
	val detailReports: DetailReportStats,
	val failuresToday: FailureStats,
	val inquiryQueue: InquiryQueue,
	val consents: ConsentStats,
	val todaySky: Nothing? = null,
	val pushToday: Nothing? = null,
)

data class ActivePoint(val label: String, val value: Int)

/** 접속 차트(결정 043 의 4). [average] 는 [points] 값의 평균(소수 첫째 자리)이다. */
data class ActiveChart(val range: String, val points: List<ActivePoint>, val total: Int, val average: Double, val previousTotal: Int)

@RestController
class AdminStatsController(private val stats: AdminStatsService) {

	@GetMapping("/admin/stats")
	fun get(): AdminStats = stats.get()

	/** `range` 는 `today` · `week` · `month` 중 하나(소문자). 없거나 틀리면 400 `INVALID_RANGE`. */
	@GetMapping("/admin/stats/active")
	fun active(@RequestParam(required = false) range: String?): ActiveChart {
		if (range == null || range !in RANGES) throw BadRequestException("INVALID_RANGE")
		return stats.active(range)
	}
}

/**
 * 수치는 DB 에 남은 행만 센다. 저장되지 않는 실패(요약 해석·방향성 제시·궁합 요약의 생성 실패)는 여기 없다(결정 014).
 * 상태는 행에 적힌 값 그대로다 — 서버가 꺼져 멈춘 `WRITING` 도 `WRITING` 으로 센다. 궁합(`synastryReports`)만은 멈춘 `WRITING` 을 `FAILED` 로 센다(결정 053 의 9).
 */
@Service
class AdminStatsService(private val repository: AdminStatsRepository, private val clock: Clock) {

	/** "오늘" 은 한국 날짜다. 시계를 한 번만 읽어 센 때와 오늘이 어긋나지 않게 한다. */
	fun get(): AdminStats {
		val now = clock.instant()
		val today = LocalDate.ofInstant(now, KOREA_ZONE)
		val todayStart = today.startOfDay()
		val tomorrowStart = today.plusDays(1).startOfDay()
		val reports = repository.countBy("reports", "status")
		// 궁합 글의 상태는 궁합 행에 있다(결정 053 의 9 — 새로 온 별은 글이 없어 세지 않고, 멈춘 쓰는 중은 실패로 센다)
		val synastryReports = repository.countSynastryStatuses().filterKeys { it in REPORT_STATUSES }
		val directions = repository.countBy("directions", "topic")
		val inquiries = repository.countBy("inquiries", "status")
		val accountTotal = repository.count("accounts")
		val detailReports = repository.countDetailReports()
		return AdminStats(
			generatedAt = now.truncatedTo(ChronoUnit.SECONDS).toString(),
			accounts = AccountStats(repository.count("accounts"), repository.countAccountsSince(7), repository.countAccountsSince(30)),
			profiles = Total(repository.count("profiles")),
			readings = Total(repository.count("readings")),
			reports = StatusStats(reports.values.sum(), REPORT_STATUSES.associateWith { reports[it] ?: 0 }),
			synastries = Total(repository.count("synastries")),
			synastryReports = StatusStats(synastryReports.values.sum(), REPORT_STATUSES.associateWith { synastryReports[it] ?: 0 }),
			directions = TopicStats(directions.values.sum(), directions),
			inquiries = StatusStats(inquiries.values.sum(), INQUIRY_STATUSES.associateWith { inquiries[it] ?: 0 }),
			users = UserStats(
				accountTotal,
				repository.countAccountsCreated(todayStart, tomorrowStart),
				repository.countAccountsCreated(today.minusDays(6).startOfDay(), tomorrowStart),
			),
			nicknames = Total(repository.countNicknames()),
			detailReports = DetailReportStats(detailReports["ME"] ?: 0, detailReports["TIMING"] ?: 0, detailReports["WRITING"] ?: 0),
			failuresToday = FailureStats(nickname = null, report = repository.countDetailReportsFailed(todayStart, tomorrowStart)),
			inquiryQueue = repository.inquiryQueue(),
			consents = repository.countConsents().let { (push, marketing, reachable) ->
				ConsentStats(rate(push, accountTotal), rate(marketing, accountTotal), reachable)
			},
		)
	}

	/**
	 * 접속 차트. 원본은 `daily_active`(그날 처음 접속한 계정마다 한 줄). 빈 날 · 빈 시는 0 이다.
	 * today: 0~23시(그 시에 그날 처음 접속한 사람) · 앞 기간은 어제. week: 오늘 포함 최근 7일 · 앞 기간은 그 앞 7일.
	 * month: 이번 달 1일부터 오늘까지 · 앞 기간은 지난달 1일부터 지난달의 같은 날까지(지난달이 짧으면 말일까지).
	 */
	fun active(range: String): ActiveChart {
		val today = LocalDate.ofInstant(clock.instant(), KOREA_ZONE)
		val from: LocalDate
		val previousFrom: LocalDate
		val previousTo: LocalDate
		val points: List<ActivePoint>
		when (range) {
			"today" -> {
				from = today
				previousFrom = today.minusDays(1)
				previousTo = previousFrom
				val byHour = repository.activeByHour(today)
				points = (0..23).map { ActivePoint("%02d".format(it), byHour[it] ?: 0) }
			}
			else -> {
				from = if (range == "week") today.minusDays(6) else today.withDayOfMonth(1)
				if (range == "week") {
					previousFrom = from.minusDays(7)
					previousTo = from.minusDays(1)
				} else {
					// minusMonths 는 지난달에 없는 날을 그달의 말일로 줄인다(3월 31일 → 2월 28일).
					previousTo = today.minusMonths(1)
					previousFrom = previousTo.withDayOfMonth(1)
				}
				val byDate = repository.activeByDate(from, today)
				points = from.datesUntil(today.plusDays(1)).map { ActivePoint(it.toString(), byDate[it] ?: 0) }.toList()
			}
		}
		val average = BigDecimal(points.sumOf { it.value }).divide(BigDecimal(points.size), 1, RoundingMode.HALF_UP).toDouble()
		return ActiveChart(range, points, repository.countActive(from, today), average, repository.countActive(previousFrom, previousTo))
	}

	private fun LocalDate.startOfDay(): Instant = atStartOfDay(KOREA_ZONE).toInstant()

	/** [part] / [whole] 를 소수 셋째 자리로(반올림). [whole] 이 0 이면 0.0. */
	private fun rate(part: Int, whole: Int): Double =
		if (whole == 0) 0.0 else BigDecimal(part).divide(BigDecimal(whole), 3, RoundingMode.HALF_UP).toDouble()
}

/**
 * 다른 기능의 테이블을 **읽기만** 한다(개수). `admin` 은 다른 기능 패키지의 코드를 부를 수 없어서 자기 SQL 로 센다(결정 014).
 * 테이블·열 이름은 이 파일의 서비스가 주는 고정 글자뿐이다(요청에서 오지 않는다).
 */
@Repository
class AdminStatsRepository(private val jdbc: JdbcTemplate) {

	fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

	fun countAccountsSince(days: Int): Int =
		jdbc.queryForObject("select count(*) from accounts where created_at > now() - make_interval(days => ?)", Int::class.java, days)!!

	/** [from] 부터 [until] 전까지 만든 계정 수. */
	fun countAccountsCreated(from: Instant, until: Instant): Int =
		jdbc.queryForObject("select count(*) from accounts where created_at >= ? and created_at < ?", Int::class.java, Timestamp.from(from), Timestamp.from(until))!!

	/** 요약 해석 v2 가 있는 계정 수(옛 `SUMMARY` 는 세지 않는다). */
	fun countNicknames(): Int =
		jdbc.queryForObject("select count(distinct owner_id) from readings where kind = 'SUMMARY_V2'", Int::class.java)!!

	/** `ME` · `TIMING`: 완성(`DONE`)된 수, `WRITING`: 쓰는 중인 수(종류를 가리지 않는다). */
	fun countDetailReports(): Map<String, Int> {
		val counts = HashMap<String, Int>()
		jdbc.query(
			"""
			select kind as k, count(*) as n from detail_reports where status = 'DONE' group by kind
			union all
			select 'WRITING', count(*) from detail_reports where status = 'WRITING'
			""",
		) { rs -> counts[rs.getString("k")] = rs.getInt("n") }
		return counts
	}

	/** [from] 부터 [until] 전까지 `FAILED` 로 바뀐 자세한 풀이 수(지금도 `FAILED` 인 것). */
	fun countDetailReportsFailed(from: Instant, until: Instant): Int = jdbc.queryForObject(
		"select count(*) from detail_reports where status = 'FAILED' and updated_at >= ? and updated_at < ?",
		Int::class.java, Timestamp.from(from), Timestamp.from(until),
	)!!

	/** 답이 없고(`answer_body is null`) 끝나지 않은(`DONE` 아님) 문의. */
	fun inquiryQueue(): InquiryQueue = jdbc.queryForObject(
		"select count(*) as n, min(created_at) as oldest from inquiries where answer_body is null and status <> 'DONE'",
	) { rs, _ -> InquiryQueue(rs.getInt("n"), rs.getObject("oldest", OffsetDateTime::class.java)?.utcSeconds()) }!!

	/** 푸시 동의 · 마케팅 동의 · 둘 다 동의했고 정지되지 않은 계정 수. */
	fun countConsents(): Triple<Int, Int, Int> = jdbc.queryForObject(
		"""
		select count(push_consented_at) as push, count(marketing_consented_at) as marketing,
			count(*) filter (where push_consented_at is not null and marketing_consented_at is not null and suspended_at is null
			                 and exists (select 1 from push_devices d where d.account_id = accounts.id)) as reachable
		from accounts
		""",
	) { rs, _ -> Triple(rs.getInt("push"), rs.getInt("marketing"), rs.getInt("reachable")) }!!

	/** 그날 처음 접속한 한국 시각의 시(0~23) → 사람 수. */
	fun activeByHour(date: LocalDate): Map<Int, Int> = jdbc.query(
		"select first_hour_kst as h, count(*) as n from daily_active where kst_date = ? group by first_hour_kst",
		{ rs, _ -> rs.getInt("h") to rs.getInt("n") }, date,
	).toMap()

	/** [from] ~ [to](두 끝 포함)의 날짜 → 그날 접속한 사람 수. */
	fun activeByDate(from: LocalDate, to: LocalDate): Map<LocalDate, Int> = jdbc.query(
		"select kst_date as d, count(*) as n from daily_active where kst_date between ? and ? group by kst_date",
		{ rs, _ -> rs.getObject("d", LocalDate::class.java) to rs.getInt("n") }, from, to,
	).toMap()

	/** [from] ~ [to](두 끝 포함)에 한 번이라도 접속한 고유 계정 수. */
	fun countActive(from: LocalDate, to: LocalDate): Int =
		jdbc.queryForObject("select count(distinct account_id) from daily_active where kst_date between ? and ?", Int::class.java, from, to)!!

	/** [column] 의 값별 개수. 많은 것부터(같으면 값의 글자 순서). */
	/**
	 * 궁합 행의 상태별 수. 살아 있다는 표시(`active_at`)가 [SYNASTRY_STALE_SECONDS] 넘게 끊긴 `WRITING` 은 앱이 보는 것과 같이 `FAILED` 로 센다
	 * (결정 053 의 9 — `synastry` 의 멈춤 기준과 같은 값. 기능 패키지는 서로의 코드를 쓰지 않으므로 여기 따로 둔다).
	 */
	fun countSynastryStatuses(): Map<String, Int> {
		val counts = LinkedHashMap<String, Int>()
		jdbc.query(
			"""
			select case when status = 'WRITING' and active_at < now() - make_interval(secs => ?) then 'FAILED' else status end as k, count(*) as n
			from synastries group by k order by n desc, k
			""".trimIndent(),
			{ rs -> counts[rs.getString("k")] = rs.getInt("n") },
			SYNASTRY_STALE_SECONDS,
		)
		return counts
	}

	fun countBy(table: String, column: String): Map<String, Int> {
		val counts = LinkedHashMap<String, Int>()
		jdbc.query("select $column as k, count(*) as n from $table group by $column order by n desc, k") { rs ->
			counts[rs.getString("k")] = rs.getInt("n")
		}
		return counts
	}
}
