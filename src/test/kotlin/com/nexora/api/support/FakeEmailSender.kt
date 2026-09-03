package com.nexora.api.support

import com.nexora.api.email.EmailSender
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

data class SentEmail(val to: String, val subject: String, val textBody: String, val htmlBody: String?)

/**
 * Captura los correos "enviados" en memoria en vez de pegarle a la API real
 * de Resend — ningún test debe depender de una red externa. Es la única
 * forma de leer el código OTP en un test: nunca se devuelve en la respuesta
 * HTTP de /auth/forgot-password (ver plan de la feature, sección 3, punto 7).
 */
class FakeEmailSender : EmailSender {
    val sent = mutableListOf<SentEmail>()

    override fun send(to: String, subject: String, textBody: String, htmlBody: String?) {
        sent += SentEmail(to, subject, textBody, htmlBody)
    }

    fun lastCodeFor(email: String): String =
        Regex("""\d{6}""").find(sent.last { it.to.equals(email, ignoreCase = true) }.textBody)!!.value
}

@TestConfiguration(proxyBeanMethods = false)
class TestEmailConfig {
    @Bean
    @Primary
    fun fakeEmailSender(): FakeEmailSender = FakeEmailSender()
}
