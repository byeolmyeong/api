package com.byeolmyeong.api.profile

import com.byeolmyeong.api.auth.CurrentOwner
import com.byeolmyeong.api.auth.UnderAgeException
import com.byeolmyeong.api.auth.isAtLeast14
import com.byeolmyeong.api.auth.koreaToday
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
		// 가입 뒤에도 만 14세 미만 날짜로는 바꿀 수 없다(가입과 같은 판정, 소유자 결정 2026-11-19 · 결정 021). 있던 것은 그대로다.
		if (!isAtLeast14(birth.date, koreaToday())) throw UnderAgeException()
		val profile = BirthProfile(birth.date, birth.time, birth.place?.id)
		val before = profiles.find(owner.id())
		val existed = before != null
		profiles.save(owner.id(), profile)
		// 바뀌었으면 옛 출생 정보로 만든 저장분을 각 기능이 지운다(결정 041). 처음 저장 · 같은 값은 그대로.
		if (before != null && before != profile) events.publishEvent(BirthProfileChanged(owner.id(), checkNotNull(profiles.chartInput(owner.id())).hash))
		// 감사 기록에는 처음 저장인지 고친 것인지만 남긴다(값은 남기지 않는다. 결정 015).
		events.publishEvent(AuditEvent(AuditAction.PROFILE_SAVED, AuditActor.user(owner.id()), detail = mapOf("change" to if (existed) "CHANGED" else "CREATED")))
		return profile.toResponse()
	}

	private fun BirthProfile.toResponse() = ProfileResponse(date.toString(), time?.format(TIME_FORMAT), placeId)
}
