package com.nexora.api.sat.domain

import com.nexora.api.common.domain.BusinessRuleException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Lee el `.cer` (certificado X.509, DER) y el `.key` (llave privada PKCS#8
 * DER, cifrada con la contraseña de la e.firma) que el SAT entrega al dar
 * de alta una e.firma — plan-integracion-sat.md, sección 3-4.
 *
 * El algoritmo de cifrado del `.key` del SAT no siempre lo soporta el
 * proveedor JCE por defecto de la JVM (SunJCE) — de ahí Bouncy Castle
 * (`bcprov-jdk18on`), registrado aquí como proveedor JCE adicional.
 */
object SatKeyReader {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val certificateFactory = CertificateFactory.getInstance("X.509")

    fun readCertificate(cerBytes: ByteArray): X509Certificate =
        try {
            certificateFactory.generateCertificate(cerBytes.inputStream()) as X509Certificate
        } catch (e: Exception) {
            throw BusinessRuleException("El archivo .cer no es un certificado X.509 válido.")
        }

    /** @throws BusinessRuleException si la contraseña es incorrecta o el .key está corrupto — nunca deja pasar detalle interno del error al usuario. */
    fun readPrivateKey(keyBytes: ByteArray, password: String): PrivateKey =
        try {
            val encryptedInfo = EncryptedPrivateKeyInfo(keyBytes)
            val keyFactory = SecretKeyFactory.getInstance(encryptedInfo.algName, BouncyCastleProvider.PROVIDER_NAME)
            val pbeKey = keyFactory.generateSecret(PBEKeySpec(password.toCharArray()))
            val cipher = Cipher.getInstance(encryptedInfo.algName, BouncyCastleProvider.PROVIDER_NAME)
            cipher.init(Cipher.DECRYPT_MODE, pbeKey, encryptedInfo.algParameters)
            val keySpec = encryptedInfo.getKeySpec(cipher)
            KeyFactory.getInstance("RSA").generatePrivate(keySpec)
        } catch (e: Exception) {
            throw BusinessRuleException("No se pudo leer la llave privada — contraseña incorrecta o archivo .key inválido/corrupto.")
        }

    /**
     * RFC del titular, tomado del Subject del certificado (OID 2.5.4.45,
     * `x500UniqueIdentifier`, formato "RFC/CURP" en persona física o solo
     * "RFC" en persona moral) — nunca se le pide al usuario que lo teclee.
     */
    fun extractRfc(certificate: X509Certificate): String {
        val subject = certificate.subjectX500Principal.name
        val rfcPattern = Regex("""[A-ZÑ&]{3,4}\d{6}[A-Z0-9]{3}""")
        val match = rfcPattern.find(subject)
            ?: throw BusinessRuleException("No se pudo determinar el RFC a partir del certificado — ¿es una e.firma vigente del SAT?")
        return match.value
    }
}
