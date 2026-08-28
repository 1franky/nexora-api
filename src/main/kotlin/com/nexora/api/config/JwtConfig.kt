package com.nexora.api.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import javax.crypto.spec.SecretKeySpec

/**
 * Propiedades del JWT propio que emite/valida esta API (no hay un
 * Authorization Server externo — somos issuer y resource server a la vez).
 * [secret] se inyecta por variable de entorno (ver .env.example); debe
 * tener al menos 32 bytes (256 bits) para HS256.
 */
@ConfigurationProperties(prefix = "nexora.jwt")
data class JwtProperties(
    val secret: String,
    val accessTokenTtlMinutes: Long = 15,
    val refreshTokenTtlDays: Long = 30,
)

@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfig(
    private val jwtProperties: JwtProperties,
) {

    private fun secretKey() = SecretKeySpec(jwtProperties.secret.toByteArray(), "HmacSHA256")

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(secretKey()))

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey()).build()
}
