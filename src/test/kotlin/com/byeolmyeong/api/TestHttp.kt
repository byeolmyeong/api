package com.byeolmyeong.api

import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// 통합 테스트가 함께 쓰는 HTTP 도우미.

/** 개발용 로그인을 켜는 설정. 세션이 필요한 엔드포인트를 부르는 테스트 클래스에 준다. */
const val DEV_AUTH_ON = "byeolmyeong.auth.dev-enabled=true"

fun http(
	port: Int,
	method: String,
	path: String,
	body: String? = null,
	token: String? = null,
	vararg headers: String,
): HttpResponse<String> {
	val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
		.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
	if (body != null) request.header("Content-Type", "application/json")
	if (token != null) request.header("Authorization", "Bearer $token")
	if (headers.isNotEmpty()) request.headers(*headers)
	return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
}

/** 개발용 로그인으로 세션 토큰을 받는다(`DEV_AUTH_ON` 이 켜진 서버에서만). */
fun devToken(port: Int): String {
	val response = http(port, "POST", "/auth/dev")
	check(response.statusCode() == 200) { "개발용 로그인이 꺼져 있다: ${response.statusCode()}" }
	return JsonMapper().readTree(response.body())["token"].asString()
}

/** 테스트용 닉네임(결정 025: 한글 · 영문 2~10자, 중복 안 됨). 부를 때마다 다르다. */
fun freshNickname(): String = "별" + (1..6).map { ('a'..'z').random() }.joinToString("")
