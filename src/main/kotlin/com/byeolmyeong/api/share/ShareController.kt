package com.byeolmyeong.api.share

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

private const val MAX_NAME_LENGTH = 20

/** `POST /shares` 의 본문. 값 검증은 컨트롤러가 하므로 느슨한 타입으로 받는다. 종류마다 쓰는 필드가 다르다. */
data class ShareRequest(
	val kind: String?,
	val synastryId: String?,
	val myName: String?,
	val relation: String?,
	val showNames: Boolean?,
	val area: String?,
	val date: String?,
)

// 나 탭 풀이의 영역 이름(결정 029). `report` 의 타입은 쓸 수 없어 이름으로 받는다.
private val ME_AREAS = setOf("PERSONALITY", "LOVE", "RELATIONSHIP", "WORK", "EMOTION")

/**
 * 공유. 만들기·지우기는 세션의 주인만 하고(누구의 것인지는 세션에서만 얻는다),
 * 공개 조회(`/shared/{shareId}`)는 세션 없이 열린다 — 이 컨트롤러에서 [CurrentOwner] 를 쓰지 않는 유일한 길이다.
 */
@RestController
class ShareController(private val owner: CurrentOwner, private val shares: ShareService) {

	@PostMapping("/shares")
	fun create(@RequestBody request: ShareRequest): Share = shares.create(owner.id(), card(request))

	/** 화면에 들어올 때 미리 받는 카드(결정 051). 만들기와 같은 요청 · 같은 400 · 404 이고, 공유 행 · 식별자 · 감사 기록은 없다. */
	@PostMapping("/shares/preview")
	fun preview(@RequestBody request: ShareRequest): SharePreview = SharePreview(card(request))

	private fun card(request: ShareRequest): ShareCard =
		when (ShareKind.entries.firstOrNull { it.name == request.kind } ?: throw BadRequestException("INVALID_KIND")) {
			ShareKind.READING -> shares.reading(owner.id())
			ShareKind.SCORE -> {
				val synastryId = request.synastryId ?: throw BadRequestException("INVALID_REQUEST")
				val myName = request.myName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH } ?: throw BadRequestException("INVALID_NAME")
				shares.score(owner.id(), id(synastryId), myName)
			}
			ShareKind.RANKING -> {
				val relation = Relation.entries.firstOrNull { it.name == request.relation } ?: throw BadRequestException("INVALID_RELATION")
				shares.ranking(owner.id(), relation, showNames = request.showNames == true)
			}
			ShareKind.NEXT_TURN -> shares.nextTurn(owner.id())
			ShareKind.NICKNAME -> shares.nickname(owner.id())
			ShareKind.ME_AREA -> shares.meArea(owner.id(), request.area?.takeIf { it in ME_AREAS } ?: throw BadRequestException("INVALID_AREA"))
			ShareKind.DAY_SKY -> shares.daySky(owner.id(), kstDate(request.date))
			ShareKind.NEXT_BIG -> shares.nextBig(owner.id())
		}

	/** 로그인 없이 열린다. 저장된 카드를 그대로 준다. */
	@GetMapping("/shared/{shareId}")
	fun shared(@PathVariable shareId: String): ResponseEntity<String> =
		ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(shares.shared(shareId))

	@DeleteMapping("/shares/{shareId}")
	fun delete(@PathVariable shareId: String): ResponseEntity<Void> {
		shares.delete(owner.id(), shareId)
		return ResponseEntity.noContent().build()
	}

	// YYYY-MM-DD 그대로만(2026-1-5 · 2026-13-01 은 틀림).
	private fun kstDate(raw: String?): LocalDate =
		raw?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { date -> date.toString() == it } } ?: throw BadRequestException("INVALID_DATE")

	// id 모양이 아닌 값도 "없는 것" 이다.
	private fun id(value: String): UUID = try {
		UUID.fromString(value)
	} catch (e: IllegalArgumentException) {
		throw NotFoundException("NOT_FOUND")
	}
}
