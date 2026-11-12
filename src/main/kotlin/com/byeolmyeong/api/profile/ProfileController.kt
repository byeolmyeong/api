package com.byeolmyeong.api.profile

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.birth.BirthProfile
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.BirthRequest
import com.byeolmyeong.api.web.BirthRequestParser
import com.byeolmyeong.api.web.NotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

/** `GET /profile` · `PUT /profile` 의 응답. 요청과 같은 모양이다. `time` 은 모르면 null 로 보내고, 그때는 `placeId` 도 null 이다. */
data class ProfileResponse(val date: String, val time: String?, val placeId: Int?)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 내 출생 정보. 누구의 것인지는 세션에서만 얻는다(요청에 소유자를 받지 않는다). */
@RestController
class ProfileController(
	private val owner: CurrentOwner,
	private val profiles: BirthProfileService,
	private val parser: BirthRequestParser,
	private val events: ApplicationEventPublisher,
) {

	@GetMapping("/profile")
	fun get(): ProfileResponse = profiles.find(owner.id())?.toResponse() ?: throw NotFoundException("NO_PROFILE")

	@PutMapping("/profile")
	fun put(@RequestBody request: BirthRequest): ProfileResponse {
		val birth = parser.parse(request)
		val profile = BirthProfile(birth.date, birth.time, birth.place?.id)
		val existed = profiles.find(owner.id()) != null
		profiles.save(owner.id(), profile)
		// 감사 기록에는 처음 저장인지 고친 것인지만 남긴다(값은 남기지 않는다. 결정 015).
		events.publishEvent(AuditEvent(AuditAction.PROFILE_SAVED, AuditActor.user(owner.id()), detail = mapOf("change" to if (existed) "CHANGED" else "CREATED")))
		return profile.toResponse()
	}

	private fun BirthProfile.toResponse() = ProfileResponse(date.toString(), time?.format(TIME_FORMAT), placeId)
}
