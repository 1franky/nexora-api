package com.nexora.api.email

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Config de envío de correo transaccional (B10, ver plan de la feature) vía
 * Resend — la API key se inyecta por variable de entorno (ver .env.example);
 * vacía en dev local es válido (ver [ResendEmailSender]), pero en el VPS
 * siempre debe tener un valor real.
 */
@ConfigurationProperties(prefix = "nexora.mail")
data class MailProperties(
    val resendApiKey: String?,
    val fromAddress: String,
    val fromName: String,
)

@Configuration
@EnableConfigurationProperties(MailProperties::class)
class MailConfig
