package com.nexora.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Habilita @Scheduled (ver [com.nexora.api.notification.domain.NotificationScheduler])
 * y @Async — este último para que una sincronización SAT (B11, puede tardar
 * varios minutos por el polling del protocolo de descarga masiva) no
 * bloquee el hilo de la request que la disparó, ver
 * [com.nexora.api.sat.domain.SatSyncService].
 */
@Configuration
@EnableScheduling
@EnableAsync
class SchedulingConfig
