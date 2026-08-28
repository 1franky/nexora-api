package com.nexora.api.notification.domain

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * "Scheduler" del roadmap (plan.md, sección 27, B6): corre la generación de
 * notificaciones para todos los usuarios una vez al día, para poder
 * empujarlas por push/email más adelante aunque el usuario no abra la app.
 * Mientras tanto, GET /api/v1/notifications también genera al vuelo para
 * el usuario que consulta, así que el usuario nunca ve datos desactualizados
 * aunque este job no haya corrido todavía.
 */
@Component
class NotificationScheduler(
    private val notificationService: NotificationService,
) {

    private val log = LoggerFactory.getLogger(NotificationScheduler::class.java)

    @Scheduled(cron = "0 0 8 * * *")
    fun generateDailyNotifications() {
        log.info("Generando notificaciones diarias (pagos y cuotas por vencer)")
        notificationService.generateForAllUsers()
    }
}
