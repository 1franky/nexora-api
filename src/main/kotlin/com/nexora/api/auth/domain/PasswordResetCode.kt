package com.nexora.api.auth.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

private const val MAX_ATTEMPTS = 5

/**
 * OTP de 6 dígitos para recuperar la contraseña (B10, ver plan de la
 * feature). A diferencia de [RefreshToken] (256 bits de entropía), un
 * código de 6 dígitos es intrínsecamente débil (~20 bits) — [attempts]
 * limita los intentos de verificación por código, para que agotar el
 * espacio de valores por fuerza bruta online no sea viable dentro de su
 * ventana de vida.
 */
@Entity
@Table(name = "password_reset_codes")
class PasswordResetCode(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "code_hash", nullable = false)
    var codeHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "used_at")
    var usedAt: Instant? = null,

    @Column(nullable = false)
    var attempts: Int = 0,

) : BaseEntity() {

    fun isUsable(now: Instant): Boolean = usedAt == null && now.isBefore(expiresAt) && attempts < MAX_ATTEMPTS
}
