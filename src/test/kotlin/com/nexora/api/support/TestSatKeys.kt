package com.nexora.api.support

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec

/**
 * Genera un certificado autofirmado + llave PKCS#8 cifrada, con la misma
 * forma con la que el SAT entrega una e.firma real (.cer DER + .key DER
 * cifrado con contraseña) — para probar el flujo completo de B11 sin
 * depender de una e.firma real. No usa las clases de producción para
 * cifrar (sería probar el código contra sí mismo); [SatKeyReader] sí se
 * ejercita de verdad al leer lo que esto genera.
 */
object TestSatKeys {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class Generated(val cerBytes: ByteArray, val keyBytes: ByteArray, val rfc: String)

    fun generate(rfc: String = "TEST010101AB1", password: String = "test-password-123"): Generated {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = Instant.now()
        // Mismo campo que usa una e.firma real del SAT para el RFC — OID
        // 2.5.4.45 (BCStyle.UNIQUE_IDENTIFIER), no el CN (ver SatKeyReader.extractRfc).
        val subject: X500Name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "Test")
            .addRDN(BCStyle.UNIQUE_IDENTIFIER, rfc)
            .build()
        val certBuilder = X509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(1, ChronoUnit.HOURS)),
            Date.from(now.plus(365, ChronoUnit.DAYS)),
            subject,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))

        // Nombre de algoritmo reconocido por BouncyCastle (PKCS#12 PBE con
        // 3DES) — "PBEWithSHA1AndDESede" (el alias de SunJCE) no tiene
        // AlgorithmParameters registrado bajo el provider "BC".
        val algorithm = "PBEWITHSHAAND3-KEYTRIPLEDES-CBC"
        val salt = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        val paramSpec = PBEParameterSpec(salt, 20)
        val pbeKey = SecretKeyFactory.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME).generateSecret(PBEKeySpec(password.toCharArray()))
        val cipher = Cipher.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME).apply { init(Cipher.ENCRYPT_MODE, pbeKey, paramSpec) }
        val encryptedData = cipher.doFinal(keyPair.private.encoded)
        val algParams = AlgorithmParameters.getInstance(algorithm, BouncyCastleProvider.PROVIDER_NAME).apply { init(paramSpec) }
        val keyBytes = EncryptedPrivateKeyInfo(algParams, encryptedData).encoded

        return Generated(certificate.encoded, keyBytes, rfc)
    }
}
