package com.byeolmyeong.api.push

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.admin.InquiryAnswered
import com.byeolmyeong.api.reading.ReadingCreated
import com.byeolmyeong.api.report.TimingReportDone
import com.byeolmyeong.api.report.MeReportDone
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/** Expo 대신 받은 알림을 모아 두고, 정해 둔 토큰에는 DeviceNotRegistered 로 답한다. */
class RecordingExpoPush : ExpoPush {
	val sent = CopyOnWriteArrayList<PushMessage>()
	val gone = CopyOnWriteArrayList<String>()

	/** 꺼짐 · 접근 토큰 없음처럼 모두 보내지 않는다. */
	@Volatile
	var skipAll = false

	/** 던진다(인터페이스는 던지지 않지만, 그 밖의 오류가 나도 결과를 알리는지 본다). */
	@Volatile
	var explode = false

	override fun send(messages: List<PushMessage>): List<PushOutcome> {
		if (explode) throw IllegalStateException("가짜 오류")
		if (skipAll) return messages.map { PushOutcome.SKIPPED }
		sent += messages
		return messages.map { if (it.to in gone) PushOutcome.DEVICE_NOT_REGISTERED else PushOutcome.OK }
	}
}

/** 풀이 완성 · 문의 답변 이벤트가 커밋 뒤 그 계정의 기기에만 푸시를 보낸다(결정 042 의 4 · 5). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = [DEV_AUTH_ON])
class PushEventsIntegrationTest {

	@TestConfiguration
	class FakeExpo {
		@Bean
		@Primary
		fun recordingExpoPush() = RecordingExpoPush()
	}

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var events: ApplicationEventPublisher

	@Autowired
	private lateinit var tx: TransactionTemplate

	@Autowired
	private lateinit var expo: RecordingExpoPush

	private lateinit var me: UUID
	private lateinit var other: UUID

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		expo.sent.clear()
		expo.gone.clear()
		me = account("푸시나", "ExponentPushToken[me-phone]", "ExponentPushToken[me-tablet]")
		other = account("다른이", "ExponentPushToken[other-phone]")
	}

	/** 푸시 수신 동의를 한 계정(동의가 꺼지면 어떤 푸시도 가지 않는다 — 소유자 결정 2026-11-20, 캔버스 v185). */
	private fun account(nickname: String?, vararg tokens: String): UUID {
		val id = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id, nickname, push_consented_at) values (?, 'TEST', ?, ?, now())", id, id.toString(), nickname)
		for (token in tokens) jdbc.update("insert into push_devices (expo_token, account_id, platform) values (?, ?, 'IOS')", token, id)
		return id
	}

	/** 푸시는 일꾼 스레드에서 간다. [count] 건이 모일 때까지 기다린다(5초까지). */
	private fun awaitSent(count: Int): List<PushMessage> {
		val until = System.currentTimeMillis() + 5_000
		while (expo.sent.size < count && System.currentTimeMillis() < until) Thread.sleep(20)
		Thread.sleep(100) // 더 오지 않는지 조금 더 본다
		return expo.sent.toList()
	}

	@Test
	fun `나 탭 풀이가 완성되면 그 계정의 기기 모두에 문구표대로 보낸다`() {
		events.publishEvent(MeReportDone(me))

		val sent = awaitSent(2)
		assertEquals(setOf("ExponentPushToken[me-phone]", "ExponentPushToken[me-tablet]"), sent.map { it.to }.toSet())
		assertEquals(setOf("자세한 풀이가 다 됐어요"), sent.map { it.title }.toSet())
		assertEquals(setOf("성격 · 연애 · 관계 · 일 · 감정을 읽어 보세요."), sent.map { it.body }.toSet())
		assertEquals(setOf(mapOf("type" to "ME_REPORT_DONE")), sent.map { it.data }.toSet())
	}

	@Test
	fun `트랜잭션 안에서 낸 이벤트는 커밋된 뒤에만 보내고 되돌리면 보내지 않는다`() {
		tx.executeWithoutResult { status ->
			events.publishEvent(MeReportDone(me))
			status.setRollbackOnly()
		}
		assertEquals(0, awaitSent(1).size, "되돌린 일에는 보내지 않는다")

		tx.executeWithoutResult { events.publishEvent(MeReportDone(other)) }
		assertEquals(listOf("ExponentPushToken[other-phone]"), awaitSent(1).map { it.to })
	}

	@Test
	fun `문의 답이 오면 답변 첫 줄과 문의 id 를 보낸다`() {
		val inquiry = UUID.randomUUID()
		jdbc.update(
			"insert into inquiries (id, owner_id, category, body, answer_body, answered_at) values (?, ?, 'BUG', ?, ?, now())",
			inquiry, me, "앱이 멈춰요 열 글자 넘게", "\n  안녕하세요, 확인해 보니 다음 판에서 고쳐집니다.  \n둘째 줄은 넣지 않아요",
		)

		events.publishEvent(InquiryAnswered(me, inquiry))

		val sent = awaitSent(2)
		assertEquals(setOf("문의에 답변이 왔어요"), sent.map { it.title }.toSet())
		assertEquals(setOf("안녕하세요, 확인해 보니 다음 판에서 고쳐집니다."), sent.map { it.body }.toSet())
		assertEquals(setOf(mapOf("type" to "INQUIRY_ANSWERED", "inquiryId" to inquiry.toString())), sent.map { it.data }.toSet())
	}

	@Test
	fun `남의 문의 · 없는 답이면 보내지 않는다`() {
		val inquiry = UUID.randomUUID()
		jdbc.update("insert into inquiries (id, owner_id, category, body) values (?, ?, 'BUG', '답이 아직 없는 문의 글')", inquiry, me)

		events.publishEvent(InquiryAnswered(me, inquiry))
		events.publishEvent(InquiryAnswered(other, inquiry))

		assertEquals(0, awaitSent(1).size)
	}

	@Test
	fun `DeviceNotRegistered 인 토큰은 지우고 나머지는 남긴다`() {
		expo.gone += "ExponentPushToken[me-tablet]"

		events.publishEvent(MeReportDone(me))
		awaitSent(2)

		// 지우기는 Expo 의 답을 받은 뒤 일꾼 스레드에서 한다.
		fun left() = jdbc.queryForList("select expo_token from push_devices where account_id = ?", String::class.java, me)
		val until = System.currentTimeMillis() + 5_000
		while (left().size > 1 && System.currentTimeMillis() < until) Thread.sleep(20)
		assertEquals(listOf("ExponentPushToken[me-phone]"), left())
	}

	@Test
	fun `별명을 지으면 닉네임을 넣어 보내고 시기 풀이가 완성되면 문구표대로 보낸다`() {
		events.publishEvent(ReadingCreated(other))
		events.publishEvent(TimingReportDone(other))

		val sent = awaitSent(2)
		assertEquals(
			setOf(
				Triple("별명을 지었어요", "다른이 님의 별명이 기다리고 있어요", mapOf("type" to "READING_DONE")),
				Triple("시기 풀이가 다 됐어요", "지금 나의 시기와 올해의 흐름을 볼 수 있어요.", mapOf("type" to "TIMING_REPORT_DONE")),
			),
			sent.map { Triple(it.title, it.body, it.data) }.toSet(),
		)
	}

	@Test
	fun `푸시 수신 동의를 끄면 별명 · 풀이 · 문의 답변 푸시를 모두 보내지 않는다`() {
		jdbc.update("update accounts set push_consented_at = null where id = ?", me)
		val inquiry = UUID.randomUUID()
		jdbc.update("insert into inquiries (id, owner_id, category, body, answer_body, answered_at) values (?, ?, 'BUG', '앱이 멈춰요 열 글자 넘게', '답', now())", inquiry, me)

		events.publishEvent(ReadingCreated(me))
		events.publishEvent(MeReportDone(me))
		events.publishEvent(TimingReportDone(me))
		events.publishEvent(InquiryAnswered(me, inquiry))

		assertEquals(0, awaitSent(1).size)
	}

	@Test
	fun `정지된 계정에는 보내지 않는다`() {
		jdbc.update("update accounts set suspended_at = now() where id = ?", me)

		events.publishEvent(MeReportDone(me))
		events.publishEvent(ReadingCreated(me))

		assertEquals(0, awaitSent(1).size)
	}

	@Test
	fun `기기가 없으면 Expo 를 부르지 않는다`() {
		val lonely = account("혼자")

		events.publishEvent(MeReportDone(lonely))

		assertEquals(0, awaitSent(1).size)
	}
}
