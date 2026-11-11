package com.byeolmyeong.api.birth

import com.byeolmyeong.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlacesApiIntegrationTest {

	@LocalServerPort
	private var port: Int = 0

	private fun get(path: String): HttpResponse<String> = HttpClient.newHttpClient().send(
		HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
		HttpResponse.BodyHandlers.ofString(),
	)

	@Test
	fun `places 가 마포로 서울 마포구를 준다`() {
		val response = get("/places?q=" + URLEncoder.encode("마포", Charsets.UTF_8))
		assertEquals(200, response.statusCode())
		assertEquals("""[{"id":1841277,"name":"서울특별시 마포구","lat":37.55438,"lon":126.90926}]""", response.body())
	}

	@Test
	fun `검색어가 없으면 전체 목록을 준다`() {
		val response = get("/places")
		assertEquals(200, response.statusCode())
		val all = JsonMapper().readTree(response.body())
		assertEquals(229, all.size())
		assertEquals(setOf("id", "name", "lat", "lon"), all[0].propertyNames().toSet())
		// 고르는 화면이 시·도별로 묶을 수 있게 서울의 구 25개가 모두 온다(검색은 20개까지만 준다)
		assertEquals(25, (0 until all.size()).count { all[it]["name"].asString().startsWith("서울특별시 ") })
	}
}
