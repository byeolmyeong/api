package com.byeolmyeong.api.profile

import java.util.UUID

/**
 * 저장된 출생 정보가 바뀌었다는 Spring 이벤트(결정 041). 처음 저장 · 같은 값이면 내지 않는다. [chartHash] 는 **새** 차트 해시이고,
 * 출생 정보를 저장하는 기능(`reading` · `report` · `sky`)이 자기 표에서 이 해시가 아닌 그 계정의 행을 지운다. 출생 정보 값은 싣지 않는다.
 */
data class BirthProfileChanged(val ownerId: UUID, val chartHash: String)
