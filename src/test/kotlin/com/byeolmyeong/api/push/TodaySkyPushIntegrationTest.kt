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
@SpringBootTest
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
		person("한번만")
		at("07:30")
		job.run()
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
		person("동의함", on = null, consent = true)
		person("동의안함", on = null, consent = false)

		at("07:30")
		job.run()
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
		assertEquals(0, skies(id))
		at("08:00")
		job.run()

		assertEquals(0, expo.sent.size)
	}

	@Test
	fun `받는 시간이 다른 사람은 그 시간에만 받는다`() {
		person("여덟시")
		person("아홉시", time = "09:00")

		for (time in listOf("07:30", "08:00", "08:30", "09:00")) {
			at(time)
			job.run()
		}

		assertEquals(listOf("ExponentPushToken[여덟시]", "ExponentPushToken[아홉시]"), expo.sent.map { it.to })
	}

	@Test
	fun `자정 언저리의 받는 시간은 앞날 밤에 다음 날 것을 미리 만든다`() {
		val id = person("자정이", time = "00:10")
		clock.now = LocalDate.parse("2026-11-20").atTime(23, 40).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()

		job.run()

		assertEquals(1, skies(id), "2026-11-21 의 것")
	}
}
