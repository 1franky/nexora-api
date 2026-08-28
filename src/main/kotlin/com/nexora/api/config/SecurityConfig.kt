package com.nexora.api.config

import com.nexora.api.user.security.NexoraJwtAuthenticationConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Configuración de seguridad: autenticación con JWT propio (ver
 * [com.nexora.api.auth.domain.AuthService] — login/refresh/logout bajo
 * /api/v1/auth, públicos). El access token lo valida este mismo backend
 * como resource server (no hay Authorization Server externo);
 * [NexoraJwtAuthenticationConverter] resuelve el `sub` del JWT a un
 * [com.nexora.api.user.security.NexoraUserDetails] real, así los
 * controladores no necesitan saber que el mecanismo cambió.
 *
 * RBAC más allá de un único rol ROLE_USER, y OAuth2/OpenID Connect con un
 * proveedor externo (plan.md, sección 11 "Seguridad"), quedan para si
 * llegan a hacer falta — hoy no hay ningún endpoint que distinga roles.
 */
@Configuration
class SecurityConfig(
    private val nexoraJwtAuthenticationConverter: NexoraJwtAuthenticationConverter,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize(HttpMethod.POST, "/api/v1/users", permitAll)
                authorize("/api/v1/auth/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            csrf { disable() }
            oauth2ResourceServer {
                jwt {
                    jwtAuthenticationConverter = nexoraJwtAuthenticationConverter
                }
            }
        }
        return http.build()
    }
}
