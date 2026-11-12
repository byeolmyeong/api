package com.byeolmyeong.api.synastry

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.chart.Relation
import com.byeolmyeong.api.generation.GenerationException
import com.byeolmyeong.api.web.BadRequestException
import com.byeolmyeong.api.web.BirthRequest
import com.byeolmyeong.api.web.BirthRequestParser
import com.byeolmyeong.api.web.ErrorResponse
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `POST /synastry/direct` 의 본문. 값 검증은 컨트롤러가 하므로 모두 느슨한 타입으로 받는다. */
data class DirectSynastryRequest(val name: String?, val relation: String?, val date: String?, val time: String?, val placeId: Int?)

/** 궁합(직접 입력). 누구의 것인지는 세션에서, 내 출생 정보는 저장된 프로필에서 얻는다. 상대의 출생 정보는 본문으로 받아 계산에만 쓴다. */
@RestController
class SynastryController(
	private val owner: CurrentOwner,
	private val synastries: SynastryService,
	private val parser: BirthRequestParser,
) {

	@PostMapping("/synastry/direct")
	fun direct(@RequestBody request: DirectSynastryRequest): Synastry {
		val name = request.name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH } ?: throw BadRequestException("INVALID_NAME")
		val relation = relation(request.relation)
		return synastries.direct(owner.id(), name, relation, parser.chartInput(BirthRequest(request.date, request.time, request.placeId)))
	}

	@GetMapping("/synastry")
	fun list(): List<Synastry> = synastries.list(owner.id())

	@GetMapping("/synastry/ranking")
	fun ranking(@RequestParam(required = false) relation: String?): List<RankingEntry> =
		synastries.ranking(owner.id(), relation(relation))

	@GetMapping("/synastry/{id}")
	fun get(@PathVariable id: String): Synastry = synastries.get(owner.id(), id(id))

	/** 궁합을 지운다(리포트·그 궁합의 방향성 제시도 함께). 남의 것·없는 것은 404. */
	@DeleteMapping("/synastry/{id}")
	fun delete(@PathVariable id: String): ResponseEntity<Void> {
		synastries.delete(owner.id(), id(id))
		return ResponseEntity.noContent().build()
	}

	/** 글을 기다리지 않고 202 로 답한다. 본문은 상대의 출생 정보다(저장돼 있지 않아 다시 받는다). 이미 완성됐거나 쓰는 중이면 본문을 읽지 않는다. */
	@PostMapping("/synastry/{id}/report")
	fun requestReport(@PathVariable id: String, @RequestBody(required = false) request: BirthRequest?): ResponseEntity<SynastryReportResponse> {
		val status = synastries.requestReport(owner.id(), id(id)) { parser.chartInput(request ?: throw BadRequestException("INVALID_REQUEST")) }
		return ResponseEntity.accepted().body(SynastryReportResponse(status))
	}

	@GetMapping("/synastry/{id}/report")
	fun report(@PathVariable id: String): SynastryReportResponse = synastries.report(owner.id(), id(id))

	@ExceptionHandler(ProfileChangedException::class)
	fun profileChanged(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(409).body(ErrorResponse("PROFILE_CHANGED"))

	@ExceptionHandler(GenerationException::class)
	fun generationFailed(): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(502).body(ErrorResponse("GENERATION_FAILED"))

	private fun relation(value: String?): Relation =
		Relation.entries.firstOrNull { it.name == value } ?: throw BadRequestException("INVALID_RELATION")

	// id 모양이 아닌 값도 "없는 것" 이다.
	private fun id(value: String): UUID = try {
		UUID.fromString(value)
	} catch (e: IllegalArgumentException) {
		throw NotFoundException("NOT_FOUND")
	}
}
