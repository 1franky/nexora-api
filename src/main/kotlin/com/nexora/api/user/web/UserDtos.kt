package com.nexora.api.user.web

import com.nexora.api.user.domain.User
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

@Schema(description = "Alta de un usuario nuevo.")
data class RegisterUserRequest(
    @field:NotBlank(message = "El email es obligatorio.")
    @field:Email(message = "El email no es válido.")
    @field:Schema(description = "Email único, usado como identificador para login.", example = "ana@example.com")
    val email: String,

    @field:NotBlank(message = "La contraseña es obligatoria.")
    @field:Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres.")
    @field:Schema(description = "Mínimo 8 caracteres. Se guarda hasheada (BCrypt), nunca en texto plano.")
    val password: String,

    @field:NotBlank(message = "El nombre es obligatorio.")
    @field:Schema(description = "Nombre para mostrar en la UI (no es único).", example = "Ana García")
    val displayName: String,
)

@Schema(description = "Datos públicos de un usuario.")
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
