package com.byeolmyeong.api.share

import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** 공유 식별자의 무작위 바이트 수(128비트). */
private const val SHARE_ID_BYTES = 16

/**
 * 공유 카드(결정 012). 공유하는 순간의 공개 글만 `shares` 에 저장하고, 공개 조회는 그 JSON 을 그대로 준다.
 * 종류마다의 함수는 카드만 만든다 — 만들기([create])는 저장까지 하고, 미리 받기(결정 051)는 카드만 돌려준다.
 * 카드의 글은 그 글을 가진 기능에게 이벤트로 묻는다 — 소유자의 것이 아니면 답이 오지 않고, 그때는 404 다.
 * 이름·공유 식별자·소유자를 로그에 남기지 않는다(카드 종류만).
 */
@Service
class ShareService(
	private val repository: ShareRepository,
	private val events: ApplicationEventPublisher,
	private val mapper: JsonMapper,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()

	fun reading(ownerId: UUID): ShareCard {
		val asked = ReadingCardRequested(ownerId).also(events::publishEvent)
		return ReadingCard(asked.headline ?: nothing(), asked.keywords ?: nothing())
	}

	fun score(ownerId: UUID, synastryId: UUID, myName: String): ShareCard {
		val asked = ScoreCardRequested(ownerId, synastryId).also(events::publishEvent)
		val card = ScoreCard(
			names = listOf(myName, asked.partnerName ?: nothing()),
			score = asked.score ?: nothing(),
			scoreLabel = asked.scoreLabel,
			headline = asked.headline ?: nothing(),
		)
		return card
	}

	/** 앞의 [RANKING_TOP] 명만 싣는다. [showNames] 가 꺼져 있으면 이름을 이니셜로 가려서 저장한다(원래 이름은 표에 남지 않는다). */
	fun ranking(ownerId: UUID, relation: Relation, showNames: Boolean): ShareCard {
		val asked = RankingCardRequested(ownerId, relation).also(events::publishEvent)
		val entries = asked.entries.orEmpty().take(RANKING_TOP)
			.map { (name, score) -> RankingCardEntry(if (showNames) name else initialsOf(name), score) }
		if (entries.isEmpty()) nothing()
		return RankingCard(relation, entries)
	}

	/** 날짜는 여기서 해와 계절로 줄인다. 날짜 자체는 저장하지 않는다. */
	fun nextTurn(ownerId: UUID): ShareCard {
		val asked = NextTurnCardRequested(ownerId).also(events::publishEvent)
		val date = asked.date ?: nothing()
		return NextTurnCard(date.year, seasonOf(date), asked.theme ?: nothing())
	}

	// ── 공유 v2(결정 036): 닉네임은 계정에서, 글은 그 글을 가진 기능에게 묻는다 ──

	fun nickname(ownerId: UUID): ShareCard {
		val asked = NicknameCardRequested(ownerId).also(events::publishEvent)
		return NicknameCard(repository.nicknameOf(ownerId), asked.sunSign ?: nothing(), asked.headline ?: nothing())
	}

	fun meArea(ownerId: UUID, area: String): ShareCard {
		val asked = MeAreaCardRequested(ownerId, area).also(events::publishEvent)
		return MeAreaCard(repository.nicknameOf(ownerId), area, asked.title ?: nothing())
	}

	/** 만들지 않은 날이면 답이 없어 404. */
	fun daySky(ownerId: UUID, date: LocalDate): ShareCard {
		val asked = DaySkyCardRequested(ownerId, date).also(events::publishEvent)
		return DaySkyCard(repository.nicknameOf(ownerId), date.toString(), asked.title ?: nothing())
	}

	fun nextBig(ownerId: UUID): ShareCard {
		val asked = NextBigCardRequested(ownerId).also(events::publishEvent)
		return NextBigCard(repository.nicknameOf(ownerId), asked.year ?: nothing(), asked.age ?: nothing(), asked.title ?: nothing())
	}

	/** 공개 조회. 저장된 카드의 JSON 이고, 없으면 404. */
	fun shared(shareId: String): String = repository.findCard(shareId) ?: nothing()

	/** 남의 것과 없는 것은 같은 404. */
	fun delete(ownerId: UUID, shareId: String) {
		if (!repository.delete(ownerId, shareId)) nothing()
		log.info("event=share_deleted")
		// 감사 기록에 공유 식별자는 남기지 않는다(그 값은 카드를 여는 열쇠다. 결정 015).
		events.publishEvent(AuditEvent(AuditAction.SHARE_DELETED, AuditActor.user(ownerId)))
	}

	// 같은 소유자의 같은 내용은 한 행이다. 동시에 만들어도 먼저 들어간 행의 식별자를 둘 다 받는다.
	fun create(ownerId: UUID, card: ShareCard): Share {
		val json = mapper.writeValueAsString(card)
		val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.toByteArray()))
		val created = repository.insertIfAbsent(newShareId(), ownerId, card.kind, json, hash)
		val shareId = checkNotNull(repository.findShareId(ownerId, hash)) { "방금 저장한 공유가 없다" }
		log.info("event=share_created kind={}", card.kind)
		// 이미 있던 공유를 다시 받은 요청은 감사 기록에 남기지 않는다(새로 만든 것만).
		if (created) events.publishEvent(AuditEvent(AuditAction.SHARE_CREATED, AuditActor.user(ownerId), detail = mapOf("kind" to card.kind.name)))
		return Share(shareId, card)
	}

	private fun newShareId(): String =
		Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SHARE_ID_BYTES).also(random::nextBytes))

	private fun nothing(): Nothing = throw NotFoundException("NOT_FOUND")
}
