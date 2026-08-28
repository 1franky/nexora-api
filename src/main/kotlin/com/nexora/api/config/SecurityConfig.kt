package com.nexora.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain

/**
 * Configuración de seguridad base.
 *
 * Esto es un placeholder: por ahora solo deja públicos los endpoints de
 * salud y de documentación, y exige autenticación básica para el resto.
 * OAuth2/OpenID Connect + JWT + RBAC (ver plan.md, sección 11 "Seguridad")
 * se implementarán en una fase posterior del roadmap (B1 → seguridad real).
 */
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            csrf { disable() }
            httpBasic { }
        }
        return http.build()
    }
}
