package com.nexora.api.email

/**
 * Abstrae quién manda el correo — permite sustituirlo por un fake en tests
 * (ver `FakeEmailSender` en src/test/kotlin/com/nexora/api/support/), para
 * que ningún test le pegue de verdad a la API de Resend.
 *
 * [htmlBody] es opcional: cuando se manda, va junto con [textBody] (Resend,
 * como cualquier proveedor serio, acepta ambos en el mismo envío) — el
 * cliente de correo del destinatario elige cuál mostrar, y el texto plano
 * queda como respaldo para lectores de pantalla o clientes que no rendericen
 * HTML.
 */
interface EmailSender {
    fun send(to: String, subject: String, textBody: String, htmlBody: String? = null)
}
