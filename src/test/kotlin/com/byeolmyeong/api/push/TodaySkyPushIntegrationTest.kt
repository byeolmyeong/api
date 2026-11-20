package com.byeolmyeong.api.push

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.MutableClock
import com.byeolmyeong.api.birth.BirthProfile
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.sky.FakeDaySkyGenerator
import com.byeolmyeong.api.sky.TodaySkyMorningService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 오늘의 하늘 아침 알림(결정 042 의 6): 받는 시간 30분 전에 그날 것을 미리 만들고, 받는 시간에 보낸다(한국 시각, 5분마다).
 * 같은 날 한 번 · 끈 사람 · 정지 · 기기 없음은 0건. 시계 · 생성기 · Expo 는 가짜로 바꿔 끼운다. 일정은 기다리지 않고 한 번 도는 메서드를 부른다.
 */
@Import(TestcontainersConfiguration::class, TodaySkyPushIntegrationTest.Fakes::class)
@SpringBootTest(properties = ["byeolmyeong.push.today-sky-cron=-"])
class TodaySkyPushIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class Fakes {
		@Bean
		@Primary
		fun todaySkyClock() = MutableClock(Instant.parse("2026-11-20T22:25:00Z")) // 한국 2026-11-21 07:25

		@Bean
		@Primary
		fun todaySkyGenerator() = FakeDaySkyGenerator()

		@Bean
		@Primary
		fun todaySkyExpo() = RecordingExpoPush()
	}

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var clock: MutableClock

	@Autowired
	private lateinit var generator: FakeDaySkyGenerator

	@Autowired
	private lateinit var expo: RecordingExpoPush

	@Autowired
	private lateinit var profiles: BirthProfileService

	@Autowired
	private lateinit var job: TodaySkyMorningService

	private val today = LocalDate.parse("2026-11-21")

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		generator.reset()
		expo.sent.clear()
		expo.gone.clear()
		at("07:25")
	}

	/** 한국 시각 [hhmm](2026-11-21)로 시계를 맞춘다. */
	private fun at(hhmm: String) {
		clock.now = today.atTime(LocalTime.parse(hhmm)).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()
	}

	/** 오늘의 하늘 알림을 켠(또는 [on] 대로) 계정 하나 — 출생 정보 · 기기 하나. */
	private fun person(name: String, time: String = "08:00", on: Boolean? = true, device: Boolean = true, consent: Boolean = false): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into accounts (id, provider, provider_user_id, nickname, today_sky_push, today_sky_time, push_consented_at) values (?, 'TEST', ?, ?, ?, ?::time, ?)",
			id, id.toString(), name, on, time, if (consent) java.sql.Timestamp.from(Instant.now()) else null,
		)
		profiles.save(id, BirthProfile(LocalDate.parse("1990-03-15"), null, null))
		if (device) jdbc.update("insert into push_devices (expo_token, account_id, platform) values (?, ?, 'IOS')", "ExponentPushToken[$name]", id)
		return id
	}

	private fun skies(id: UUID): Int = jdbc.queryForObject("select count(*) from day_skies where owner_id = ? and date = ?", Int::class.java, id, today)!!

	@Test
	fun `받는 시간 30분 전에 그날 것을 미리 만들고 받는 시간에 그 제목으로 보낸다`() {
		val id = person("아침이")

		at("07:30")
		job.run()
		awaitSky(id)
		assertEquals(1, skies(id), "30분 전에 미리 만든다")
		assertEquals(1, generator.prompts.size)
		assertEquals(0, expo.sent.size, "아직 보내지 않는다")

		at("08:00")
		job.run()
		val sent = expo.sent.single()
		assertEquals("ExponentPushToken[아침이]", sent.to)
		assertEquals("오늘의 하늘", sent.title)
		val title = jdbc.queryForObject("select body->>'title' from day_skies where owner_id = ?", String::class.java, id)
		assertEquals(title, sent.body)
		assertEquals(mapOf("type" to "TODAY_SKY", "date" to "2026-11-21"), sent.data)
		assertEquals(1, generator.prompts.size, "보낼 때는 만들지 않는다")
	}

	@Test
	fun `같은 날 두 번 돌아도 · 서버가 둘이어도 한 번만 보낸다`() {
		val id = person("한번만")
		at("07:30")
		job.run()
		awaitSky(id)
		at("08:00")
		job.run()
		job.run()
		at("08:00")
		job.run() // 다른 서버가 같은 시각에 도는 것과 같다

		assertEquals(1, expo.sent.size)
	}

	@Test
	fun `끈 사람 · 정지된 사람 · 기기가 없는 사람에게는 만들지도 보내지도 않는다`() {
		person("끈이", on = false)
		val suspended = person("정지된이")
		jdbc.update("update accounts set suspended_at = now() where id = ?", suspended)
		person("기기없음", device = false)

		at("07:30")
		job.run()
		at("08:00")
		job.run()

		assertEquals(0, generator.prompts.size)
		assertEquals(0, expo.sent.size)
	}

	@Test
	fun `직접 정한 적 없으면 푸시 수신 동의를 따른다`() {
		val agreed = person("동의함", on = null, consent = true)
		person("동의안함", on = null, consent = false)

		at("07:30")
		job.run()
		awaitSky(agreed)
		at("08:00")
		job.run()

		assertEquals(listOf("ExponentPushToken[동의함]"), expo.sent.map { it.to })
	}

	@Test
	fun `미리 만들지 못한 날은 보내지 않는다`() {
		val id = person("실패한날")
		generator.respond = { _, _ -> null }

		at("07:30")
		job.run()
		Thread.sleep(500) // 일꾼이 세 번 시도하고 그만둘 때까지
		assertEquals(0, skies(id))
		at("08:00")
		job.run()

		assertEquals(0, expo.sent.size)
	}

	@Test
	fun `받는 시간이 다른 사람은 그 시간에만 받는다`() {
		val eight = person("여덟시")
		val nine = person("아홉시", time = "09:00")

		for (time in listOf("07:30", "08:00", "08:30", "09:00")) {
			at(time)
			job.run()
			if (time == "07:30") awaitSky(eight)
			if (time == "08:30") awaitSky(nine)
		}

		assertEquals(listOf("ExponentPushToken[여덟시]", "ExponentPushToken[아홉시]"), expo.sent.map { it.to })
	}

	/** 미리 만들기는 생성 일꾼에서 돈다 — 그 사람의 그날 것이 생길 때까지 기다린다(5초까지). */
	private fun awaitSky(id: UUID) {
		val until = System.currentTimeMillis() + 5_000
		while (skies(id) == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
	}

	@Test
	fun `받는 시간 칸을 놓쳐도 60분 안이면 다음 번에 따라잡아 보낸다`() {
		val id = person("늦은칸")
		at("07:30")
		job.run()
		awaitSky(id)

		at("08:05") // 08:00 은 돌지 못했다(재시작 · 늦어짐)
		job.run()
		assertEquals(listOf("ExponentPushToken[늦은칸]"), expo.sent.map { it.to })

		at("08:10")
		job.run()
		assertEquals(1, expo.sent.size, "따라잡은 뒤에도 한 번")
	}

	@Test
	fun `60분이 넘게 지난 칸은 보내지 않는다 — 한낮에 아침 알림이 가지 않게`() {
		val id = person("너무늦음")
		at("07:30")
		job.run()
		awaitSky(id)

		at("09:05")
		job.run()

		assertEquals(0, expo.sent.size)
	}

	@Test
	fun `미리 만들기 칸을 놓쳐도 받는 시간 전이면 따라잡아 만든다`() {
		val id = person("늦게만듦")

		at("07:45") // 07:30 은 돌지 못했다
		job.run()
		awaitSky(id)

		assertEquals(1, skies(id))
	}

	@Test
	fun `한 사람에게서 오류가 나도 다른 사람은 받는다`() {
		val broken = person("깨진프로필")
		jdbc.update("update profiles set birth_time = '09:00', place_id = 999999999 where account_id = ?", broken) // 목록에 없는 곳 — 차트를 못 만든다
		val ok = person("멀쩡함")
		at("07:30")
		job.run()
		awaitSky(ok)

		at("08:00")
		job.run()

		assertEquals(listOf("ExponentPushToken[멀쩡함]"), expo.sent.map { it.to })
	}

	@Test
	fun `자정 언저리의 받는 시간은 앞날 밤에 다음 날 것을 미리 만든다`() {
		val id = person("자정이", time = "00:10")
		clock.now = LocalDate.parse("2026-11-20").atTime(23, 40).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()

		job.run()
		awaitSky(id)

		assertEquals(1, skies(id), "2026-11-21 의 것")
	}
}
