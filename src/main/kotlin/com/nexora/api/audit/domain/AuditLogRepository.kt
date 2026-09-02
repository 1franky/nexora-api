package com.nexora.api.audit.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuditLogRepository : JpaRepository<AuditLog, UUID> {
    fun findAllByUserIdOrderByOccurredAtDesc(userId: UUID, pageable: Pageable): List<AuditLog>
}
