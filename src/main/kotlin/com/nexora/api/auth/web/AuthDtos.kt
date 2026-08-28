package com.nexora.api.auth.web

import com.nexora.api.auth.domain.TokenPair
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank(message = "El email es obligatorio.")
    @field:Email(message = "El email no es válido.")
    val email: String,

    @field:NotBlank(message = "La contraseña es obligatoria.")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank(message = "El refresh token es obligatorio.")
    val refreshToken: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
) {
    companion object {
        fun from(tokens: TokenPair) = TokenResponse(tokens.accessToken, tokens.refreshToken, expiresInSeconds = tokens.expiresInSeconds)
    }
}
