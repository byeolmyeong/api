package com.byeolmyeong.api.birth

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class PlacesController(private val places: Places) {

	@GetMapping("/places")
	fun search(@RequestParam q: String): List<Place> = places.search(q)
}
