package com.byeolmyeong.api.admin

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.BadRequestException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

private val SEOUL = ZoneId.of("Asia/Seoul")
private const val TITLE_MAX = 40
private const val BODY_MAX = 120
private val TARGETS = setOf("HOME", "TODAY_SKY", "ME", "TIMING_MONTH", "TIMING_YEAR", "MY")
// 광고 표기 · 수신 거부 안내(정보통신망법, 앱 결정 066 의 4). 화면이 아니라 서버가 붙인다.
private const val AD_PREFIX = "(광고) "
private const val OPT_OUT = "\n수신 거부: 마이 › 알림"
// 야간(한국 시각 21:00 ~ 다음 날 08:00)에는 보내지 않는다.
private val SEND_FROM = LocalTime.of(8, 0)
private val SEND_UNTIL = LocalTime.of(21, 0)
private const val EVENTS_SIZE_DEFAULT = 20
private const val EVENTS_SIZE_MAX = 100

data class PushAudience(val count: Int)

data class SentPush(val id: UUID, val recipients: Int)

data class PushEventRow(val id: UUID, val sentAt: String, val title: String, val recipients: Int, val succeeded: Int?, val failed: Int?, val sentBy: String?)

data class PushEventPage(val items: List<PushEventRow>, val page: Int, val size: Int, val total: Int)

data class PushStatus(val canSend: Boolean, val reason: String?, val sentTodayAt: String?, val sentTodayBy: String?, val nextAvailableAt: String?)

/** 광고 푸시 발송(결정 043 의 6): 대상 수 · 보내기 · 보낼 수 있는지 · 발송 내역. 실제로 보내는 일은 `push`(S⑩)가 이벤트를 듣고 한다. */
@RestController
class AdminPushController(private val push: AdminPushService, private val current: CurrentAdmin) {

	@GetMapping("/admin/push/audience")
	fun audience(): PushAudience = PushAudience(push.audience())

	@GetMapping("/admin/push/status")
	fun status(): PushStatus = push.status()

	@GetMapping("/admin/push/events")
	fun events(@RequestParam(required = false) page: String?, @RequestParam(required = false) size: String?): PushEventPage {
		val pageNumber = if (page.isNullOrEmpty()) 0 else page.toIntOrNull()
		val pageSize = if (size.isNullOrEmpty()) EVENTS_SIZE_DEFAULT else size.toIntOrNull()
		if (pageNumber == null || pageNumber < 0 || pageSize == null || pageSize !in 1..EVENTS_SIZE_MAX) throw BadRequestException("INVALID_PAGE")
		return push.events(pageNumber, pageSize)
	}

	/** 202 로 답하고 뒤에서 보낸다(`push` 가 이벤트를 듣는다). */
	@PostMapping("/admin/push/events")
	fun send(@RequestBody body: JsonNode): ResponseEntity<SentPush> {
		val fields = body.requireObject()
		val title = fields.textOrNull("title")?.trim()?.takeIf { it.isNotEmpty() && it.codePointLength() <= TITLE_MAX && NUL !in it }
			?: throw BadRequestException("INVALID_TITLE")
		val text = fields.textOrNull("body")?.trim()?.takeIf { it.isNotEmpty() && it.codePointLength() <= BODY_MAX && NUL !in it }
			?: throw BadRequestException("INVALID_BODY")
		val target = fields.textOrNull("target")?.takeIf { it in TARGETS } ?: throw BadRequestException("INVALID_TARGET")
		return ResponseEntity.accepted().body(push.send(current.get().id, title, text, target))
	}
}

@Service
class AdminPushService(
	private val repository: AdminPushRepository,
	private val events: ApplicationEventPublisher,
	private val audit: AdminAudit,
	/** 야간 · 하루 한 번을 판정하는 시계. 테스트가 바꿔 끼운다. */
	private val clock: Clock = Clock.system(SEOUL),
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun audience(): Int = repository.audience()

	fun events(page: Int, size: Int): PushEventPage = PushEventPage(repository.page(page.toLong() * size, size), page, size, repository.count())

	/** 그날 이미 보냈으면 `DAILY_LIMIT`(다음 날 8시부터), 야간이면 `QUIET_HOURS`(그다음 8시부터). */
	fun status(): PushStatus {
		val now = ZonedDateTime.now(clock).withZoneSameInstant(SEOUL)
		repository.sentOn(now.toLocalDate())?.let { (at, by) ->
			return PushStatus(false, "DAILY_LIMIT", at.utcSeconds(), by, nextMorning(now.toLocalDate().plusDays(1)))
		}
		if (!quiet(now)) return PushStatus(true, null, null, null, null)
		val morning = if (now.toLocalTime() < SEND_FROM) now.toLocalDate() else now.toLocalDate().plusDays(1)
		return PushStatus(false, "QUIET_HOURS", null, null, nextMorning(morning))
	}

	/**
	 * 야간이면 409 `QUIET_HOURS`, 그날(한국 날짜) 이미 누가 보냈으면 409 `DAILY_LIMIT`. 내역 한 줄을 넣고 발송을 요청한다.
	 * 하루 한 번은 `push_events.kst_date` 의 유일 제약이 지킨다(두 관리자가 동시에 보내도 한 줄).
	 */
	@Transactional
	fun send(adminId: UUID, title: String, body: String, target: String): SentPush {
		val now = ZonedDateTime.now(clock).withZoneSameInstant(SEOUL)
		if (quiet(now)) throw AdminRejectedException(409, "QUIET_HOURS")
		val id = UUID.randomUUID()
		val recipients = repository.audience()
		try {
			repository.insert(id, now.toLocalDate(), now.toOffsetDateTime(), title, body, target, recipients, adminId)
		} catch (_: DuplicateKeyException) {
			throw AdminRejectedException(409, "DAILY_LIMIT")
		}
		events.publishEvent(PushEventRequested(id, AD_PREFIX + title, body + OPT_OUT, target))
		log.info("event=admin_push_event_sent recipients={}", recipients)
		audit.record(AuditAction.PUSH_EVENT_SENT, "PUSH_EVENT", id, mapOf("target" to target, "recipients" to recipients))
		return SentPush(id, recipients)
	}

	/** `push` 가 다 보냈다 — 내역의 대상 · 성공 · 실패 수를 채운다. */
	@EventListener
	fun onSent(event: PushEventSent) {
		repository.complete(event.eventId, event.recipients, event.succeeded, event.failed)
	}

	private fun quiet(now: ZonedDateTime): Boolean = now.toLocalTime() < SEND_FROM || now.toLocalTime() >= SEND_UNTIL

	private fun nextMorning(date: LocalDate): String = date.atTime(SEND_FROM).atZone(SEOUL).toOffsetDateTime().utcSeconds()
}

/** 발송 내역(`push_events`)과 대상 수. 대상은 다른 기능의 표를 자기 SQL 로 센다(결정 014). */
@Repository
class AdminPushRepository(private val jdbc: JdbcTemplate) {

	/**
	 * 푸시 · 마케팅 둘 다 동의 + 기기 등록 + 정지 아님. 기기 표(`push_devices`, 푸시 묶음 S⑩)나 정지 칸(`suspended_at`, S⑪)이
	 * 아직 없는 서버에서는 0 이다 — 두 묶음이 모두 들어오면 이 확인은 지운다.
	 */
	fun audience(): Int {
		val ready = jdbc.queryForObject(
			"""
			select to_regclass('push_devices') is not null
			   and exists (select 1 from information_schema.columns where table_name = 'accounts' and column_name = 'suspended_at')
			""",
			Boolean::class.java,
		) == true
		if (!ready) return 0
		return jdbc.queryForObject(
			"""
			select count(*) from accounts a
			where a.push_consented_at is not null and a.marketing_consented_at is not null and a.suspended_at is null
			  and exists (select 1 from push_devices d where d.account_id = a.id)
			""",
			Int::class.java,
		)!!
	}

	fun insert(id: UUID, date: LocalDate, at: OffsetDateTime, title: String, body: String, target: String, recipients: Int, adminId: UUID) {
		jdbc.update(
			"insert into push_events (id, kst_date, sent_at, title, body, target, recipients, sent_by) values (?, ?, ?, ?, ?, ?, ?, ?)",
			id, date, at, title, body, target, recipients, adminId,
		)
	}

	/** 그 한국 날짜에 보낸 때와 보낸 관리자 이름. 없으면 null. */
	fun sentOn(date: LocalDate): Pair<OffsetDateTime, String?>? =
		jdbc.query(
			"select e.sent_at, a.name from push_events e left join admin_accounts a on a.id = e.sent_by where e.kst_date = ?",
			{ rs, _ -> rs.getObject("sent_at", OffsetDateTime::class.java) to rs.getString("name") },
			date,
		).firstOrNull()

	fun page(offset: Long, limit: Int): List<PushEventRow> =
		jdbc.query(
			"""
			select e.id, e.sent_at, e.title, e.recipients, e.succeeded, e.failed, a.name
			from push_events e left join admin_accounts a on a.id = e.sent_by
			order by e.sent_at desc, e.id desc offset ? limit ?
			""",
			{ rs, _ ->
				PushEventRow(
					id = rs.getObject("id", UUID::class.java),
					sentAt = rs.getObject("sent_at", OffsetDateTime::class.java).utcSeconds(),
					title = rs.getString("title"),
					recipients = rs.getInt("recipients"),
					succeeded = rs.getObject("succeeded") as Int?,
					failed = rs.getObject("failed") as Int?,
					sentBy = rs.getString("name"),
				)
			},
			offset, limit,
		)

	fun count(): Int = jdbc.queryForObject("select count(*) from push_events", Int::class.java)!!

	fun complete(id: UUID, recipients: Int, succeeded: Int, failed: Int) {
		jdbc.update("update push_events set recipients = ?, succeeded = ?, failed = ? where id = ?", recipients, succeeded, failed, id)
	}
}
