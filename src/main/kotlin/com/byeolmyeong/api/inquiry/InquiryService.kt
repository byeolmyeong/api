package com.byeolmyeong.api.inquiry

import com.byeolmyeong.api.web.AuditAction
import com.byeolmyeong.api.web.AuditActor
import com.byeolmyeong.api.web.AuditEvent
import com.byeolmyeong.api.web.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

private const val HOUR_MILLIS = 3_600_000L

/** 한 계정이 한 시간의 한도를 넘겼다(429 `RATE_LIMITED`). */
class InquiryRateLimitedException : RuntimeException("RATE_LIMITED")

/**
 * 문의를 저장한다. 계정(소유자 id)별로 한 시간에 정해진 수까지만 받는다(서버 메모리의 고정 창, 결정 013).
 * 문의 글과 이메일을 로그에 남기지 않는다(종류 코드만).
 */
@Service
class InquiryService(
	private val repository: InquiryRepository,
	private val events: ApplicationEventPublisher,
	@Value("\${byeolmyeong.rate-limit.inquiries-per-hour}") perHour: Int,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val limiter = RateLimiter(perHour, HOUR_MILLIS, System::currentTimeMillis)

	fun create(ownerId: UUID, inquiry: Inquiry): UUID {
		if (!limiter.tryAcquire(ownerId.toString())) {
			log.warn("event=inquiry_rate_limited")
			throw InquiryRateLimitedException()
		}
		val id = repository.insert(ownerId, inquiry)
		log.info("event=inquiry_created category={}", inquiry.category)
		events.publishEvent(AuditEvent(AuditAction.INQUIRY_SUBMITTED, AuditActor.user(ownerId), "INQUIRY", id.toString(), detail = mapOf("category" to inquiry.category)))
		return id
	}
}
