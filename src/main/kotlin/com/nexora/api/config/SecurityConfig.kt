package com.nexora.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Configuración de seguridad base: login básico real (email + contraseña
 * con BCrypt, ver [com.nexora.api.user.security.NexoraUserDetailsService])
 * contra la tabla de usuarios. El registro es el único endpoint de negocio
 * público; todo lo demás exige autenticación.
 *
 * OAuth2/OpenID Connect + JWT + RBAC completos (plan.md, sección 11
 * "Seguridad") quedan para una fase posterior.
 */
@Configuration
class SecurityConfig {

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
                authorize(anyRequest, authenticated)
            }
            csrf { disable() }
            httpBasic { }
        }
        return http.build()
    }
}
