package com.byeolmyeong.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

// 주기 작업: 쓰는 중인 리포트가 살아 있다고 적는 일(report·synastry 의 heartbeat).
@EnableScheduling
@SpringBootApplication
class ByeolmyeongApiApplication

fun main(args: Array<String>) {
	// 되돌리기 연습(배포 계획 2026-11-18 태스크 8): 켜자마자 끝난다. 이 브랜치는 연습 뒤 지운다.
	kotlin.system.exitProcess(1)
}
