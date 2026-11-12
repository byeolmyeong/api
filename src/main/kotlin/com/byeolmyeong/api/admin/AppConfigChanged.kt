package com.byeolmyeong.api.admin

/**
 * 관리자가 앱 설정을 고쳤다는 Spring 이벤트(결정 016). `appconfig` 가 듣고 서버 메모리의 캐시를 비운다.
 * 기능 사이에 허용된 이벤트 목록(`ArchitectureTest` 의 `EVENTS`)에 있다.
 */
class AppConfigChanged
