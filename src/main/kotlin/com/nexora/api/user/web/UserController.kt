package com.nexora.api.user.web

import com.nexora.api.user.domain.UserService
import com.nexora.api.user.security.NexoraUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
) {

    /** Registro de un nuevo usuario. Es el único endpoint de este módulo público (ver SecurityConfig). */
    @PostMapping
    fun register(@Valid @RequestBody request: RegisterUserRequest): ResponseEntity<UserResponse> {
        val user = userService.register(request.email, request.password, request.displayName)
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: NexoraUserDetails): UserResponse {
        val user = userService.getById(principal.userId)
        return UserResponse.from(user)
    }
}
