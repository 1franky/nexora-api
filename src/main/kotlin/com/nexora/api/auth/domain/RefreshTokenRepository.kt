package com.nexora.api.auth.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshToken?

    /** Todas las sesiones activas de un usuario — B10 las revoca tras un reset de contraseña exitoso. */
    fun findByUserIdAndRevokedAtIsNull(userId: UUID): List<RefreshToken>
}
