package com.byeolmyeong.api.sky

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskRejectedException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private val KST = ZoneId.of("Asia/Seoul")

/** 받는 시간보다 이만큼 앞부터 미리 만든다(결정 042 의 6). */
private const val PREBUILD_MINUTES = 30L

/** 받는 시간 칸을 놓쳤을 때 따라잡아 보내는 한계. 이보다 늦으면 아침 알림이 한낮에 가지 않게 보내지 않는다. */
private const val CATCH_UP_MINUTES = 60L

/** 미리 만들기를 생성 일꾼에 맡길 때 사용자 요청 몫으로 남겨 두는 대기 줄 칸 수. */
private const val QUEUE_RESERVE = 20

/** 오늘의 하늘 아침 일이 일정 스레드를 막지 않게 따로 도는 일꾼 하나. 닫을 때 하던 한 번은 끝까지 한다. */
@Configuration
class TodaySkyExecutorConfig {

	@Bean
	fun todaySkyExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(60)
		setThreadNamePrefix("today-sky-")
	}
}

/** 한국 날짜 하루 안의 받는 시간 구간(양 끝 포함). */
private data class Slot(val date: LocalDate, val from: LocalTime, val to: LocalTime)

/** (from, to] 를 날짜마다 나눈다(자정을 넘으면 둘). 받는 시간은 분 단위라 from 다음 분부터 본다. */
private fun slots(from: LocalDateTime, to: LocalDateTime): List<Slot> {
	val start = from.plusMinutes(1)
	if (start.toLocalDate() == to.toLocalDate()) return listOf(Slot(to.toLocalDate(), start.toLocalTime(), to.toLocalTime()))
	return listOf(Slot(start.toLocalDate(), start.toLocalTime(), LocalTime.MAX), Slot(to.toLocalDate(), LocalTime.MIN, to.toLocalTime()))
}

/**
 * 오늘의 하늘 아침 알림(결정 042 의 6). 5분마다(한국 시각)
 * ① 받는 시간이 지난 60분 안이고 그날 아직 보내지 않은 사람에게, 그날 것이 있으면 보내라고 [TodaySkyDue] 를 낸다(`push` 가 묶어 보낸다).
 * ② 받는 시간이 앞으로 30분 안이고 그날 아직 맡지 않은 사람의 그날 것을 생성 일꾼(`reportExecutor` — 닫을 때 끝까지 기다린다)에 맡겨 미리 만든다.
 * 칸을 놓쳐도(늦게 돈 · 재시작) 다음 번이 따라잡는다. 같은 날 한 번: (계정, 한국 날짜) 줄을 먼저 넣은 서버만 맡고, 보낸 때를 먼저 적은 서버만 보낸다 —
 * 서버 둘이 함께 떠도(결정 046) 두 번 가지 않는다(일정 잠금 대신 줄 단위 선점). 한 사람의 오류는 그 사람만 건너뛴다.
 * 켬은 직접 정한 값, 없으면 푸시 수신 동의. 정지 · 기기 없음 · 출생 정보 없음은 대상이 아니다. 스위치 `byeolmyeong.push.today-sky-enabled`.
 */
@Service
class TodaySkyMorningService(
	private val repository: TodaySkyMorningRepository,
	private val daySkies: DaySkyService,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	@Qualifier("todaySkyExecutor") private val executor: ThreadPoolTaskExecutor,
	@Qualifier("reportExecutor") private val generation: ThreadPoolTaskExecutor,
	@Value("\${byeolmyeong.push.today-sky-enabled}") private val enabled: Boolean,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 일정 스레드는 넘기기만 한다. 앞 번이 아직 돌고 있으면 이번은 건너뛴다(다음 번이 따라잡는다). */
	@Scheduled(cron = "\${byeolmyeong.push.today-sky-cron}", zone = "Asia/Seoul")
	fun tick() {
		try {
			executor.execute(::run)
		} catch (_: TaskRejectedException) {
			log.warn("event=today_sky_tick_skipped reason=BUSY")
		}
	}

	/** 한 번 돈다(테스트가 직접 부른다). 보내기를 먼저 한다 — 미리 만들기가 길어도 받는 시간을 놓치지 않게. */
	fun run() {
		if (!enabled) return
		val now = LocalDateTime.ofInstant(clock.instant(), KST).withSecond(0).withNano(0)
		try {
			send(now)
		} catch (e: RuntimeException) {
			log.warn("event=today_sky_send_failed reason={}", e.javaClass.simpleName)
		}
		try {
			prebuild(now)
		} catch (e: RuntimeException) {
			log.warn("event=today_sky_prebuild_failed reason={}", e.javaClass.simpleName)
		}
	}

	private fun send(now: LocalDateTime) {
		val due = mutableListOf<TodaySkyDue.Item>()
		for (slot in slots(now.minusMinutes(CATCH_UP_MINUTES), now)) {
			for (id in repository.dueToSend(slot.date, slot.from, slot.to)) {
				try {
					val sky = daySkies.stored(id, slot.date) ?: continue
					if (repository.claimSend(id, slot.date)) due += TodaySkyDue.Item(id, slot.date, sky.title)
				} catch (e: RuntimeException) {
					log.warn("event=today_sky_send_failed reason={}", e.javaClass.simpleName)
				}
			}
		}
		if (due.isEmpty()) return
		events.publishEvent(TodaySkyDue(due))
		log.info("event=today_sky_due count={}", due.size)
	}

	private fun prebuild(now: LocalDateTime) {
		var queued = 0
		for (slot in slots(now, now.plusMinutes(PREBUILD_MINUTES))) {
			for (id in repository.dueToPrebuild(slot.date, slot.from, slot.to)) {
				// 사용자 요청(풀이 · 그날의 하늘)이 들어갈 자리를 남긴다. 못 맡긴 사람은 다음 번이 따라잡는다.
				if (generation.threadPoolExecutor.queue.remainingCapacity() <= QUEUE_RESERVE) {
					if (queued > 0) log.info("event=today_sky_prebuild_queued count={}", queued)
					return
				}
				try {
					if (!repository.claimPrebuild(id, slot.date)) continue
					val date = slot.date
					generation.execute {
						try {
							daySkies.daySky(id, date, countQuota = false)
						} catch (e: RuntimeException) {
							// 그날은 알림 없이 둔다(열 때 만든다 — 앱 결정 051). 출생 정보 · 글은 남기지 않는다.
							log.warn("event=today_sky_prebuild_failed reason={}", e.javaClass.simpleName)
						}
					}
					queued++
				} catch (e: RuntimeException) {
					log.warn("event=today_sky_prebuild_failed reason={}", e.javaClass.simpleName)
				}
			}
		}
		if (queued > 0) log.info("event=today_sky_prebuild_queued count={}", queued)
	}
}

/**
 * 아침 알림이 읽는 것. 계정 · 기기 · 출생 정보 표는 대상을 고를 만큼만 읽는다(관리자 현황과 같은 방식 — 결정 014).
 * "켜졌는가" 는 `coalesce(today_sky_push, push_consented_at is not null)` — `auth` 의 계정 응답 · 관리자 사용자 상세와 같은 식이다.
 */
@Repository
class TodaySkyMorningRepository(private val jdbc: JdbcTemplate) {

	private val target = """
		select a.id from accounts a
		where coalesce(a.today_sky_push, a.push_consented_at is not null)
		  and a.suspended_at is null
		  and a.today_sky_time between ? and ?
		  and exists (select 1 from push_devices d where d.account_id = a.id)
		  and exists (select 1 from profiles p where p.account_id = a.id)
	""".trimIndent()

	/** [date] 에 받는 시간이 [from]~[to] 이고 그날 아직 보내지 않은 대상. */
	fun dueToSend(date: LocalDate, from: LocalTime, to: LocalTime): List<UUID> = jdbc.queryForList(
		"$target and not exists (select 1 from today_sky_pushes t where t.account_id = a.id and t.kst_date = ? and t.sent_at is not null) order by a.today_sky_time, a.id",
		UUID::class.java, from, to, date,
	).filterNotNull()

	/** [date] 에 받는 시간이 [from]~[to] 이고 그날 아직 아무도 맡지 않은 대상. */
	fun dueToPrebuild(date: LocalDate, from: LocalTime, to: LocalTime): List<UUID> = jdbc.queryForList(
		"$target and not exists (select 1 from today_sky_pushes t where t.account_id = a.id and t.kst_date = ?) order by a.today_sky_time, a.id",
		UUID::class.java, from, to, date,
	).filterNotNull()

	/** 그날의 줄을 이 서버가 처음 넣었으면 true(미리 만들기를 맡는다). */
	fun claimPrebuild(accountId: UUID, date: LocalDate): Boolean =
		jdbc.update("insert into today_sky_pushes (account_id, kst_date) values (?, ?) on conflict do nothing", accountId, date) == 1

	/** 그날 아직 보내지 않았으면 보낼 때를 적고 true(이 서버가 낸다). */
	fun claimSend(accountId: UUID, date: LocalDate): Boolean = jdbc.update(
		"""
		insert into today_sky_pushes (account_id, kst_date, sent_at) values (?, ?, now())
		on conflict (account_id, kst_date) do update set sent_at = now() where today_sky_pushes.sent_at is null
		""".trimIndent(),
		accountId, date,
	) == 1
}
