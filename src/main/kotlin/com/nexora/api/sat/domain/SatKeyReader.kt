package com.nexora.api.sat.domain

import com.nexora.api.common.domain.BusinessRuleException
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Lee el `.cer` (certificado X.509, DER) y el `.key` (llave privada PKCS#8
 * DER, cifrada con la contraseña de la e.firma) que el SAT entrega al dar
 * de alta una e.firma — plan-integracion-sat.md, sección 3-4.
 *
 * El `.key` real del SAT usa PBES2 (PKCS#5 v2.0) con DES-EDE3-CBC como
 * cifrado subyacente — verificado contra una e.firma real. El parser nativo
 * de Java (`javax.crypto.EncryptedPrivateKeyInfo`, que usa SunJCE para leer
 * el `AlgorithmIdentifier` embebido) no lo reconoce ("expecting the object
 * identifier for AES cipher") sin importar qué provider se pase después —
 * el fallo ocurre en el parsing, antes de llegar ahí. Por eso se usa la API
 * nativa de BouncyCastle (`org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo`),
 * que parsea el ASN.1 con su propio código en vez de depender del registro
 * de providers de la JVM.
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
            val encryptedInfo = PKCS8EncryptedPrivateKeyInfo(keyBytes)
            val decryptorProvider = JcePKCSPBEInputDecryptorProviderBuilder()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(password.toCharArray())
            val privateKeyInfo = encryptedInfo.decryptPrivateKeyInfo(decryptorProvider)
            JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getPrivateKey(privateKeyInfo)
        } catch (e: Exception) {
            throw BusinessRuleException("No se pudo leer la llave privada — contraseña incorrecta o archivo .key inválido/corrupto.")
        }

    /**
     * RFC del titular, tomado del Subject del certificado (OID 2.5.4.45,
     * `x500UniqueIdentifier`) — nunca se le pide al usuario que lo teclee.
     *
     * `X509Certificate.subjectX500Principal.name` (java.security estándar)
     * NO decodifica este OID a texto: al no tener un nombre corto conocido
     * por la JVM, lo deja como `2.5.4.45=#<hex DER>` crudo — verificado
     * contra una e.firma real. Por eso se lee con el `X500Name` de
     * BouncyCastle (`JcaX509CertificateHolder` + `IETFUtils.valueToString`),
     * que sí decodifica el ASN.1 subyacente a texto plano.
     */
    fun extractRfc(certificate: X509Certificate): String {
        val subject = JcaX509CertificateHolder(certificate).subject
        val rawValue = subject.getRDNs(BCStyle.UNIQUE_IDENTIFIER).firstOrNull()?.first?.value
            ?.let { IETFUtils.valueToString(it) }
            ?: throw BusinessRuleException("No se pudo determinar el RFC a partir del certificado — ¿es una e.firma vigente del SAT?")

        // En persona física, algunos certificados traen "RFC/CURP" en el mismo campo — se toma solo el RFC.
        val candidate = rawValue.substringBefore("/").trim().uppercase()
        val rfcPattern = Regex("""^[A-ZÑ&]{3,4}\d{6}[A-Z0-9]{3}$""")
        return if (rfcPattern.matches(candidate)) {
            candidate
        } else {
            throw BusinessRuleException("El campo de identificación del certificado no tiene forma de RFC (\"$candidate\").")
        }
    }
}
