package com.byeolmyeong.api.web

/** 자리마다 숫자 9개까지다(Int 를 넘지 않는다). */
private val VERSION = Regex("[0-9]{1,9}(\\.[0-9]{1,9}){0,2}")

/** 앱의 버전(결정 016). 숫자로 견준다: `1.10.0` 이 `1.9.9` 보다 높다. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
	override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
}

/**
 * 버전 글자를 읽는다: 점으로 나눈 1~3개의 0 이상 정수(`"1"`·`"1.2"`·`"1.2.3"`). 빠진 자리는 0 이다.
 * 그 밖의 글자(`"1.2.3.4"`·`"1.2-beta"`·`"v1"`·앞뒤 공백·빈 글)는 읽을 수 없는 값이고 null 이다.
 * 관리자가 넣는 최소 버전과 앱이 보내는 `X-App-Version` 에 같이 쓴다.
 */
fun parseAppVersion(raw: String): AppVersion? {
	if (!VERSION.matches(raw)) return null
	val parts = raw.split('.').map(String::toInt)
	return AppVersion(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
}
