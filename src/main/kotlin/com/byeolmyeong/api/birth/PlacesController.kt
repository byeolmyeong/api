package com.byeolmyeong.api.birth

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class PlacesController(private val places: Places) {

	/** 검색어가 없으면 전체 목록(고르는 화면용), 있으면 검색 결과. */
	@GetMapping("/places")
	fun list(@RequestParam(required = false) q: String?): List<Place> = if (q == null) places.all else places.search(q)
}
