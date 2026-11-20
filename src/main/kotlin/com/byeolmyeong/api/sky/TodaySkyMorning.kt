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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

private val KST = ZoneId.of("Asia/Seoul")

/** 받는 시간보다 이만큼 앞에 미리 만든다(결정 042 의 6). */
private const val PREBUILD_MINUTES = 30L

/** 일정의 간격. 받는 시간은 5분 단위라 한 번에 한 칸만 본다. */
private const val STEP_MINUTES = 5

/** 오늘의 하늘 아침 일이 일정 스레드를 막지 않게 따로 도는 일꾼 하나. */
@Configuration
class TodaySkyExecutorConfig {

	@Bean
	fun todaySkyExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 2
		setThreadNamePrefix("today-sky-")
	}
}

/**
 * 오늘의 하늘 아침 알림(결정 042 의 6). 5분마다 한국 시각으로
 * ① 받는 시간이 30분 뒤인 켠 사람의 그날 것을 미리 만들고(그날의 하늘 생성 그대로 — 생성 한도는 세지 않는다),
 * ② 받는 시간이 된 사람에게 보내라고 [TodaySkyDue] 를 낸다(`push` 가 보낸다). 그날 것이 없으면(미리 만들기 실패 등) 내지 않는다.
 * 같은 날 한 번: (계정, 한국 날짜) 줄을 먼저 넣은 서버만 미리 만들고, 보낸 때를 먼저 적은 서버만 낸다 — 서버 둘이 함께 떠도(결정 046) 두 번 가지 않는다.
 * 켬은 직접 정한 값, 없으면 푸시 수신 동의. 정지 · 기기 없음 · 출생 정보 없음은 대상이 아니다. 스위치 `byeolmyeong.push.today-sky-enabled`.
 */
@Service
class TodaySkyMorningService(
	private val repository: TodaySkyMorningRepository,
	private val daySkies: DaySkyService,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	@Qualifier("todaySkyExecutor") private val executor: ThreadPoolTaskExecutor,
	@Value("\${byeolmyeong.push.today-sky-enabled}") private val enabled: Boolean,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** 일정 스레드는 넘기기만 한다(미리 만들기는 사람마다 수십 초라 박동 같은 다른 일정을 막지 않게). */
	@Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
	fun tick() {
		try {
			executor.execute(::run)
		} catch (_: TaskRejectedException) {
			log.warn("event=today_sky_tick_skipped reason=BUSY")
		}
	}

	/** 한 번 돈다(테스트가 직접 부른다). */
	fun run() {
		if (!enabled) return
		val now = ZonedDateTime.ofInstant(clock.instant(), KST).truncatedTo(ChronoUnit.MINUTES).let { it.withMinute(it.minute - it.minute % STEP_MINUTES) }
		prebuild(now.plusMinutes(PREBUILD_MINUTES))
		send(now)
	}

	private fun prebuild(target: ZonedDateTime) {
		val date = target.toLocalDate()
		var built = 0
		for (id in repository.due(target.toLocalTime())) {
			if (!repository.claimPrebuild(id, date)) continue
			try {
				daySkies.daySky(id, date, countQuota = false)
				built++
			} catch (e: RuntimeException) {
				// 그날은 알림 없이 둔다(열 때 만든다 — 앱 결정 051). 출생 정보 · 글은 남기지 않는다.
				log.warn("event=today_sky_prebuild_failed reason={}", e.javaClass.simpleName)
			}
		}
		if (built > 0) log.info("event=today_sky_prebuilt count={}", built)
	}

	private fun send(now: ZonedDateTime) {
		val date = now.toLocalDate()
		var due = 0
		for (id in repository.due(now.toLocalTime())) {
			val sky = daySkies.stored(id, date) ?: continue
			if (!repository.claimSend(id, date)) continue
			events.publishEvent(TodaySkyDue(id, date, sky.title))
			due++
		}
		if (due > 0) log.info("event=today_sky_due count={}", due)
	}
}

/** 아침 알림이 읽는 것. 계정 · 기기 · 출생 정보 표는 대상을 고를 만큼만 읽는다(관리자 현황과 같은 방식 — 결정 014). */
@Repository
class TodaySkyMorningRepository(private val jdbc: JdbcTemplate) {

	/** 받는 시간이 [time] 인 대상: 켬(직접 정한 값, 없으면 푸시 수신 동의) · 정지 아님 · 기기 있음 · 출생 정보 있음. */
	fun due(time: LocalTime): List<UUID> = jdbc.queryForList(
		"""
		select a.id from accounts a
		where coalesce(a.today_sky_push, a.push_consented_at is not null)
		  and a.suspended_at is null
		  and a.today_sky_time = ?
		  and exists (select 1 from push_devices d where d.account_id = a.id)
		  and exists (select 1 from profiles p where p.account_id = a.id)
		order by a.id
		""".trimIndent(),
		UUID::class.java, time,
	).filterNotNull()

	/** 그날의 줄을 이 서버가 처음 넣었으면 true(미리 만들기를 맡는다). */
	fun claimPrebuild(accountId: UUID, date: LocalDate): Boolean =
		jdbc.update("insert into today_sky_pushes (account_id, kst_date) values (?, ?) on conflict do nothing", accountId, date) == 1

	/** 그날 아직 보내지 않았으면 보낸 때를 적고 true(이 서버가 낸다). */
	fun claimSend(accountId: UUID, date: LocalDate): Boolean = jdbc.update(
		"""
		insert into today_sky_pushes (account_id, kst_date, sent_at) values (?, ?, now())
		on conflict (account_id, kst_date) do update set sent_at = now() where today_sky_pushes.sent_at is null
		""".trimIndent(),
		accountId, date,
	) == 1
}
