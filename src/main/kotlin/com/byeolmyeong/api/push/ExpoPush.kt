package com.byeolmyeong.api.push

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** Expo 가 한 요청에 받는 알림 수(Expo 문서: 100). */
private const val BATCH = 100

/**
 * 휴대폰 알림 한 건(결정 042 의 4). [data] 는 앱이 누른 뒤 갈 곳을 고르는 값이다.
 * [sound] 가 거짓이면 소리 없이(iOS — `sound` 를 뺀다), [channelId] 는 안드로이드 알림 채널(null 이면 싣지 않는다 — 기본 채널). 밤 무음은 결정 057.
 */
data class PushMessage(
	val to: String,
	val title: String,
	val body: String,
	val data: Map<String, String>,
	val sound: Boolean = true,
	val channelId: String? = null,
)

/** 한 건의 결과. */
enum class PushOutcome {
	/** Expo 가 받았다(기기에 닿았는지는 영수증이 말한다 — 1.0.0 은 보지 않는다). */
	OK,

	/** 그 기기에서 앱이 지워졌거나 알림 등록이 끝났다. 토큰을 지운다. */
	DEVICE_NOT_REGISTERED,

	/** 그 밖의 실패(Expo 의 오류 · 연결 실패 · 읽을 수 없는 답). 다시 보내지 않는다. */
	FAILED,

	/** 보내지 않았다(꺼짐 · 접근 토큰 없음). */
	SKIPPED,
}

/** Expo 푸시 서비스 호출(결정 042 의 1). 외부 호출이라 인터페이스 뒤에 둔다 — 테스트는 가짜로 바꾼다. */
interface ExpoPush {

	/** [messages] 를 100건씩 묶어 보내고 같은 순서로 결과를 준다. 던지지 않는다. */
	fun send(messages: List<PushMessage>): List<PushOutcome>
}

/**
 * `POST {base-url}/--/api/v2/push/send`. 끄기 스위치(`byeolmyeong.push.enabled`)가 꺼졌거나 `EXPO_ACCESS_TOKEN` 이 비면 보내지 않고 로그만 남긴다.
 * 토큰 · 본문 · 접근 토큰은 로그에 남기지 않는다(오류 코드와 개수만).
 */
@Component
class ExpoPushClient(
	@Value("\${byeolmyeong.push.enabled}") private val enabled: Boolean,
	@Value("\${byeolmyeong.push.expo-access-token}") private val accessToken: String,
	@Value("\${byeolmyeong.push.expo-base-url}") baseUrl: String,
) : ExpoPush {
	private val log = LoggerFactory.getLogger(javaClass)
	private val mapper = JsonMapper()
	private val client = RestClient.builder()
		.baseUrl(baseUrl)
		.requestFactory(SimpleClientHttpRequestFactory().apply {
			setConnectTimeout(Duration.ofSeconds(5))
			setReadTimeout(Duration.ofSeconds(15))
		})
		.build()

	override fun send(messages: List<PushMessage>): List<PushOutcome> {
		if (messages.isEmpty()) return emptyList()
		val skip = when {
			!enabled -> "DISABLED"
			accessToken.isBlank() -> "NO_ACCESS_TOKEN"
			else -> null
		}
		if (skip != null) {
			log.info("event=push_skipped reason={} count={}", skip, messages.size)
			return messages.map { PushOutcome.SKIPPED }
		}
		return messages.chunked(BATCH).flatMap(::sendBatch)
	}

	private fun sendBatch(batch: List<PushMessage>): List<PushOutcome> {
		val payload = batch.map { message ->
			buildMap {
				put("to", message.to)
				put("title", message.title)
				put("body", message.body)
				put("data", message.data)
				// 빼면 소리가 없다(iOS). 채널은 있을 때만 — 기기에 없는 채널이면 알림이 아예 보이지 않는다(Expo 문서, 결정 057)
				if (message.sound) put("sound", "default")
				message.channelId?.let { put("channelId", it) }
			}
		}
		val body = try {
			client.post().uri("/--/api/v2/push/send")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer $accessToken")
				.body(mapper.writeValueAsString(payload))
				.retrieve()
				.body(String::class.java)
		} catch (e: RestClientException) {
			log.warn("event=push_failed reason=REQUEST_{} count={}", e.javaClass.simpleName, batch.size)
			return batch.map { PushOutcome.FAILED }
		}
		val tickets = try {
			body?.let(mapper::readTree)?.get("data")
		} catch (_: JacksonException) {
			null
		}
		if (tickets == null || !tickets.isArray || tickets.size() != batch.size) {
			log.warn("event=push_failed reason=UNREADABLE_RESPONSE count={}", batch.size)
			return batch.map { PushOutcome.FAILED }
		}
		return (0 until tickets.size()).map { outcome(tickets[it]) }
	}

	private fun outcome(ticket: JsonNode): PushOutcome {
		if (ticket.path("status").asString("") == "ok") return PushOutcome.OK
		val error = ticket.path("details").path("error").asString("UNKNOWN")
		if (error == "DeviceNotRegistered") return PushOutcome.DEVICE_NOT_REGISTERED
		// 오류 코드는 Expo 가 정한 영문 이름뿐이다(MessageTooBig · MessageRateExceeded · InvalidCredentials 등).
		log.warn("event=push_failed reason={}", error.filter { it.isLetterOrDigit() }.take(40))
		return PushOutcome.FAILED
	}
}
