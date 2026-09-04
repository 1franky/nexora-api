package com.nexora.api.sat.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Sin contexto de Spring (ver RateLimitFilterTests): [SatCryptoService] no
 * depende de nada más que el secreto — round-trip real de cifrado, sin
 * mocks, sobre el dato más sensible que maneja Nexora (plan-integracion-sat.md,
 * sección 4).
 */
class SatCryptoServiceTests {

    private val service = SatCryptoService("test-only-sat-key-not-for-production-0123456789")

    @Test
    fun `encrypt seguido de decrypt devuelve el texto original`() {
        val dek = service.generateDek()
        val plaintext = "llave-privada-de-prueba-no-real".toByteArray()

        val encrypted = service.encrypt(dek, plaintext)
        val decrypted = service.decrypt(dek, encrypted)

        assertContentEquals(plaintext, decrypted)
        assertNotEquals(plaintext.toList(), encrypted.toList(), "el cifrado nunca debe coincidir con el texto en claro")
    }

    @Test
    fun `wrapDek seguido de unwrapDek reproduce una DEK funcional`() {
        val dek = service.generateDek()
        val wrapped = service.wrapDek(dek)
        val unwrapped = service.unwrapDek(wrapped)

        val plaintext = "otro-secreto-de-prueba".toByteArray()
        val encrypted = service.encrypt(dek, plaintext)
        val decrypted = service.decrypt(unwrapped, encrypted)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `cada llamada a encrypt produce un IV distinto, aunque el texto sea el mismo`() {
        val dek = service.generateDek()
        val plaintext = "mismo-texto".toByteArray()

        val a = service.encrypt(dek, plaintext)
        val b = service.encrypt(dek, plaintext)

        assertNotEquals(a.toList(), b.toList(), "reusar el mismo IV en AES-GCM rompe la seguridad del esquema")
    }

    @Test
    fun `una DEK distinta no puede descifrar lo que otra cifro`() {
        val dekA = service.generateDek()
        val dekB = service.generateDek()
        val encrypted = service.encrypt(dekA, "secreto".toByteArray())

        assertFailsWith<Exception> { service.decrypt(dekB, encrypted) }
    }

    @Test
    fun `rechaza una clave de cifrado del servidor demasiado corta`() {
        assertFailsWith<IllegalArgumentException> { SatCryptoService("muy-corta") }
    }
}
