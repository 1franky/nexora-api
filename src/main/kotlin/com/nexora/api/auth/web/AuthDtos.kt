package com.nexora.api.auth.web

import com.nexora.api.auth.domain.TokenPair
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

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
