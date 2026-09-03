package com.nexora.api.email

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Envía correo vía la API HTTP de Resend (no SMTP — un solo POST, RestClient
 * ya viene con Spring). No relanza si falla: [com.nexora.api.auth.domain.AuthService.forgotPassword]
 * responde igual de genérico exista o no el email (ver plan de la feature,
 * sección 3) — un fallo de envío queda solo en logs, no en la respuesta HTTP.
 */
@Component
class ResendEmailSender(private val mailProperties: MailProperties) : EmailSender {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder().baseUrl("https://api.resend.com").build()

    override fun send(to: String, subject: String, textBody: String, htmlBody: String?) {
        // Sin API key (dev local sin ganas de gastar cuota / abrir el correo cada vez):
        // loguea en vez de enviar de verdad. Nunca pasa en prod — RESEND_API_KEY es
        // obligatorio ahí (ver .env del VPS).
        if (mailProperties.resendApiKey.isNullOrBlank()) {
            log.warn("RESEND_API_KEY no configurado — correo NO enviado, solo logueado: to={} subject={} body={}", to, subject, textBody)
            return
        }
        runCatching {
            val payload = buildMap {
                put("from", "${mailProperties.fromName} <${mailProperties.fromAddress}>")
                put("to", listOf(to))
                put("subject", subject)
                put("text", textBody)
                if (htmlBody != null) put("html", htmlBody)
            }
            client.post()
                .uri("/emails")
                .header("Authorization", "Bearer ${mailProperties.resendApiKey}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity()
        }.onFailure { log.error("Fallo enviando correo vía Resend a {}", to, it) }
    }
}
