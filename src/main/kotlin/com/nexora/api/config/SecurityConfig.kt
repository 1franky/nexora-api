package com.nexora.api.config

import com.nexora.api.user.security.NexoraJwtAuthenticationConverter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Orígenes desde los que el navegador puede llamar a la API (nexora-web es
 * una SPA: las peticiones salen del navegador del usuario, un origen
 * distinto al de la API — ver nexora-web/compose.yaml). Sin esto, el
 * navegador bloquea toda petición cross-origin antes de que llegue aquí
 * (falla el preflight OPTIONS), aunque curl/Postman nunca lo noten: CORS
 * es una restricción exclusiva del navegador.
 */
@ConfigurationProperties(prefix = "nexora.cors")
data class CorsProperties(val allowedOrigins: List<String>)

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
@EnableConfigurationProperties(CorsProperties::class)
class SecurityConfig(
    private val nexoraJwtAuthenticationConverter: NexoraJwtAuthenticationConverter,
    private val corsProperties: CorsProperties,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = corsProperties.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            cors { }
            authorizeHttpRequests {
                authorize(HttpMethod.OPTIONS, "/**", permitAll)
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
