package com.nexora.api.auth.web

import com.nexora.api.auth.domain.AuthService
import com.nexora.api.common.web.ApiError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Login/refresh/logout. Todo bajo /api/v1/auth es público (ver SecurityConfig). */
@Tag(name = "Autenticación", description = "Login, refresh y logout con JWT propio. Todo público, no requiere Authorization.")
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {

    @Operation(
        summary = "Iniciar sesión",
        description = "Devuelve un access token (de vida corta, ver JWT_ACCESS_TOKEN_TTL_MINUTES) y un refresh token.",
        responses = [
            ApiResponse(responseCode = "200", description = "Par de tokens emitido."),
            ApiResponse(
                responseCode = "401",
                description = "Email o contraseña inválidos.",
                content = [Content(schema = Schema(implementation = ApiError::class))],
            ),
        ],
    )
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenResponse =
        TokenResponse.from(authService.login(request.email, request.password))

    @Operation(
        summary = "Renovar el access token",
        description = "Cambia un refresh token válido por un nuevo par de tokens (rotación: el refresh token usado queda invalidado).",
        responses = [
            ApiResponse(responseCode = "200", description = "Nuevo par de tokens emitido."),
            ApiResponse(
                responseCode = "401",
                description = "El refresh token es inválido, expiró, o ya fue usado/revocado.",
                content = [Content(schema = Schema(implementation = ApiError::class))],
            ),
        ],
    )
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse =
        TokenResponse.from(authService.refresh(request.refreshToken))

    @Operation(
        summary = "Cerrar sesión",
        description = "Revoca el refresh token — el access token ya emitido sigue siendo válido hasta que expire por sí solo.",
        responses = [ApiResponse(responseCode = "204", description = "Sesión cerrada (o el refresh token ya no era válido; es idempotente).")],
    )
    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: RefreshRequest): ResponseEntity<Void> {
        authService.logout(request.refreshToken)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Pedir código para restablecer la contraseña",
        description = "Envía un código de 6 dígitos por email, válido 10 minutos. La respuesta es SIEMPRE la misma exista o no una cuenta con ese email — no revela qué emails están registrados.",
        responses = [ApiResponse(responseCode = "200", description = "Si existe una cuenta con ese email, se envió un código.")],
    )
    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): ResponseEntity<Void> {
        authService.forgotPassword(request.email)
        return ResponseEntity.ok().build()
    }

    @Operation(
        summary = "Restablecer la contraseña con el código recibido",
        description = "Si el código es correcto, actualiza la contraseña y cierra todas las sesiones activas del usuario (hay que loguearse de nuevo).",
        responses = [
            ApiResponse(responseCode = "204", description = "Contraseña actualizada."),
            ApiResponse(
                responseCode = "401",
                description = "El código es inválido, expiró, ya se usó, o se agotaron los intentos.",
                content = [Content(schema = Schema(implementation = ApiError::class))],
            ),
        ],
    )
    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): ResponseEntity<Void> {
        authService.resetPassword(request.email, request.code, request.newPassword)
        return ResponseEntity.noContent().build()
    }
}
