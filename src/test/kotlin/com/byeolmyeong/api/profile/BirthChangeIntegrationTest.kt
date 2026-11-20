package com.byeolmyeong.api.profile

import com.byeolmyeong.api.DEV_AUTH_ON
import com.byeolmyeong.api.TestcontainersConfiguration
import com.byeolmyeong.api.birth.BirthProfileService
import com.byeolmyeong.api.devToken
import com.byeolmyeong.api.http
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val MAPO = 1841277
private const val BIRTH_A = """{"date":"1990-03-15","time":"14:30","placeId":$MAPO}"""
private const val BIRTH_B = """{"date":"1990-03-15","time":null,"placeId":null}"""

/** 출생 정보가 바뀌면 그 계정의 별명 · 자세한 풀이 저장분 가운데 새 차트가 아닌 것을 지운다(결정 041). 공유 · 남의 것은 그대로. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [DEV_AUTH_ON])
class BirthChangeIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@Autowired
	private lateinit var profiles: BirthProfileService

	private lateinit var token: String
	private lateinit var me: UUID
	private lateinit var other: UUID

	@BeforeEach
	fun clean() {
		jdbc.update("delete from accounts")
		token = devToken(port)
		me = jdbc.queryForObject("select id from accounts where provider = 'DEV'", UUID::class.java)!!
		other = UUID.randomUUID()
		jdbc.update("insert into accounts (id, provider, provider_user_id) values (?, 'TEST', 'other')", other)
	}

	private fun put(body: String) = assertEquals(200, http(port, "PUT", "/profile", body, token).statusCode())

	private fun hash(): String = profiles.chartInput(me)!!.hash

	/** 그 계정 · 그 차트의 별명 · 나 탭 풀이 · 시기 풀이 · 공유 한 벌. */
	private fun seed(owner: UUID, chartHash: String) {
		jdbc.update(
			"insert into readings (id, owner_id, kind, chart_hash, body, share_id) values (?, ?, 'SUMMARY_V2', ?, '{}'::jsonb, ?)",
			UUID.randomUUID(), owner, chartHash, UUID.randomUUID().toString(),
		)
		for (kind in listOf("ME", "TIMING")) {
			jdbc.update("insert into detail_reports (id, owner_id, kind, chart_hash, status, body) values (?, ?, ?, ?, 'DONE', '{}'::jsonb)", UUID.randomUUID(), owner, kind, chartHash)
		}
		jdbc.update(
			"insert into shares (share_id, owner_id, kind, card, card_hash) values (?, ?, 'READING', '{}'::jsonb, ?)",
			UUID.randomUUID().toString(), owner, UUID.randomUUID().toString(),
		)
	}

	private fun count(table: String, owner: UUID): Int = jdbc.queryForObject("select count(*) from $table where owner_id = ?", Int::class.java, owner)!!

	private fun counts(owner: UUID) = listOf("readings", "detail_reports", "shares").associateWith { count(it, owner) }

	@Test
	fun `바뀌면 옛 차트의 별명 · 자세한 풀이를 지우고 공유와 남의 것은 둔다`() {
		put(BIRTH_A)
		val old = hash()
		seed(me, old)
		seed(other, old)

		put(BIRTH_B)

		assertNotEquals(old, hash())
		assertEquals(mapOf("readings" to 0, "detail_reports" to 0, "shares" to 1), counts(me))
		assertEquals(mapOf("readings" to 1, "detail_reports" to 2, "shares" to 1), counts(other), "남의 것은 그대로")
	}

	@Test
	fun `같은 값을 다시 보내면 아무것도 지우지 않는다`() {
		put(BIRTH_A)
		seed(me, hash())

		put(BIRTH_A)

		assertEquals(mapOf("readings" to 1, "detail_reports" to 2, "shares" to 1), counts(me))
	}

	@Test
	fun `처음 저장은 아무것도 지우지 않는다`() {
		seed(me, "hash-before-any-profile")

		put(BIRTH_A)

		assertEquals(mapOf("readings" to 1, "detail_reports" to 2, "shares" to 1), counts(me))
	}

	@Test
	fun `새 차트의 행은 남긴다 — 바꾼 직후 새 출생 정보로 시작한 생성`() {
		put(BIRTH_B)
		val b = hash()
		put(BIRTH_A)
		val a = hash()
		seed(me, a)
		seed(me, b) // B 로 바꾸는 사이에 새 차트로 이미 만든 것

		put(BIRTH_B)

		fun left(table: String) = jdbc.queryForList("select distinct chart_hash from $table where owner_id = ?", String::class.java, me)
		assertEquals(listOf(b), left("readings"))
		assertEquals(listOf(b), left("detail_reports"))
	}
}
