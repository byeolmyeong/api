package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.birth.BirthProfile
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.BirthRequest
import com.byeolmyeong.api.web.BirthRequestParser
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `POST /synastry` 의 본문. 값 검증은 컨트롤러가 하므로 모두 느슨한 타입으로 받는다. */
data class SynastryRequest(val name: String?, val relation: String?, val date: String?, val time: String?, val placeId: Int?)

/** `POST /synastry/delete` 의 본문. 값 검증은 컨트롤러가 한다. */
data class SynastryDeleteRequest(val ids: List<String?>?)

/** 궁합(직접 입력). 누구의 것인지는 세션에서, 내 출생 정보는 저장된 프로필에서 얻는다. 상대의 출생 정보는 본문으로 받아 그 궁합에 저장한다(결정 053 의 2). */
@RestController
class SynastryController(
	private val owner: CurrentOwner,
	private val synastries: SynastryService,
	private val parser: BirthRequestParser,
) {

	/** 글을 기다리지 않고 202 로 답한다(결정 053 의 3). 앱은 `GET /synastry/{id}` 를 몇 초 간격으로 다시 부른다. */
	@PostMapping("/synastry")
	fun create(@RequestBody request: SynastryRequest): ResponseEntity<SynastryStarted> {
		val name = request.name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH } ?: throw BadRequestException("INVALID_NAME")
		val relation = inputRelation(request.relation)
		val partner = parser.parse(BirthRequest(request.date, request.time, request.placeId))
		return ResponseEntity.accepted().body(synastries.create(owner.id(), name, relation, BirthProfile(partner.date, partner.time, partner.place?.id)))
	}

	@GetMapping("/synastry")
	fun list(): List<Synastry> = synastries.list(owner.id())

	@GetMapping("/synastry/ranking")
	fun ranking(@RequestParam(required = false) relation: String?): List<RankingEntry> =
		synastries.ranking(owner.id(), relation(relation))

	@GetMapping("/synastry/{id}")
	fun get(@PathVariable id: String): Synastry = synastries.get(owner.id(), id(id))

	/** 실패한 궁합을 같은 행에서 다시 쓴다. 다시 쓸 수 없는 상태면 그 상태를 준다(언제나 202). */
	@PostMapping("/synastry/{id}/retry")
	fun retry(@PathVariable id: String): ResponseEntity<SynastryStarted> =
		ResponseEntity.accepted().body(synastries.retry(owner.id(), id(id)))

	/** 궁합을 지운다(저장한 상대 출생 정보 · 글 · 그 궁합의 방향성 제시도 함께). 남의 것·없는 것은 404. */
	@DeleteMapping("/synastry/{id}")
	fun delete(@PathVariable id: String): ResponseEntity<Void> {
		synastries.delete(owner.id(), id(id))
		return ResponseEntity.noContent().build()
	}

	/**
	 * 여러 명을 한 번에 지운다(결정 053 의 7). 남의 것 · 없는 것 · id 모양이 아닌 값이 하나라도 섞이면 404 이고 아무것도 지우지 않는다.
	 * 목록이 없거나 비었으면 400 `INVALID_REQUEST`. 같은 id 가 여러 번 와도 한 번이다.
	 */
	@PostMapping("/synastry/delete")
	fun deleteMany(@RequestBody(required = false) request: SynastryDeleteRequest?): ResponseEntity<Void> {
		val ids = request?.ids?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("INVALID_REQUEST")
		synastries.deleteMany(owner.id(), ids.map { id(it ?: throw BadRequestException("INVALID_REQUEST")) }.toSet())
		return ResponseEntity.noContent().build()
	}

	private fun relation(value: String?): Relation =
		Relation.entries.firstOrNull { it.name == value } ?: throw BadRequestException("INVALID_RELATION")

	// 새로 넣는 사이는 연애 · 친구 둘이다(결정 053 의 1). 일 · 가족은 옛 기록을 읽을 때(랭킹)만 받는다.
	private fun inputRelation(value: String?): Relation =
		relation(value).takeIf { it in INPUT_RELATIONS } ?: throw BadRequestException("INVALID_RELATION")

	// id 모양이 아닌 값도 "없는 것" 이다.
	private fun id(value: String): UUID = try {
		UUID.fromString(value)
	} catch (e: IllegalArgumentException) {
		throw NotFoundException("NOT_FOUND")
	}
}
