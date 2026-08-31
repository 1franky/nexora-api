plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "com.nexora"
version = "0.0.1-SNAPSHOT"
description = "API central de Nexora - plataforma de finanzas personales"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Igual que en producción, el secreto viene de una variable de entorno;
	// este es un valor fijo solo para que los tests sean deterministas.
	environment("JWT_SECRET", "test-only-secret-not-for-production-0123456789-0123456789")
	// Spring cachea el ApplicationContext entre clases de test con la misma
	// configuración: el RateLimitFilter es el mismo singleton para todo el
	// suite, y decenas de tests registran usuarios repetidamente desde la
	// misma IP simulada. Se sube el límite para que no se dispare entre
	// tests de otros módulos; el comportamiento de 429 en sí se prueba
	// aparte, de forma aislada (RateLimitFilterTests, sin Spring context).
	environment("NEXORA_RATE_LIMIT_MAX_REQUESTS", "100000")
	// Puerto sin nada escuchando: toda llamada de FrankfurterExchangeRateClient falla rápido y
	// determinista (sin red real, sin flakiness), ejercitando el respaldo de ExchangeRateService.
	// Los tests que necesitan una conversión ya resuelta insertan el ExchangeRate directamente.
	environment("NEXORA_EXCHANGE_RATE_API_BASE_URL", "http://127.0.0.1:1")
}
