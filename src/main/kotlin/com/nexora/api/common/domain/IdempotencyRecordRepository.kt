package com.nexora.api.common.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, UUID> {
    fun findByUserIdAndKeyValue(userId: UUID, keyValue: String): IdempotencyRecord?
}
