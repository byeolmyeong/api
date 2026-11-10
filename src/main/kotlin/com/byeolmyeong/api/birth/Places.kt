package com.byeolmyeong.api.birth

import org.springframework.stereotype.Component

data class Place(val id: Int, val name: String, val lat: Double, val lon: Double)

/** 국내 시·군·구 목록. 출처와 만든 방법은 `src/main/resources/places/README.md`. */
@Component
class Places {
	val all: List<Place> = load()
	private val byId: Map<Int, Place> = all.associateBy { it.id }

	fun find(id: Int): Place? = byId[id]

	/** 검색어를 공백으로 나눈 낱말이 이름에 모두 들어 있는 곳. 목록 순서대로 [MAX_RESULTS] 개까지. */
	fun search(query: String): List<Place> {
		val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
		if (words.isEmpty()) return emptyList()
		return all.asSequence().filter { place -> words.all { place.name.contains(it) } }.take(MAX_RESULTS).toList()
	}

	private fun load(): List<Place> {
		val stream = checkNotNull(javaClass.getResourceAsStream(RESOURCE)) { "$RESOURCE 가 없다" }
		return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
			lines.drop(1).filter { it.isNotBlank() }.map { line ->
				val (id, name, lat, lon) = line.split(",")
				Place(id.toInt(), name, lat.toDouble(), lon.toDouble())
			}.toList()
		}
	}

	companion object {
		const val MAX_RESULTS = 20
		private const val RESOURCE = "/places/kr-sigungu.csv"
		private val WHITESPACE = Regex("\\s+")
	}
}
