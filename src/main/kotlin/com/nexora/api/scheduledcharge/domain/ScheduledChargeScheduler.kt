package com.nexora.api.scheduledcharge.domain

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Registra los cargos programados del día (B14). Corre a las 00:05 hora de
 * CDMX para que el cargo aparezca desde temprano el día que toca; si el
 * servidor estuvo caído, la siguiente corrida registra los atrasados (ver
 * [ScheduledChargeService.processDue]).
 */
@Component
class ScheduledChargeScheduler(
    private val scheduledChargeService: ScheduledChargeService,
) {

    @Scheduled(cron = "\${nexora.scheduled-charges.cron:0 5 0 * * *}", zone = "America/Mexico_City")
    fun postDueCharges() {
        scheduledChargeService.processDue()
    }
}
