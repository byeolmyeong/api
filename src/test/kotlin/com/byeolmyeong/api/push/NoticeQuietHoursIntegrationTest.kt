package com.byeolmyeong.api.push

import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.MutableClock
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 공지 알림의 밤 무음(결정 057): 보내는 순간이 한국 시각 21:00 ~ 08:00 이면 소리를 빼고, 1.1.0 이상 안드로이드에만 `quiet` 채널을 싣는다.
 * 풀이 완성 · 오늘의 하늘 같은 다른 알림은 밤에도 지금 모양 그대로다. 시계 · Expo 는 가짜로 바꿔 끼운다.
 */
@Import(TestcontainersConfiguration::class, NoticeQuietHoursIntegrationTest.Fakes::class)
@SpringBootTest(properties = ["byeolmyeong.push.today-sky-cron=-"])
class NoticeQuietHoursIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	class Fakes {
		@Bean
		@Primary
		fun quietClock() = MutableClock(Instant.parse("2026-12-01T03:00:00Z"))

		@Bean
		@Primary
		fun quietExpo() = RecordingExpoPush()
	}

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var clock: MutableClock

	@Autowired
	private lateinit var expo: RecordingExpoPush

	@Autowired
	private lateinit var push: PushService

	private lateinit var notice: UUID
	private lateinit var someone: UUID

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		jdbc.update("delete from notices")
		expo.sent.clear()
		someone = account(
			"IOS" to "1.1.0",
			"ANDROID" to "1.1.0",
			"ANDROID" to "1.2.3",
			"ANDROID" to "1.0.0",
			"ANDROID" to null,
		)
		notice = UUID.randomUUID()
		jdbc.update("insert into notices (id, category, title, body, status, published_at) values (?, 'NOTICE', '점검 안내', '본문', 'PUBLISHED', now())", notice)
	}

	/** 푸시 동의한 계정 하나와 (플랫폼, 앱 버전) 기기들. 토큰은 `ExponentPushToken[플랫폼-버전]`. */
	private fun account(vararg devices: Pair<String, String?>): UUID {
		val id = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id, nickname, push_consented_at) values (?, 'TEST', ?, '밤손님', now())", id, id.toString())
		for ((platform, version) in devices) {
			jdbc.update("insert into push_devices (expo_token, account_id, platform, app_version) values (?, ?, ?, ?)", token(platform, version), id, platform, version)
		}
		return id
	}

	private fun token(platform: String, version: String?) = "ExponentPushToken[$platform-${version ?: "none"}]"

	/** 한국 시각 [hhmm](2026-12-01)로 시계를 맞춘다. */
	private fun at(hhmm: String) {
		clock.now = LocalDateTime.parse("2026-12-01T$hhmm:00").atZone(ZoneId.of("Asia/Seoul")).toInstant()
	}

	/** 기기마다 (소리, 채널). */
	private fun sentShape(): Map<String, Pair<Boolean, String?>> = expo.sent.associate { it.to to (it.sound to it.channelId) }

	private val day = mapOf(
		token("IOS", "1.1.0") to (true to null),
		token("ANDROID", "1.1.0") to (true to null),
		token("ANDROID", "1.2.3") to (true to null),
		token("ANDROID", "1.0.0") to (true to null),
		token("ANDROID", null) to (true to null),
	)

	private val night = mapOf(
		token("IOS", "1.1.0") to (false to null),
		token("ANDROID", "1.1.0") to (false to "quiet"),
		token("ANDROID", "1.2.3") to (false to "quiet"),
		token("ANDROID", "1.0.0") to (false to null),
		token("ANDROID", null) to (false to null),
	)

	private fun noticeAt(hhmm: String): Map<String, Pair<Boolean, String?>> {
		expo.sent.clear()
		at(hhmm)
		push.notice(notice)
		return sentShape()
	}

	@Test
	fun `20시 59분은 낮의 모양이다`() {
		assertEquals(day, noticeAt("20:59"))
	}

	@Test
	fun `21시 정각부터 소리를 빼고 1점1점0 이상 안드로이드에만 quiet 를 싣는다`() {
		assertEquals(night, noticeAt("21:00"))
	}

	@Test
	fun `7시 59분은 아직 밤이다`() {
		assertEquals(night, noticeAt("07:59"))
	}

	@Test
	fun `8시 정각부터 낮의 모양이다`() {
		assertEquals(day, noticeAt("08:00"))
	}

	@Test
	fun `다른 알림은 밤에도 지금 모양 그대로다`() {
		at("23:00")
		push.notify(someone, PushKind.ME_REPORT_DONE)
		push.sendMany(PushKind.TODAY_SKY, listOf(PushNotice(someone, "오늘의 하늘", "제목")))
		assertEquals(10, expo.sent.size)
		assertEquals(setOf(true to null), expo.sent.map { it.sound to it.channelId }.toSet())
	}
}
