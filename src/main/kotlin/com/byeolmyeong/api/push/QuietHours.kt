package com.byeolmyeong.api.push

import com.byeolmyeong.api.birth.KOREA_ZONE
import com.byeolmyeong.api.web.AppVersion
import com.byeolmyeong.api.web.parseAppVersion
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/** 서비스 알림을 받을 기기 하나: 계정 · 토큰 · 플랫폼(`IOS` · `ANDROID`) · 등록할 때의 앱 버전(모르면 null). */
data class PushTarget(val accountId: UUID, val token: String, val platform: String, val appVersion: String?)

/**
 * 밤 무음(결정 055 의 6 · 앱 결정 104 의 9): 한국 시각 21:00 ~ 다음 날 08:00 에 보내는 서비스 알림(공지 · 궁합 알림)은 소리 없이 보낸다.
 * iOS 는 `sound` 를 빼고, 안드로이드는 1.1.0 앱이 만든 조용한 채널 `quiet` 로 보낸다 — 그 채널이 없는 기기에 실으면 알림이 아예 보이지 않으므로(Expo 문서)
 * 1.1.0 이상 안드로이드에만 싣는다. 광고 푸시의 "보내지 않는 밤"(admin, 법)과 시각은 같지만 따로 바뀔 수 있어 묶지 않는다.
 */
internal object QuietHours {
	private val START = LocalTime.of(21, 0)
	private val END = LocalTime.of(8, 0)

	/** 1.1.0 앱이 만드는 안드로이드의 조용한 알림 채널. */
	const val CHANNEL = "quiet"
	private val CHANNEL_SINCE = AppVersion(1, 1, 0)

	/** [now] 가 한국 시각으로 21:00 이상이거나 08:00 미만인가. */
	fun isNight(now: Instant): Boolean {
		val time = now.atZone(KOREA_ZONE).toLocalTime()
		return time >= START || time < END
	}

	/** 그 기기에 조용한 채널이 있는가: 안드로이드이고 앱 버전이 1.1.0 이상. 버전을 모르거나 읽을 수 없으면 없다고 본다. */
	fun hasQuietChannel(platform: String, appVersion: String?): Boolean =
		platform == "ANDROID" && appVersion?.let(::parseAppVersion)?.let { it >= CHANNEL_SINCE } == true

	/** [device] 에 보낼 한 통. [night] 면 소리를 빼고 채널이 있는 기기에만 [CHANNEL] 을 싣는다. 낮에는 지금 모양(소리 있음 · 채널 없음). */
	fun message(device: PushTarget, title: String, body: String, data: Map<String, String>, night: Boolean): PushMessage =
		if (!night) {
			PushMessage(device.token, title, body, data)
		} else {
			PushMessage(device.token, title, body, data, sound = false, channelId = CHANNEL.takeIf { hasQuietChannel(device.platform, device.appVersion) })
		}
}
