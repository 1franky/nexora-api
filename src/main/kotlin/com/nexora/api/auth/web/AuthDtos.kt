package com.nexora.api.auth.web

import com.nexora.api.auth.domain.TokenPair
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank(message = "El email es obligatorio.")
    @field:Email(message = "El email no es válido.")
    val email: String,

    @field:NotBlank(message = "La contraseña es obligatoria.")
    val password: String,
)

@Schema(description = "También se usa para /logout — ahí solo importa refreshToken.")
data class RefreshRequest(
    @field:NotBlank(message = "El refresh token es obligatorio.")
    val refreshToken: String,
)

@Schema(description = "B10: pide un código de un solo uso (6 dígitos) por email para restablecer la contraseña. La respuesta es siempre la misma exista o no una cuenta con ese email — ver AuthController.")
data class ForgotPasswordRequest(
    @field:NotBlank(message = "El email es obligatorio.")
    @field:Email(message = "El email no es válido.")
    val email: String,
)

@Schema(description = "B10: completa la recuperación de contraseña con el código recibido por email.")
data class ResetPasswordRequest(
    @field:NotBlank(message = "El email es obligatorio.")
    @field:Email(message = "El email no es válido.")
    val email: String,

    @field:NotBlank(message = "El código es obligatorio.")
    @field:Pattern(regexp = "\\d{6}", message = "El código debe tener 6 dígitos.")
    val code: String,

    @field:NotBlank(message = "La contraseña es obligatoria.")
    @field:Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres.")
    val newPassword: String,
)

@Schema(description = "Par de tokens JWT. accessToken va en el header Authorization de las siguientes peticiones; refreshToken se usa solo contra /auth/refresh o /auth/logout.")
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    @field:Schema(description = "Vida útil del accessToken en segundos, no del refreshToken.")
    val expiresInSeconds: Long,
) {
    companion object {
        fun from(tokens: TokenPair) = TokenResponse(tokens.accessToken, tokens.refreshToken, expiresInSeconds = tokens.expiresInSeconds)
    }
}
