package com.byeolmyeong.api.share

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ShareRepository(private val jdbc: JdbcTemplate) {

	/** 같은 소유자의 같은 내용([cardHash])이 이미 있으면 아무것도 하지 않는다. 새로 넣었으면 true. */
	fun insertIfAbsent(shareId: String, ownerId: UUID, kind: ShareKind, cardJson: String, cardHash: String): Boolean =
		jdbc.update(
			"""
			insert into shares (share_id, owner_id, kind, card, card_hash) values (?, ?, ?, ?::jsonb, ?)
			on conflict (owner_id, card_hash) do nothing
			""".trimIndent(),
			shareId, ownerId, kind.name, cardJson, cardHash,
		) == 1

	fun findShareId(ownerId: UUID, cardHash: String): String? =
		jdbc.queryForList("select share_id from shares where owner_id = ? and card_hash = ?", String::class.java, ownerId, cardHash).firstOrNull()

	/** 공개 조회: 저장된 카드의 JSON. 소유자를 가리지 않는다(누구나 연다). */
	fun findCard(shareId: String): String? =
		jdbc.queryForList("select card::text from shares where share_id = ?", String::class.java, shareId).firstOrNull()

	/** 소유자의 공유 하나를 지운다. 남의 것이면 지우지 않고 false. */
	fun delete(ownerId: UUID, shareId: String): Boolean =
		jdbc.update("delete from shares where owner_id = ? and share_id = ?", ownerId, shareId) == 1
}
