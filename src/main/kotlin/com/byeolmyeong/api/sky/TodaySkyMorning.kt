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
import java.util.concurrent.atomic.AtomicInteger

private val KST = ZoneId.of("Asia/Seoul")

/** 받는 시간보다 이만큼 앞부터 미리 만든다(결정 042 의 6). */
private const val PREBUILD_MINUTES = 30L

/**
 * 받는 시간 칸을 놓쳤을 때 따라잡아 보내는 한계. 이보다 늦으면 아침 알림이 한낮에 가지 않게 보내지 않는다.
 * 받는 시간 전에 미리 만들지 못한 사람도 이 한계까지는 맡아 만든다 — 만든 다음 번이 보낸다(결정 052).
 */
private const val CATCH_UP_MINUTES = 60L

/** 미리 만들기가 맡을 사람을 한 번에 읽는 수(날짜마다). 읽은 사람을 모두 다른 줄기 · 서버가 먼저 맡았으면 그 줄기는 끝난다. */
private const val CANDIDATE_BATCH = 20

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
 * ② 받는 시간이 앞으로 30분 안이거나 지난 60분 안이고 그날 아직 맡지 않은 사람의 그날 것을 미리 만든다 — 생성 일꾼(`reportExecutor`)에서
 *   미리 만들기 줄기를 일꾼 수 - 1 까지(그리고 빈 스레드 - 1 까지) 띄우고, 줄기는 한 사람씩 맡아 바로 만들며 맡을 사람이 없을 때까지 이어 간다(결정 052).
 *   사용자 요청이 먼저다: 한 스레드는 늘 비워 두고, 대기 줄은 쓰지 않으며, 사용자 요청이 줄에서 기다리면 줄기는 하던 한 사람을 마치고 끝난다.
 *   맡기는 만들기 직전에 하므로, 일꾼이 받지 않거나(줄이 참 · 닫는 중) 서버를 닫아 줄기가 끝나도 맡은 채 남는 사람이 없다 — 다음 번 · 새 서버가 맡는다.
 * 칸을 놓쳐도(늦게 돈 · 재시작) 다음 번이 따라잡는다. 같은 날 한 번: (계정, 한국 날짜) 줄을 먼저 넣은 서버만 맡고, 보낸 때를 먼저 적은 서버만 보낸다 —
 * 서버 둘이 함께 떠도(결정 046) 두 번 가지 않는다(일정 잠금 대신 줄 단위 선점). 한 사람의 오류는 그 사람만 건너뛴다.
 * 대상은 푸시 수신 동의 + 오늘의 하늘 알림 켬(직접 정한 값, 없으면 켬). 정지 · 기기 없음 · 출생 정보 없음은 대상이 아니다. 스위치 `byeolmyeong.push.today-sky-enabled`.
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

	// 이 서버에서 도는 미리 만들기 줄기 수(줄기 하나 = 생성 일꾼 스레드 하나).
	private val builders = AtomicInteger()

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
		val now = now()
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

	/**
	 * 미리 만들 사람이 있으면 생성 일꾼에 미리 만들기 줄기를 띄운다 — 이 서버에서 일꾼 수 - 1 까지, 그리고 지금 빈 스레드 - 1 까지
	 * (한 스레드는 늘 사용자 요청 몫이고, 빈 스레드가 없으면 줄기가 대기 줄에 들어가 사용자 요청 앞에 서므로 띄우지 않는다).
	 * 사용자 요청이 줄에서 기다리거나 서버를 닫는 중이면 띄우지 않는다(다음 번이 따라잡는다). 띄우지 못해도 아무도 맡지 않았다.
	 */
	private fun prebuild(now: LocalDateTime) {
		val pool = generation.threadPoolExecutor
		if (pool.isShutdown || pool.queue.isNotEmpty()) return
		val free = pool.maximumPoolSize - pool.activeCount // 대략의 값이어도 된다 — 다음 번이 다시 본다
		val wanted = minOf(candidates(now).size, pool.maximumPoolSize - 1 - builders.get(), free - 1)
		var started = 0
		while (started < wanted) {
			builders.incrementAndGet()
			try {
				generation.execute(::build)
			} catch (_: TaskRejectedException) {
				builders.decrementAndGet()
				break
			}
			started++
		}
		if (started > 0) log.info("event=today_sky_prebuild_started builders={}", started)
	}

	/**
	 * 미리 만들기 줄기(생성 일꾼 스레드에서 돈다): 한 사람을 맡아 바로 만들고 다음 사람으로 이어 간다.
	 * 맡을 사람이 없거나, 사용자 요청이 줄에서 기다리거나(하던 한 사람은 마친다), 서버를 닫기 시작하면 끝난다.
	 */
	private fun build() {
		val pool = generation.threadPoolExecutor
		var built = 0
		try {
			while (!pool.isShutdown) {
				val (id, date) = claimNext(now()) ?: break
				try {
					daySkies.daySky(id, date, countQuota = false)
					built++
				} catch (e: RuntimeException) {
					// 그날은 알림 없이 둔다(열 때 만든다 — 앱 결정 051). 출생 정보 · 글은 남기지 않는다.
					log.warn("event=today_sky_prebuild_failed reason={}", e.javaClass.simpleName)
				}
				if (pool.queue.isNotEmpty()) break // 사용자 요청이 먼저다 — 남은 사람은 다음 번이 따라잡는다
			}
		} catch (e: RuntimeException) {
			log.warn("event=today_sky_prebuild_failed reason={}", e.javaClass.simpleName)
		} finally {
			builders.decrementAndGet()
			if (built > 0) log.info("event=today_sky_prebuilt count={}", built)
		}
	}

	/** 받는 시간이 지난 60분(보내기가 따라잡는 한계) ~ 앞으로 30분 안이고 그날 아직 아무도 맡지 않은 사람. 받는 시간이 이른 사람부터. */
	private fun candidates(now: LocalDateTime): List<Pair<UUID, LocalDate>> =
		slots(now.minusMinutes(CATCH_UP_MINUTES), now.plusMinutes(PREBUILD_MINUTES)).flatMap { slot ->
			repository.dueToPrebuild(slot.date, slot.from, slot.to, CANDIDATE_BATCH).map { it to slot.date }
		}

	/** 다음 사람을 이 서버가 맡는다(그날의 줄을 처음 넣은 서버만 — 결정 042 의 6). 맡을 사람이 없으면 null. */
	private fun claimNext(now: LocalDateTime): Pair<UUID, LocalDate>? =
		candidates(now).firstOrNull { (id, date) -> repository.claimPrebuild(id, date) }

	private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), KST).withSecond(0).withNano(0)
}

/**
 * 아침 알림이 읽는 것. 계정 · 기기 · 출생 정보 표는 대상을 고를 만큼만 읽는다(관리자 현황과 같은 방식 — 결정 014).
 * 보내는 대상은 푸시 수신 동의를 했고(끄면 어떤 푸시도 가지 않는다 — 결정 042 의 4) 오늘의 하늘 알림을 끄지 않은 사람이다
 * (`today_sky_push` 가 null 이면 켬 — 동의의 기본을 따른다). 관리자 사용자 상세의 `todaySky.on` 과 같은 식이다.
 */
@Repository
class TodaySkyMorningRepository(private val jdbc: JdbcTemplate) {

	private val target = """
		select a.id from accounts a
		where a.push_consented_at is not null
		  and coalesce(a.today_sky_push, true)
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

	/** [date] 에 받는 시간이 [from]~[to] 이고 그날 아직 아무도 맡지 않은 대상. 받는 시간이 이른 사람부터 [limit] 명까지. */
	fun dueToPrebuild(date: LocalDate, from: LocalTime, to: LocalTime, limit: Int): List<UUID> = jdbc.queryForList(
		"$target and not exists (select 1 from today_sky_pushes t where t.account_id = a.id and t.kst_date = ?) order by a.today_sky_time, a.id limit ?",
		UUID::class.java, from, to, date, limit,
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
