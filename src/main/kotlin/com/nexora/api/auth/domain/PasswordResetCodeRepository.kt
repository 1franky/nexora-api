package com.nexora.api.auth.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface PasswordResetCodeRepository : JpaRepository<PasswordResetCode, UUID> {
    fun findByUserIdAndUsedAtIsNullAndExpiresAtAfter(userId: UUID, now: Instant): List<PasswordResetCode>
}
