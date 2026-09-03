package com.nexora.api.email

/**
 * Abstrae quién manda el correo — permite sustituirlo por un fake en tests
 * (ver `FakeEmailSender` en src/test/kotlin/com/nexora/api/support/), para
 * que ningún test le pegue de verdad a la API de Resend.
 */
interface EmailSender {
    fun send(to: String, subject: String, textBody: String)
}
