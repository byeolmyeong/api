package com.byeolmyeong.api.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val REPORT_STATUSES = listOf("DONE", "WRITING", "FAILED")
private val INQUIRY_STATUSES = listOf("NEW", "IN_PROGRESS", "ANSWERED", "DONE")

data class Total(val total: Int)
data class AccountStats(val total: Int, val last7Days: Int, val last30Days: Int)
data class StatusStats(val total: Int, val byStatus: Map<String, Int>)
data class TopicStats(val total: Int, val byTopic: Map<String, Int>)

/** 현황 수치. 개수뿐이다 — 사람별 행, 출생 정보, 글은 없다. */
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
)

@RestController
class AdminStatsController(private val stats: AdminStatsService) {

	@GetMapping("/admin/stats")
	fun get(): AdminStats = stats.get()
}

/**
 * 수치는 DB 에 남은 행만 센다. 저장되지 않는 실패(요약 해석·방향성 제시·궁합 요약의 생성 실패)는 여기 없다(결정 014).
 * 상태는 행에 적힌 값 그대로다 — 서버가 꺼져 멈춘 `WRITING` 도 `WRITING` 으로 센다.
 */
@Service
class AdminStatsService(private val repository: AdminStatsRepository) {

	fun get(): AdminStats {
		val reports = repository.countBy("reports", "status")
		val synastryReports = repository.countBy("synastry_reports", "status")
		val directions = repository.countBy("directions", "topic")
		val inquiries = repository.countBy("inquiries", "status")
		return AdminStats(
			generatedAt = OffsetDateTime.now(ZoneOffset.UTC).utcSeconds(),
			accounts = AccountStats(repository.count("accounts"), repository.countAccountsSince(7), repository.countAccountsSince(30)),
			profiles = Total(repository.count("profiles")),
			readings = Total(repository.count("readings")),
			reports = StatusStats(reports.values.sum(), REPORT_STATUSES.associateWith { reports[it] ?: 0 }),
			synastries = Total(repository.count("synastries")),
			synastryReports = StatusStats(synastryReports.values.sum(), REPORT_STATUSES.associateWith { synastryReports[it] ?: 0 }),
			directions = TopicStats(directions.values.sum(), directions),
			inquiries = StatusStats(inquiries.values.sum(), INQUIRY_STATUSES.associateWith { inquiries[it] ?: 0 }),
		)
	}
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

	/** [column] 의 값별 개수. 많은 것부터(같으면 값의 글자 순서). */
	fun countBy(table: String, column: String): Map<String, Int> {
		val counts = LinkedHashMap<String, Int>()
		jdbc.query("select $column as k, count(*) as n from $table group by $column order by n desc, k") { rs ->
			counts[rs.getString("k")] = rs.getInt("n")
		}
		return counts
	}
}
