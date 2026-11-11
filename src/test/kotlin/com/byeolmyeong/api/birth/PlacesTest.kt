package com.byeolmyeong.api.birth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesTest {

	private val places = Places()

	@Test
	fun `시군구 목록을 리소스에서 읽는다`() {
		assertEquals(229, places.all.size)
		assertEquals(229, places.all.map { it.id }.toSet().size)
		// 국내 범위 안의 좌표만 있다
		assertTrue(places.all.all { it.lat in 33.0..38.7 && it.lon in 124.5..131.0 })
	}

	@Test
	fun `마포를 찾으면 서울 마포구가 나온다`() {
		val found = places.search("마포")
		assertEquals(listOf("서울특별시 마포구"), found.map { it.name })
		assertEquals(37.55, found[0].lat, 0.05)
		assertEquals(126.91, found[0].lon, 0.05)
	}

	@Test
	fun `검색어를 공백으로 나눈 낱말이 모두 들어 있는 곳을 찾고 결과 수를 제한한다`() {
		assertEquals(listOf("서울특별시 마포구"), places.search(" 서울 마포 ").map { it.name })
		assertEquals(listOf("서울특별시 마포구"), places.search("마포구  서울").map { it.name })
		assertEquals(Places.MAX_RESULTS, places.search("구").size)
		assertTrue(places.search("").isEmpty())
		assertTrue(places.search("   ").isEmpty())
		assertTrue(places.search("없는곳").isEmpty())
	}

	@Test
	fun `식별자로 찾는다`() {
		val mapo = places.search("마포").single()
		assertEquals(mapo, places.find(mapo.id))
		assertNull(places.find(-1))
	}
}
