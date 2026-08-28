package com.nexora.api.auth.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Refresh token opaco (no es un JWT): se genera con bytes aleatorios y solo
 * se guarda su hash (SHA-256), nunca el valor en claro — igual que una
 * contraseña, así una fuga de la base de datos no expone tokens usables.
 * Permite revocar sesiones (logout) y rotar en cada refresh (si alguien
 * reusa un refresh token ya rotado, es señal de robo — ver [AuthService]).
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

) : BaseEntity() {

    fun isValid(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)
}
