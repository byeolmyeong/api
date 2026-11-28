package com.byeolmyeong.api.push

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 밤 무음(결정 057): 한국 시각 21:00 ~ 다음 날 08:00, 조용한 채널은 1.1.0 이상 안드로이드에만. */
class QuietHoursTest {

	private fun kst(text: String) = LocalDateTime.parse(text).atZone(ZoneId.of("Asia/Seoul")).toInstant()

	@Test
	fun `밤은 21시부터 다음 날 8시 전까지다`() {
		assertFalse(QuietHours.isNight(kst("2026-12-01T20:59:59")), "20:59")
		assertTrue(QuietHours.isNight(kst("2026-12-01T21:00:00")), "21:00")
		assertTrue(QuietHours.isNight(kst("2026-12-02T00:00:00")), "자정")
		assertTrue(QuietHours.isNight(kst("2026-12-02T07:59:59")), "07:59")
		assertFalse(QuietHours.isNight(kst("2026-12-02T08:00:00")), "08:00")
		assertFalse(QuietHours.isNight(kst("2026-12-02T12:00:00")), "한낮")
		assertTrue(QuietHours.isNight(java.time.Instant.parse("2026-12-01T12:00:00Z")), "UTC 12:00 = 한국 21:00 — 서버 시간대와 상관없다")
		assertFalse(QuietHours.isNight(java.time.Instant.parse("2026-12-01T11:59:00Z")), "UTC 11:59 = 한국 20:59")
	}

	@Test
	fun `조용한 채널은 앱 버전이 1점1점0 이상인 안드로이드에만 있다`() {
		assertTrue(QuietHours.hasQuietChannel("ANDROID", "1.1.0"))
		assertTrue(QuietHours.hasQuietChannel("ANDROID", "1.1"))
		assertTrue(QuietHours.hasQuietChannel("ANDROID", "1.10.0"))
		assertTrue(QuietHours.hasQuietChannel("ANDROID", "2"))
		assertFalse(QuietHours.hasQuietChannel("ANDROID", "1.0.9"))
		assertFalse(QuietHours.hasQuietChannel("ANDROID", "1.0.0"))
		assertFalse(QuietHours.hasQuietChannel("ANDROID", null), "버전을 모르면 없다고 본다")
		assertFalse(QuietHours.hasQuietChannel("ANDROID", "1.1.0-beta"))
		assertFalse(QuietHours.hasQuietChannel("IOS", "1.1.0"))
	}

	@Test
	fun `밤에는 소리를 빼고 채널이 있는 기기에만 quiet 를 싣고 낮에는 지금 모양이다`() {
		val data = mapOf("type" to "NOTICE")
		fun device(platform: String, version: String?) = PushTarget(java.util.UUID.randomUUID(), "ExponentPushToken[$platform-$version]", platform, version)

		val android = QuietHours.message(device("ANDROID", "1.1.0"), "제목", "본문", data, night = true)
		assertFalse(android.sound)
		assertEquals("quiet", android.channelId)
		assertEquals(PushMessage("ExponentPushToken[ANDROID-1.1.0]", "제목", "본문", data, sound = false, channelId = "quiet"), android)

		val oldAndroid = QuietHours.message(device("ANDROID", "1.0.0"), "제목", "본문", data, night = true)
		assertFalse(oldAndroid.sound)
		assertNull(oldAndroid.channelId, "1.1.0 미만 안드로이드에는 quiet 를 싣지 않는다")

		val ios = QuietHours.message(device("IOS", "1.1.0"), "제목", "본문", data, night = true)
		assertFalse(ios.sound)
		assertNull(ios.channelId)

		val day = QuietHours.message(device("ANDROID", "1.1.0"), "제목", "본문", data, night = false)
		assertEquals(PushMessage("ExponentPushToken[ANDROID-1.1.0]", "제목", "본문", data), day)
		assertTrue(day.sound)
		assertNull(day.channelId)
	}
}
