package com.nexora.api.user.web

import com.nexora.api.user.domain.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class RegisterUserRequest(
    @field:NotBlank(message = "El email es obligatorio.")
    @field:Email(message = "El email no es válido.")
    val email: String,

    @field:NotBlank(message = "La contraseña es obligatoria.")
    @field:Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres.")
    val password: String,

    @field:NotBlank(message = "El nombre es obligatorio.")
    val displayName: String,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User): UserResponse = UserResponse(
            id = requireNotNull(user.id),
            email = user.email,
            displayName = user.displayName,
            createdAt = requireNotNull(user.createdAt),
        )
    }
}
