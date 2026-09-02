package com.nexora.api.audit.domain

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AuditLogService(
    private val auditLogRepository: AuditLogRepository,
) {

    /**
     * Registra un evento. `Propagation.MANDATORY`: siempre se llama desde
     * dentro de la transacción de la operación que audita (ver
     * TransactionService/InstallmentPlanService) — si esa operación
     * revierte, la entrada de auditoría también, para no dejar un registro
     * de algo que en realidad no pasó.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(userId: UUID, eventType: AuditEventType, entityType: String, entityId: UUID, summary: String) {
        auditLogRepository.save(AuditLog(userId = userId, eventType = eventType, entityType = entityType, entityId = entityId, summary = summary))
    }

    /** Más recientes primero. [limit] acotado por el llamador (mismo criterio que recentTransactionsLimit en el dashboard). */
    fun listForUser(userId: UUID, limit: Int): List<AuditLog> =
        auditLogRepository.findAllByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, limit))
}
