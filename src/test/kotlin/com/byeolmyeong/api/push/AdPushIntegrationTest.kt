package com.byeolmyeong.api.push

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.PushEventRequested
import com.byeolmyeong.api.admin.PushEventSent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/** `admin.PushEventSent` 를 모아 두는 듣는 쪽(관리자 발송 내역 대신). */
class SentEvents {
	val received = CopyOnWriteArrayList<PushEventSent>()

	@EventListener
	fun on(event: PushEventSent) {
		received += event
	}
}

/**
 * 광고 푸시의 보내는 쪽(결정 043 의 6 · 042 의 7): `admin.PushEventRequested` 를 듣고 푸시 · 마케팅 둘 다 동의 + 기기 + 정지 아님인 사람에게
 * 관리자가 정한 글 그대로(앞뒤 줄은 admin 이 붙임) 보내고, `admin.PushEventSent` 로 대상 · 성공 · 실패 수를 알린다.
 */
@Import(TestcontainersConfiguration::class, AdPushIntegrationTest.Fakes::class)
@SpringBootTest(properties = [DEV_AUTH_ON])
class AdPushIntegrationTest {

	@TestConfiguration
	class Fakes {
		@Bean
		@Primary
		fun adExpo() = RecordingExpoPush()

		@Bean
		fun sentEvents() = SentEvents()
	}

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var events: ApplicationEventPublisher

	@Autowired
	private lateinit var expo: RecordingExpoPush

	@Autowired
	private lateinit var sent: SentEvents

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		expo.sent.clear()
		expo.gone.clear()
		expo.skipAll = false
		expo.explode = false
		sent.received.clear()
	}

	private fun person(name: String, push: Boolean = true, marketing: Boolean = true, devices: Int = 1, suspended: Boolean = false): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into accounts (id, provider, provider_user_id, push_consented_at, marketing_consented_at, suspended_at) values (?, 'TEST', ?, ${if (push) "now()" else "null"}, ${if (marketing) "now()" else "null"}, ${if (suspended) "now()" else "null"})",
			id, name,
		)
		repeat(devices) { jdbc.update("insert into push_devices (expo_token, account_id, platform) values (?, ?, 'ANDROID')", "ExponentPushToken[$name-$it]", id) }
		return id
	}

	private fun awaitEvent(): PushEventSent {
		val until = System.currentTimeMillis() + 5_000
		while (sent.received.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
		return sent.received.single()
	}

	@Test
	fun `둘 다 동의하고 기기가 있고 정지되지 않은 사람에게만 보내고 결과를 알린다`() {
		person("받는이", devices = 2)
		person("또받는이")
		person("푸시만", marketing = false)
		person("마케팅만", push = false)
		person("기기없음", devices = 0)
		person("정지된이", suspended = true)
		val eventId = UUID.randomUUID()

		events.publishEvent(PushEventRequested(eventId, "(광고) 겨울 이벤트", "새 기능을 만나 보세요\n수신 거부: 마이 › 알림", "HOME"))

		val result = awaitEvent()
		assertEquals(PushEventSent(eventId, recipients = 2, succeeded = 2, failed = 0), result)
		assertEquals(setOf("ExponentPushToken[받는이-0]", "ExponentPushToken[받는이-1]", "ExponentPushToken[또받는이-0]"), expo.sent.map { it.to }.toSet())
		assertEquals(setOf("(광고) 겨울 이벤트"), expo.sent.map { it.title }.toSet(), "글은 admin 이 붙인 그대로")
		assertEquals(setOf("새 기능을 만나 보세요\n수신 거부: 마이 › 알림"), expo.sent.map { it.body }.toSet())
		assertEquals(setOf(mapOf("type" to "EVENT", "target" to "HOME")), expo.sent.map { it.data }.toSet())
	}

	@Test
	fun `기기가 모두 지워진 사람은 실패로 세고 그 토큰을 지운다`() {
		person("살아있음")
		person("지운앱")
		expo.gone += "ExponentPushToken[지운앱-0]"
		val eventId = UUID.randomUUID()

		events.publishEvent(PushEventRequested(eventId, "(광고) 제목", "본문\n수신 거부: 마이 › 알림", "TODAY_SKY"))

		assertEquals(PushEventSent(eventId, recipients = 2, succeeded = 1, failed = 1), awaitEvent())
		val until = System.currentTimeMillis() + 5_000
		fun left() = jdbc.queryForList("select expo_token from push_devices order by expo_token", String::class.java)
		while (left().size > 1 && System.currentTimeMillis() < until) Thread.sleep(20)
		assertEquals(listOf("ExponentPushToken[살아있음-0]"), left())
	}

	@Test
	fun `보내지 않은 기기(꺼짐 · 접근 토큰 없음)는 실패로 세지 않는다`() {
		person("건너뜀")
		expo.skipAll = true
		val eventId = UUID.randomUUID()

		events.publishEvent(PushEventRequested(eventId, "(광고) 제목", "본문", "HOME"))

		assertEquals(PushEventSent(eventId, recipients = 1, succeeded = 0, failed = 0), awaitEvent())
	}

	@Test
	fun `보내다 오류가 나도 결과를 알려 발송 내역이 끝난다`() {
		person("받을사람")
		expo.explode = true
		val eventId = UUID.randomUUID()

		events.publishEvent(PushEventRequested(eventId, "(광고) 제목", "본문", "HOME"))

		assertEquals(PushEventSent(eventId, recipients = 1, succeeded = 0, failed = 1), awaitEvent())
	}

	@Test
	fun `대상이 없으면 Expo 를 부르지 않고 0 으로 알린다`() {
		person("동의없음", push = false, marketing = false)
		val eventId = UUID.randomUUID()

		events.publishEvent(PushEventRequested(eventId, "(광고) 제목", "본문", "MY"))

		assertEquals(PushEventSent(eventId, 0, 0, 0), awaitEvent())
		assertEquals(0, expo.sent.size)
	}
}
