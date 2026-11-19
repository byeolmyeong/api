package com.byeolmyeong.api.auth

import com.byeolmyeong.api.web.BadRequestException

/** 프로필 그림 번호(결정 026): 앱의 브랜드 그림 avatar-1~8. DB 의 check(V21)와 같은 범위다. */
private val AVATARS = 1..8

/** 1~8 이면 그 번호, 없거나 범위 밖이면 400 `INVALID_AVATAR`. */
fun requireAvatar(raw: Int?): Int = raw?.takeIf { it in AVATARS } ?: throw BadRequestException("INVALID_AVATAR")
