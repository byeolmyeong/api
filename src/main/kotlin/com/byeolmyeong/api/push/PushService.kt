package com.byeolmyeong.api.push

import com.byeolmyeong.api.admin.InquiryAnswered
import com.byeolmyeong.api.admin.PushEventRequested
import com.byeolmyeong.api.reading.ReadingCreated
import com.byeolmyeong.api.report.MeReportDone
import com.byeolmyeong.api.report.TimingReportDone
import com.byeolmyeong.api.sky.TodaySkyDue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskRejectedException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.context.event.EventListener
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/** 답변 첫 줄을 푸시 본문에 넣을 때 자르는 길이(코드 포인트, 결정 042 의 4). */
private const val ANSWER_PREVIEW = 60

/** 결정 042 의 4 의 종류(앱이 `data.type` 으로 받아 갈 곳을 고른다). */
enum class PushKind { TODAY_SKY, READING_DONE, ME_REPORT_DONE, TIMING_REPORT_DONE, INQUIRY_ANSWERED, EVENT }

/** 광고 푸시 한 번의 결과(사람 수로 센다): 대상 · 기기 하나라도 받은 사람 · 하나도 못 받은 사람. */
data class BroadcastResult(val recipients: Int, val succeeded: Int, val failed: Int)

/** 한 사람에게 보낼 알림 하나([sendMany] 용). */
data class Notice(val accountId: UUID, val title: String, val body: String, val data: Map<String, String> = emptyMap())

/** 한 번 보낸 결과(sent · skipped · failed 를 나눈다). */
data class PushResult(val devices: Int, val sent: Int, val failed: Int, val skipped: Int)

/** 푸시 문구(결정 042 의 4 — 앱 결정 066 의 표와 같은 말). 앱 이름 · 아이콘은 휴대폰이 붙인다. */
object PushMessages {
	const val READING_DONE_TITLE = "별명을 지었어요"
	const val ME_REPORT_DONE_TITLE = "자세한 풀이가 다 됐어요"
	const val ME_REPORT_DONE_BODY = "성격 · 연애 · 관계 · 일 · 감정을 읽어 보세요."
	const val TIMING_REPORT_DONE_TITLE = "시기 풀이가 다 됐어요"
	const val TIMING_REPORT_DONE_BODY = "지금 나의 시기와 올해의 흐름을 볼 수 있어요."
	const val INQUIRY_ANSWERED_TITLE = "문의에 답변이 왔어요"
	const val TODAY_SKY_TITLE = "오늘의 하늘"

	/** 닉네임이 없는 옛 계정은 닉네임 없이. */
	fun readingDoneBody(nickname: String?): String =
		if (nickname.isNullOrBlank()) "별명이 기다리고 있어요" else "$nickname 님의 별명이 기다리고 있어요"

	/** 답변의 첫 줄(앞뒤 공백 뗌)을 [ANSWER_PREVIEW] 자까지, 넘으면 말줄임표. */
	fun answerPreview(answer: String): String {
		val line = answer.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() } ?: ""
		val points = line.codePoints().toArray()
		return if (points.size <= ANSWER_PREVIEW) line else String(points, 0, ANSWER_PREVIEW) + "…"
	}
}

/**
 * 한 계정의 기기에 보낸다(결정 042). 토큰은 로그에 남기지 않는다(개수와 종류만).
 * `DeviceNotRegistered` 인 토큰은 지운다. 다시 보내지 않는다(두 번 가는 것보다 한 번 빠지는 쪽).
 */
@Service
class PushService(private val repository: PushRepository, private val expo: ExpoPush) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 풀이 완성 · 문의 답변(결정 042 의 4 — "늘"). 문구에 필요한 값은 여기서 읽는다. 보낼 것이 없으면(답이 지워짐 등) 아무것도 하지 않는다. */
	fun notify(accountId: UUID, kind: PushKind, inquiryId: UUID? = null): PushResult {
		val (title, body, data) = when (kind) {
			PushKind.READING_DONE -> Triple(PushMessages.READING_DONE_TITLE, PushMessages.readingDoneBody(repository.nickname(accountId)), emptyMap())
			PushKind.ME_REPORT_DONE -> Triple(PushMessages.ME_REPORT_DONE_TITLE, PushMessages.ME_REPORT_DONE_BODY, emptyMap())
			PushKind.TIMING_REPORT_DONE -> Triple(PushMessages.TIMING_REPORT_DONE_TITLE, PushMessages.TIMING_REPORT_DONE_BODY, emptyMap())
			PushKind.INQUIRY_ANSWERED -> {
				val answer = inquiryId?.let { repository.answer(accountId, it) } ?: return PushResult(0, 0, 0, 0)
				Triple(PushMessages.INQUIRY_ANSWERED_TITLE, PushMessages.answerPreview(answer), mapOf("inquiryId" to inquiryId.toString()))
			}
			PushKind.TODAY_SKY, PushKind.EVENT -> error("$kind 는 이 길로 보내지 않는다")
		}
		return send(accountId, kind, title, body, data)
	}

	/**
	 * 광고 푸시(결정 043 의 6 · 042 의 7): 푸시 · 마케팅 둘 다 동의 + 기기 + 정지 아님인 사람의 기기 모두에 같은 글을 보낸다.
	 * 글의 "(광고) " · 수신 거부 줄은 admin 이 붙여 둔다. [target] 은 앱이 누른 뒤 갈 화면 키. 야간 · 하루 한 번은 admin 이 막는다.
	 */
	fun broadcast(title: String, body: String, target: String): BroadcastResult {
		val devices = repository.adAudienceDevices()
		if (devices.isEmpty()) return BroadcastResult(0, 0, 0)
		val outcomes = expo.send(devices.map { PushMessage(it.second, title, body, mapOf("type" to PushKind.EVENT.name, "target" to target)) })
		val gone = devices.filterIndexed { i, _ -> outcomes.getOrNull(i) == PushOutcome.DEVICE_NOT_REGISTERED }.map { it.second }
		if (gone.isNotEmpty()) repository.deleteTokens(gone)
		val byAccount = devices.indices.groupBy({ devices[it].first }, { outcomes.getOrNull(it) })
		val succeeded = byAccount.count { (_, results) -> results.any { it == PushOutcome.OK } }
		// 보내지 않은 것(꺼짐 · 접근 토큰 없음 — SKIPPED)은 실패로 세지 않는다(sent · skipped · failed 를 나눈다).
		val failed = byAccount.count { (_, results) -> results.none { it == PushOutcome.OK } && results.any { it != PushOutcome.SKIPPED } }
		val result = BroadcastResult(byAccount.size, succeeded, failed)
		log.info("event=push_broadcast recipients={} succeeded={} failed={} removed={}", result.recipients, result.succeeded, result.failed, gone.size)
		return result
	}

	/** 광고 대상의 사람 수(발송이 실패했을 때 결과를 알리려고). */
	fun adAudienceCount(): Int = repository.adAudienceDevices().map { it.first }.distinct().size

	/**
	 * 여러 사람에게 각자의 알림을 한꺼번에 보낸다(Expo 를 100건씩 묶어 부른다 — 오늘의 하늘 아침 알림).
	 * 정지 계정 · 기기 없는 사람은 건너뛴다. `DeviceNotRegistered` 인 토큰은 지운다.
	 */
	fun sendMany(kind: PushKind, notices: List<Notice>): PushResult {
		val messages = notices.flatMap { notice ->
			repository.tokens(notice.accountId).map { PushMessage(it, notice.title, notice.body, notice.data + ("type" to kind.name)) }
		}
		if (messages.isEmpty()) return PushResult(0, 0, 0, 0)
		val outcomes = expo.send(messages)
		val gone = messages.filterIndexed { i, _ -> outcomes.getOrNull(i) == PushOutcome.DEVICE_NOT_REGISTERED }.map { it.to }
		if (gone.isNotEmpty()) repository.deleteTokens(gone)
		val result = PushResult(
			devices = messages.size,
			sent = outcomes.count { it == PushOutcome.OK },
			failed = outcomes.count { it == PushOutcome.FAILED || it == PushOutcome.DEVICE_NOT_REGISTERED },
			skipped = outcomes.count { it == PushOutcome.SKIPPED },
		)
		log.info("event=push_sent type={} devices={} sent={} failed={} skipped={} removed={}", kind, result.devices, result.sent, result.failed, result.skipped, gone.size)
		return result
	}

	/** 그 계정의 기기 모두에 같은 알림을 보낸다. */
	fun send(accountId: UUID, kind: PushKind, title: String, body: String, data: Map<String, String> = emptyMap()): PushResult {
		val tokens = repository.tokens(accountId)
		if (tokens.isEmpty()) return PushResult(0, 0, 0, 0)
		val outcomes = expo.send(tokens.map { PushMessage(it, title, body, data + ("type" to kind.name)) })
		val gone = tokens.filterIndexed { i, _ -> outcomes.getOrNull(i) == PushOutcome.DEVICE_NOT_REGISTERED }
		if (gone.isNotEmpty()) repository.deleteTokens(gone)
		val result = PushResult(
			devices = tokens.size,
			sent = outcomes.count { it == PushOutcome.OK },
			failed = outcomes.count { it == PushOutcome.FAILED || it == PushOutcome.DEVICE_NOT_REGISTERED },
			skipped = outcomes.count { it == PushOutcome.SKIPPED },
		)
		log.info("event=push_sent type={} devices={} sent={} failed={} skipped={} removed={}", kind, result.devices, result.sent, result.failed, result.skipped, gone.size)
		return result
	}
}

/**
 * 푸시가 읽는 것. 다른 기능의 테이블(`accounts` 의 닉네임 · `inquiries` 의 답)은 문구를 채울 만큼만 읽는다(관리자 현황과 같은 방식 — 결정 014).
 */
@Repository
class PushRepository(private val jdbc: JdbcTemplate) {

	/** 그 계정의 기기 토큰. 오래 등록한 것부터. 정지된 계정이면 비어 있다(결정 044 의 3 — 어떤 푸시도 보내지 않는다). */
	fun tokens(accountId: UUID): List<String> = jdbc.queryForList(
		"""
		select d.expo_token from push_devices d join accounts a on a.id = d.account_id
		where d.account_id = ? and a.suspended_at is null
		order by d.created_at, d.expo_token
		""".trimIndent(),
		String::class.java, accountId,
	).filterNotNull()

	/** 광고 대상의 (계정, 기기 토큰): 푸시 · 마케팅 둘 다 동의 + 정지 아님(결정 027 의 4 · 044 의 3). */
	fun adAudienceDevices(): List<Pair<UUID, String>> = jdbc.query(
		"""
		select d.account_id, d.expo_token from push_devices d join accounts a on a.id = d.account_id
		where a.push_consented_at is not null and a.marketing_consented_at is not null and a.suspended_at is null
		order by d.account_id, d.created_at, d.expo_token
		""".trimIndent(),
	) { rs, _ -> rs.getObject("account_id", UUID::class.java) to rs.getString("expo_token") }

	fun deleteTokens(tokens: List<String>) {
		jdbc.batchUpdate("delete from push_devices where expo_token = ?", tokens.map { arrayOf<Any>(it) })
	}

	fun nickname(accountId: UUID): String? =
		jdbc.queryForList("select nickname from accounts where id = ?", String::class.java, accountId).firstOrNull()

	/** 그 계정의 그 문의의 답. 없으면 null. */
	fun answer(accountId: UUID, inquiryId: UUID): String? =
		jdbc.queryForList("select answer_body from inquiries where id = ? and owner_id = ?", String::class.java, inquiryId, accountId).firstOrNull()
}

/** 푸시를 보내는 일꾼. 이벤트를 낸 쪽(생성 일꾼 · 관리자 요청)이 Expo 호출을 기다리지 않게 한다. */
@Configuration
class PushExecutorConfig {

	@Bean
	fun pushExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 2
		maxPoolSize = 2
		queueCapacity = 500
		// 서버를 닫는 동안(결정 046 — 요청 마무리 · 생성 일꾼 기다리기) 끝난 풀이 · 답변의 푸시도 받아 보내고, 닫을 때 줄을 비울 때까지 기다린다.
		setAcceptTasksAfterContextClose(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(60)
		setThreadNamePrefix("push-")
	}
}

/**
 * 별명 지음 · 풀이 완성 · 문의 답변 이벤트를 듣고(커밋 뒤) 푸시를 보낸다(결정 042 의 5). 알림함(결정 035)과 같은 이벤트다.
 * 궁합 리포트 완성은 1.0.0 에 보내지 않는다(앱 결정 055).
 * 실패는 삼키고 로그만 남긴다 — 이벤트를 낸 일은 그대로 끝난다.
 */
@Component
class PushEvents(
	private val push: PushService,
	private val events: ApplicationEventPublisher,
	@Qualifier("pushExecutor") private val executor: ThreadPoolTaskExecutor,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun readingCreated(event: ReadingCreated) {
		later(PushKind.READING_DONE) { push.notify(event.ownerId, PushKind.READING_DONE) }
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun meReportDone(event: MeReportDone) {
		later(PushKind.ME_REPORT_DONE) { push.notify(event.ownerId, PushKind.ME_REPORT_DONE) }
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun timingReportDone(event: TimingReportDone) {
		later(PushKind.TIMING_REPORT_DONE) { push.notify(event.ownerId, PushKind.TIMING_REPORT_DONE) }
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun inquiryAnswered(event: InquiryAnswered) {
		later(PushKind.INQUIRY_ANSWERED) { push.notify(event.ownerId, PushKind.INQUIRY_ANSWERED, event.inquiryId) }
	}

	/** 광고 푸시(결정 043 의 6). admin 이 발송 내역 행을 만든 뒤(커밋 뒤) 낸다 — 일꾼이 보내고 결과를 `PushEventSent` 로 알린다. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun pushEventRequested(event: PushEventRequested) {
		// 어떤 길로 끝나도 결과를 알린다 — 오지 않으면 발송 내역이 "보내는 중" 으로 남고 그날은 다시 보낼 수도 없다(하루 한 번).
		fun failedAll() = runCatching { push.adAudienceCount() }.getOrDefault(0).let { BroadcastResult(it, 0, it) }
		val started = later(PushKind.EVENT) {
			val result = try {
				push.broadcast(event.title, event.body, event.target)
			} catch (e: RuntimeException) {
				log.warn("event=push_failed type={} reason={}", PushKind.EVENT, e.javaClass.simpleName)
				failedAll()
			}
			events.publishEvent(PushEventSent(event.eventId, result.recipients, result.succeeded, result.failed))
		}
		if (!started) failedAll().let { events.publishEvent(PushEventSent(event.eventId, it.recipients, it.succeeded, it.failed)) }
	}

	/** 오늘의 하늘 아침 알림(결정 042 의 6). `sky` 의 아침 일정이 그날 한 번만 낸다 — 그 일꾼 스레드에서 바로 보낸다. */
	@EventListener
	fun todaySkyDue(event: TodaySkyDue) {
		try {
			push.sendMany(PushKind.TODAY_SKY, event.items.map { Notice(it.ownerId, PushMessages.TODAY_SKY_TITLE, it.title, mapOf("date" to it.date.toString())) })
		} catch (e: RuntimeException) {
			log.warn("event=push_failed type={} reason={}", PushKind.TODAY_SKY, e.javaClass.simpleName)
		}
	}

	/** 일꾼에 맡겼으면 true. 거절되면(줄이 참 · 닫힘) false 와 로그. */
	private fun later(kind: PushKind, work: () -> Unit): Boolean = try {
		executor.execute {
			try {
				work()
			} catch (e: RuntimeException) {
				log.warn("event=push_failed type={} reason={}", kind, e.javaClass.simpleName)
			}
		}
		true
	} catch (_: TaskRejectedException) {
		log.warn("event=push_failed type={} reason=REJECTED", kind)
		false
	}
}
