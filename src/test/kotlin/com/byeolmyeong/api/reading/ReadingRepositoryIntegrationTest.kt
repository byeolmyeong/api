package com.byeolmyeong.api.reading

import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 저장 경합의 마지막 방어(리뷰 7). 서버 한 대에서는 진행 중 생성 표시(결정 022)가 겹침을 막지만,
 * 서버가 늘거나 표시를 지난 요청이 겹치면 `on conflict do nothing` 이 먼저 들어간 글 하나만 남긴다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ReadingRepositoryIntegrationTest {

	@Autowired
	private lateinit var repository: ReadingRepository

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private fun draft(headline: String) = ReadingDraft(headline, listOf(ReadingSection("제목", "본문", listOf("태양"))), listOf("장점"), listOf("주의"))

	@Test
	fun `같은 소유자·종류·차트로 두 번 넣으면 먼저 들어간 글 하나만 남는다`() {
		val owner = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'DEV', ?)", owner, owner.toString())

		repository.insertIfAbsent(owner, "SUMMARY", "hash-1", draft("먼저"), "share-first")
		repository.insertIfAbsent(owner, "SUMMARY", "hash-1", draft("나중"), "share-second")

		val saved = repository.find(owner, "SUMMARY", "hash-1")
		assertEquals("먼저", saved?.headline)
		assertEquals("share-first", saved?.shareId)
		assertEquals(1, jdbc.queryForObject("select count(*) from readings where owner_id = ?", Int::class.java, owner))
	}
}
