package com.nexora.api.audit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

/**
 * Eventos financieros explícitos que pide plan.md, sección 13
 * "Auditoría" — TransactionCreated se separó en sus tres variantes
 * (CRUD) para que el tipo de evento ya diga qué pasó sin tener que leer
 * [AuditLog.summary]. InstallmentCreated (una cuota individual) se fusiona
 * con [INSTALLMENT_PLAN_CREATED]: registrar cada una de las N cuotas de un
 * plan como fila aparte sería puro ruido para un log pensado para
 * lectura humana — la creación del plan ya implica la de su calendario
 * completo de cuotas, generado atómicamente en la misma operación.
 */
enum class AuditEventType {
    TRANSACTION_CREATED,
    TRANSACTION_UPDATED,
    TRANSACTION_DELETED,
    CREDIT_CARD_PURCHASE_CREATED,
    INSTALLMENT_PLAN_CREATED,
    PAYMENT_CREATED,

    /** Alta/reemplazo de la e.firma conectada (B11) — el dato más sensible que maneja Nexora, se audita explícitamente. */
    SAT_CERTIFICATE_CONNECTED,

    /** El usuario desconectó su e.firma (o el SAT la rechazó y se marcó ERROR_AUTENTICACION). */
    SAT_CERTIFICATE_REVOKED,
}

/**
 * Registro inmutable de un evento financiero (plan.md, sección 13):
 * quién, qué, sobre qué entidad y cuándo. A diferencia de `created_by` en
 * [com.nexora.api.common.domain.BaseEntity] (que solo dice quién creó
 * *esa fila*), esta tabla es el historial de *acciones* — sobrevive
 * incluso si la entidad afectada se borra después (p.ej. TRANSACTION_DELETED
 * queda como registro aunque la Transaction ya no exista).
 *
 * No extiende BaseEntity a propósito: es un log de solo-escritura (nunca
 * se actualiza una fila ya escrita), así que `updatedAt`/`createdBy` no
 * tienen sentido aquí — [userId] y [occurredAt] ya cubren esa información.
 */
@Entity
@Table(name = "audit_log")
class AuditLog(

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    val eventType: AuditEventType,

    /** Nombre simple de la clase de la entidad afectada (ej. "Transaction", "InstallmentPlan"). */
    @Column(name = "entity_type", nullable = false, length = 40)
    val entityType: String,

    @Column(name = "entity_id", nullable = false)
    val entityId: UUID,

    /** Descripción legible del evento (ej. "Gasto de $500.00 en Débito Principal"), para no tener que reconstruirla leyendo la entidad. */
    @Column(nullable = false, columnDefinition = "TEXT")
    val summary: String,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.now(),

) {
    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    var id: UUID? = null
        protected set
}
