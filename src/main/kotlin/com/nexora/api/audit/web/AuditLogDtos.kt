package com.nexora.api.audit.web

import com.nexora.api.audit.domain.AuditEventType
import com.nexora.api.audit.domain.AuditLog
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Un evento financiero registrado (plan.md, sección 13 \"Auditoría\") — solo lectura, nunca se edita ni se borra.")
data class AuditLogResponse(
    val id: UUID,
    val eventType: AuditEventType,
    @field:Schema(description = "Nombre simple de la clase de la entidad afectada, p.ej. \"Transaction\" o \"InstallmentPlan\".")
    val entityType: String,
    @field:Schema(description = "Id de la entidad afectada — puede ya no existir (p.ej. TRANSACTION_DELETED apunta a una Transaction ya borrada).")
    val entityId: UUID,
    @field:Schema(description = "Descripción legible del evento, lista para mostrar sin tener que reconstruirla.")
    val summary: String,
    val occurredAt: Instant,
) {
    companion object {
        fun from(log: AuditLog) = AuditLogResponse(
            id = requireNotNull(log.id),
            eventType = log.eventType,
            entityType = log.entityType,
            entityId = log.entityId,
            summary = log.summary,
            occurredAt = log.occurredAt,
        )
    }
}
