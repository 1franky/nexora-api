package com.nexora.api.notification.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Eventos de notificación (plan.md, sección 9). Este B6 solo genera
 * PAYMENT_DUE, PAYMENT_DUE_SOON e INSTALLMENT_DUE — los demás quedan
 * declarados para no migrar el enum otra vez, pero todavía no se disparan:
 *
 * - PAYMENT_OVERDUE requeriría llevar el estado de pago por ciclo/estado de
 *   cuenta (qué ciclo ya se pagó y cuál no); hoy solo sabemos la *próxima*
 *   fecha límite (siempre futura por construcción, ver BillingCycleCalculator),
 *   no si la anterior quedó sin pagar.
 * - BUDGET_EXCEEDED necesita la entidad Budget (plan.md, sección 31 —
 *   explícitamente posterior al MVP, todavía no existe).
 * - UNUSUAL_EXPENSE necesita detección de anomalías/gastos recurrentes
 *   (sección 31, también posterior al MVP).
 *
 * SAT_SYNC_COMPLETED/SAT_SYNC_FAILED (B11) sí se generan, pero no desde
 * [NotificationService.generateForUser] — [SatSyncService] las crea
 * directamente al terminar una sincronización, porque son un evento
 * puntual disparado por un job async, no una regla recurrente evaluada
 * contra "hoy" como el resto de este enum.
 */
enum class NotificationType {
    PAYMENT_DUE,
    PAYMENT_DUE_SOON,
    PAYMENT_OVERDUE,
    INSTALLMENT_DUE,
    BUDGET_EXCEEDED,
    UNUSUAL_EXPENSE,
    SAT_SYNC_COMPLETED,
    SAT_SYNC_FAILED,
}

enum class NotificationStatus {
    UNREAD,
    READ,
}

/**
 * Notificación en la app (lo que hoy sirve como "notificaciones web": la
 * Web/Android las consumen vía GET /api/v1/notifications). Email y push
 * (Firebase Cloud Messaging) son canales de entrega adicionales que se
 * agregarán después sobre este mismo modelo (plan.md, sección 9: "la
 * plataforma podrá evolucionar..."), no implementados en este B6 por
 * requerir credenciales/infra externa (SMTP, proyecto de Firebase) que
 * todavía no se han definido.
 */
@Entity
@Table(name = "notifications")
class Notification(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var type: NotificationType,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false, length = 1000)
    var message: String,

    /** id de la tarjeta o de la cuota a la que se refiere, según [type]. */
    @Column(name = "related_entity_id")
    var relatedEntityId: UUID? = null,

    /**
     * Fecha de referencia ("hoy") con la que se generó — no [BaseEntity.createdAt]
     * (ese es el timestamp real de auditoría). Evita generar la misma
     * notificación dos veces el mismo día lógico, incluso si alguien llama a
     * generateForUser con una fecha explícita distinta a la real.
     */
    @Column(name = "for_date", nullable = false)
    var forDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: NotificationStatus = NotificationStatus.UNREAD,

    @Column(name = "read_at")
    var readAt: Instant? = null,

) : BaseEntity()
