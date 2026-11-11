package com.byeolmyeong.api.chart

import java.time.Instant

// 하우스·교점·릴리스·행운점 기준값.
// 출처: Swiss Ephemeris 2.10.03 (파이썬 패키지 pyswisseph, Moshier 모드)으로 2026-11-10 에 계산했다.
//   houses(jd_ut, 위도, 경도, 'P') → 플라시더스 경계·상승점·중천·버텍스(ascmc[3], 2026-11-11 에 같은 방법으로 추가)
//   calc_ut(jd_ut, MEAN_NODE / MEAN_APOG) → 평균 교점·평균 릴리스(평균 원지점), 날짜 기준 황경
//   행운점 = 낮(태양이 지평선 위) 상승점+달-태양, 밤 상승점+태양-달 을 같은 출력값으로 계산
// Swiss Ephemeris 는 개발 중 기준값을 구하는 데만 썼고 이 저장소와 빌드에는 들어 있지 않다.
data class ChartReference(
	val name: String,
	val utc: Instant,
	val latitude: Double,
	val longitude: Double,
	val ascendant: Double,
	val midheaven: Double,
	val vertex: Double,
	val cusps: List<Double>,
	val meanNode: Double,
	val meanLilith: Double,
	val fortune: Double,
	val sunHouse: Int,
)

val SEOUL_1990 = ChartReference(
	name = "서울 1990-03-15 14:30 KST",
	utc = Instant.parse("1990-03-15T05:30:00Z"),
	latitude = 37.5665,
	longitude = 126.9780,
	ascendant = 125.053774,
	midheaven = 23.813022,
	vertex = 259.434152,
	cusps = listOf(
		125.053774, 146.462870, 172.200566, 203.813022, 239.682733, 274.571309,
		305.053774, 326.462870, 352.200566, 23.813022, 59.682733, 94.571309,
	),
	meanNode = 314.583808,
	meanLilith = 224.613362,
	fortune = 347.202722, // 낮 차트(태양 9하우스): 상승점 + 달 - 태양
	sunHouse = 9,
)

val BUSAN_1985 = ChartReference(
	name = "부산 1985-11-02 06:15 KST",
	utc = Instant.parse("1985-11-01T21:15:00Z"),
	latitude = 35.1796,
	longitude = 129.0756,
	ascendant = 212.290802,
	midheaven = 126.535344,
	vertex = 76.626717,
	cusps = listOf(
		212.290802, 240.958817, 272.752094, 306.535344, 339.260260, 8.074942,
		32.290802, 60.958817, 92.752094, 126.535344, 159.260260, 188.074942,
	),
	meanNode = 39.003558,
	meanLilith = 46.959073,
	fortune = 347.318924, // 밤 차트(태양 1하우스): 상승점 + 태양 - 달
	sunHouse = 1,
)

val CHART_REFERENCES = listOf(SEOUL_1990, BUSAN_1985)

fun angleError(actual: Double, expected: Double): Double = kotlin.math.abs(signedDifference(actual, expected))
