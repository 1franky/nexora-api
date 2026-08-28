package com.nexora.api.user.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

enum class UserStatus {
    ACTIVE,
    DISABLED,
}

/**
 * Dueño de las cuentas, categorías y movimientos. La autenticación aquí es
 * un login básico real (email + contraseña con BCrypt) contra esta tabla;
 * OAuth2/OpenID Connect + JWT + RBAC (plan.md, sección 11 "Seguridad")
 * quedan para una fase posterior.
 */
@Entity
@Table(name = "users")
class User(

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "display_name", nullable = false)
    var displayName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: UserStatus = UserStatus.ACTIVE,

) : BaseEntity()
