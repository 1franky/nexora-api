package com.nexora.api.sat.domain

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Sincronización incremental automática (plan-integracion-sat.md, sección
 * 6, paso 7) — corre para toda e.firma ACTIVA una vez al día (cron
 * configurable, `nexora.sat.sync-cron`), independiente de si el usuario
 * abre la app o no.
 */
@Component
class SatSyncScheduler(
    private val certificateRepository: SatCertificateRepository,
    private val syncService: SatSyncService,
) {

    private val log = LoggerFactory.getLogger(SatSyncScheduler::class.java)

    @Scheduled(cron = "\${nexora.sat.sync-cron:0 0 3 * * *}")
    fun syncAllActiveCertificates() {
        val activos = certificateRepository.findAllByStatus(SatCertificateStatus.ACTIVO)
        log.info("Sincronización SAT diaria: {} e.firma(s) activa(s).", activos.size)
        activos.forEach { certificate -> syncService.syncIncrementalAsync(requireNotNull(certificate.id)) }
    }
}
