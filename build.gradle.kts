plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.byeolmyeong"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
	// astronomy-engine Kotlin 판은 JitPack 에만 있다. 다른 의존성이 여기서 풀리지 않게 그룹을 제한한다.
	maven("https://jitpack.io") {
		content { includeGroup("io.github.cosinekitty") }
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("io.github.cosinekitty:astronomy:2.1.19")
	// Claude API 공식 Java SDK. generation.ClaudeTextGenerator 만 쓴다(결정 006).
	implementation("com.anthropic:anthropic-java:3.3.0")
	// 관리자 비밀번호의 bcrypt 해시만 쓴다(admin.AdminPasswords, 결정 014). Spring Security 의 웹 쪽(필터 체인)은 들이지 않는다.
	implementation("org.springframework.security:spring-security-crypto")
	// 소셜 로그인 ID 토큰 확인(JWKS · RS256)과 애플 client_secret(ES256) 만들기(결정 020). Spring Boot 가 버전을 관리하지 않아 정한다(2026-09-17 판).
	implementation("com.nimbusds:nimbus-jose-jwt:10.10")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
