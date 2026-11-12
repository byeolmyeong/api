package com.byeolmyeong.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// 주기 작업: 쓰는 중인 리포트가 살아 있다고 적는 일(report·synastry 의 heartbeat).
@EnableScheduling
@SpringBootApplication
class ByeolmyeongApiApplication

fun main(args: Array<String>) {
	runApplication<ByeolmyeongApiApplication>(*args)
}
