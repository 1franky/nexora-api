package com.nexora.api.user.web

import com.nexora.api.common.web.ApiError
import com.nexora.api.user.domain.UserService
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Usuarios", description = "Registro de cuentas y perfil del usuario autenticado.")
@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
) {

    @Operation(
        summary = "Registrar un nuevo usuario",
        description = "Crea la cuenta con email/contraseña. Es el único endpoint de este módulo público " +
            "(ver SecurityConfig) — no requiere Authorization. Usa POST /api/v1/auth/login después para obtener el access token.",
        responses = [
            ApiResponse(responseCode = "201", description = "Usuario creado."),
            ApiResponse(
                responseCode = "409",
                description = "Ya existe un usuario con ese email.",
                content = [Content(schema = Schema(implementation = ApiError::class))],
            ),
        ],
    )
    @SecurityRequirements
    @PostMapping
    fun register(@Valid @RequestBody request: RegisterUserRequest): ResponseEntity<UserResponse> {
        val user = userService.register(request.email, request.password, request.displayName)
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user))
    }

    @Operation(summary = "Perfil del usuario autenticado", description = "Datos del usuario dueño del access token.")
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: NexoraUserDetails): UserResponse {
        val user = userService.getById(principal.userId)
        return UserResponse.from(user)
    }
}
