package com.nexora.api.sat.domain

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val AES = "AES"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128
private const val DEK_LENGTH_BITS = 256

/**
 * Cifrado en reposo de la e.firma (plan-integracion-sat.md, sección 4.1/4.2)
 * — el dato más sensible que maneja Nexora: comprometerlo equivale a
 * comprometer la firma legal del usuario ante el SAT.
 *
 * Envelope encryption con AES-256-GCM en dos capas:
 * - Cada [SatCertificate] tiene su propia DEK (Data Encryption Key)
 *   aleatoria, generada una vez al alta ([generateDek]), que cifra la
 *   llave privada y la contraseña de la e.firma ([encrypt]/[decrypt]).
 * - Esa DEK se cifra a su vez con la KEK del servidor ([wrapDek]/[unwrapDek]),
 *   derivada de `NEXORA_SAT_ENCRYPTION_KEY` — variable de entorno del VPS,
 *   nunca en la base de datos ni en el repo, mismo patrón que `JWT_SECRET`
 *   (ver JwtConfig). Permite rotar la KEK sin re-cifrar cada llave privada
 *   individualmente: solo se re-envuelven las DEKs.
 *
 * El formato de salida de [encrypt]/[wrapDek] es `IV (12 bytes) || ciphertext+tag`,
 * en un solo arreglo — no hace falta guardar el IV aparte.
 */
@Service
class SatCryptoService(
    @Value("\${nexora.sat.encryption-key}") encryptionKeySecret: String,
) {

    init {
        require(encryptionKeySecret.length >= 32) {
            "NEXORA_SAT_ENCRYPTION_KEY debe tener al menos 32 caracteres."
        }
    }

    // AES-256 exige una clave de exactamente 32 bytes; el secreto configurado
    // (alta entropía, ej. `openssl rand -base64 48`, igual que JWT_SECRET) se
    // normaliza a ese tamaño con SHA-256 — no es un KDF con salt/iteraciones
    // porque el secreto de entrada ya se asume de alta entropía, no una
    // contraseña humana.
    private val kek: SecretKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(encryptionKeySecret.toByteArray(Charsets.UTF_8)),
        AES,
    )

    private val secureRandom = SecureRandom()

    fun generateDek(): SecretKey {
        val generator = KeyGenerator.getInstance(AES)
        generator.init(DEK_LENGTH_BITS, secureRandom)
        return generator.generateKey()
    }

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray = seal(key, plaintext)

    fun decrypt(key: SecretKey, sealed: ByteArray): ByteArray = open(key, sealed)

    fun wrapDek(dek: SecretKey): ByteArray = seal(kek, dek.encoded)

    fun unwrapDek(wrapped: ByteArray): SecretKey = SecretKeySpec(open(kek, wrapped), AES)

    private fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    private fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        require(sealed.size > GCM_IV_LENGTH_BYTES) { "Dato cifrado inválido (demasiado corto)." }
        val iv = sealed.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = sealed.copyOfRange(GCM_IV_LENGTH_BYTES, sealed.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
