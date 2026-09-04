package com.nexora.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Parámetros de la integración SAT (B11, ver plan-integracion-sat.md).
 * `NEXORA_SAT_ENCRYPTION_KEY` se resuelve aparte, directo en
 * [com.nexora.api.sat.domain.SatCryptoService] — es la clave maestra de
 * cifrado, no un parámetro de comportamiento.
 */
@ConfigurationProperties(prefix = "nexora.sat")
data class SatProperties(
    /** Meses hacia atrás que trae la primera sincronización automática al conectar la e.firma — no un límite de lo consultable, ver plan sección 6.1 (el usuario puede pedir cualquier rango con sync manual). */
    val initialSyncMonths: Long = 3,
    /** Cron de la sincronización incremental automática — default diaria a las 3am. */
    val syncCron: String = "0 0 3 * * *",
    val pollIntervalSeconds: Long = 30,
    val maxPollAttempts: Int = 20,
)

@Configuration
@EnableConfigurationProperties(SatProperties::class)
class SatConfig
