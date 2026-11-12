package com.byeolmyeong.api.faq

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 앱에 보이는 자주 묻는 질문 한 개. 순서·공개 여부·고친 관리자는 내보내지 않는다. */
data class Faq(val id: UUID, val question: String, val answer: String)

/**
 * 자주 묻는 질문의 공개 조회(결정 014). 세션 없이 읽는다 — 로그인 전 화면에서도 보인다.
 * 글은 관리자가 `/admin/faqs` 로 쓴다(`admin` 패키지). 이 패키지는 `faqs` 테이블을 읽기만 한다.
 */
@RestController
class FaqController(private val faqs: FaqService) {

	/** 공개한 것만 순서대로. 하나도 없으면 빈 배열. 5분 동안 캐시해도 된다. */
	@GetMapping("/faqs")
	fun list(): ResponseEntity<List<Faq>> =
		ResponseEntity.ok().header("Cache-Control", "public, max-age=300").body(faqs.published())
}

@Service
class FaqService(private val repository: FaqRepository) {

	fun published(): List<Faq> = repository.findPublished()
}

@Repository
class FaqRepository(private val jdbc: JdbcTemplate) {

	fun findPublished(): List<Faq> =
		jdbc.query("select id, question, answer from faqs where published order by sort_order, id") { rs, _ ->
			Faq(rs.getObject("id", UUID::class.java), rs.getString("question"), rs.getString("answer"))
		}
}
