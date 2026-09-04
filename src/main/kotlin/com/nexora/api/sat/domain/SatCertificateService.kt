package com.nexora.api.sat.domain

import com.nexora.api.audit.domain.AuditEventType
import com.nexora.api.audit.domain.AuditLogService
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.PrivateKey
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID

/**
 * Alta, baja y desencriptado de la e.firma conectada por un usuario
 * (plan-integracion-sat.md, sección 4 y 6). Un usuario tiene a lo más una
 * — v1 no soporta multi-RFC (plan, sección 10).
 */
@Service
class SatCertificateService(
    private val repository: SatCertificateRepository,
    private val cryptoService: SatCryptoService,
    private val soapClient: SatSoapClient,
    private val auditLogService: AuditLogService,
) {

    private val log = LoggerFactory.getLogger(SatCertificateService::class.java)

    /**
     * Valida certificado + llave + contraseña (incluyendo un login de
     * prueba real contra el SAT, plan sección 6 paso 3) y los guarda
     * cifrados. Reemplaza cualquier e.firma conectada previamente por este
     * usuario — no hay historial de e.firmas viejas, solo la vigente.
     */
    @Transactional
    fun connect(userId: UUID, cerBytes: ByteArray, keyBytes: ByteArray, password: String): SatCertificate {
        val certificate = SatKeyReader.readCertificate(cerBytes)
        checkVigencia(certificate)
        val rfc = SatKeyReader.extractRfc(certificate)
        // Falla temprano y con mensaje claro si la contraseña no descifra la llave —
        // antes de siquiera intentar hablar con el SAT.
        val privateKey = SatKeyReader.readPrivateKey(keyBytes, password)

        validarConSat(certificate, privateKey)

        val dek = cryptoService.generateDek()
        val entity = (repository.findByUserId(userId) ?: SatCertificate(
            userId = userId,
            rfc = rfc,
            certificateDer = certificate.encoded,
            privateKeyEncrypted = ByteArray(0),
            passwordEncrypted = ByteArray(0),
            dekEncrypted = ByteArray(0),
            validUntil = certificate.notAfter.toInstant(),
        )).apply {
            this.rfc = rfc
            this.certificateDer = certificate.encoded
            this.privateKeyEncrypted = cryptoService.encrypt(dek, keyBytes)
            this.passwordEncrypted = cryptoService.encrypt(dek, password.toByteArray(Charsets.UTF_8))
            this.dekEncrypted = cryptoService.wrapDek(dek)
            this.validUntil = certificate.notAfter.toInstant()
            this.status = SatCertificateStatus.ACTIVO
        }

        val saved = repository.save(entity)
        auditLogService.record(
            userId = userId,
            eventType = AuditEventType.SAT_CERTIFICATE_CONNECTED,
            entityType = "SatCertificate",
            entityId = requireNotNull(saved.id),
            summary = "Conectó su e.firma (RFC $rfc) para descarga de CFDI.",
        )
        log.info("Usuario {} conectó e.firma RFC {} (vigente hasta {}).", userId, rfc, saved.validUntil)
        return saved
    }

    fun getOwned(userId: UUID): SatCertificate =
        repository.findByUserId(userId) ?: throw NotFoundException("No tienes una e.firma conectada.")

    @Transactional
    fun revoke(userId: UUID) {
        val certificate = getOwned(userId)
        // Borrado real del material sensible (plan, sección 4) — se conserva
        // la fila solo para no perder el historial de auditoría de cuándo se
        // conectó/desconectó, pero sin nada descifrable.
        certificate.privateKeyEncrypted = ByteArray(0)
        certificate.passwordEncrypted = ByteArray(0)
        certificate.dekEncrypted = ByteArray(0)
        certificate.status = SatCertificateStatus.REVOCADO
        repository.save(certificate)
        auditLogService.record(
            userId = userId,
            eventType = AuditEventType.SAT_CERTIFICATE_REVOKED,
            entityType = "SatCertificate",
            entityId = requireNotNull(certificate.id),
            summary = "Desconectó su e.firma (RFC ${certificate.rfc}).",
        )
    }

    /** Descifra en memoria para firmar una llamada al SAT — nunca se persiste ni se loguea el resultado. */
    fun decryptCredentials(certificate: SatCertificate): Pair<X509Certificate, PrivateKey> {
        check(certificate.status == SatCertificateStatus.ACTIVO) { "La e.firma no está activa." }
        val dek = cryptoService.unwrapDek(certificate.dekEncrypted)
        val keyBytes = cryptoService.decrypt(dek, certificate.privateKeyEncrypted)
        val password = String(cryptoService.decrypt(dek, certificate.passwordEncrypted), Charsets.UTF_8)
        val x509 = SatKeyReader.readCertificate(certificate.certificateDer)
        val privateKey = SatKeyReader.readPrivateKey(keyBytes, password)
        return x509 to privateKey
    }

    /** Marca la e.firma en error cuando el SAT rechaza la autenticación en una sync (contraseña cambiada, certificado revocado, etc.) — el usuario debe reconectar. */
    @Transactional
    fun markAuthenticationError(satCertificateId: UUID) {
        val certificate = repository.findById(satCertificateId).orElseThrow { NotFoundException("e.firma no encontrada.") }
        certificate.status = SatCertificateStatus.ERROR_AUTENTICACION
        repository.save(certificate)
        auditLogService.record(
            userId = certificate.userId,
            eventType = AuditEventType.SAT_CERTIFICATE_REVOKED,
            entityType = "SatCertificate",
            entityId = satCertificateId,
            summary = "El SAT rechazó la autenticación — la e.firma necesita reconectarse.",
        )
    }

    private fun checkVigencia(certificate: X509Certificate) {
        try {
            certificate.checkValidity(java.util.Date.from(Instant.now()))
        } catch (e: CertificateExpiredException) {
            throw BusinessRuleException("El certificado de la e.firma ya expiró (venció el ${certificate.notAfter}).")
        } catch (e: CertificateNotYetValidException) {
            throw BusinessRuleException("El certificado de la e.firma todavía no es válido (vigente desde el ${certificate.notBefore}).")
        }
    }

    private fun validarConSat(certificate: X509Certificate, privateKey: PrivateKey) {
        try {
            soapClient.autenticar(certificate, privateKey)
        } catch (e: SatProtocolException) {
            throw BusinessRuleException("El SAT rechazó la e.firma: ${e.message}")
        }
    }
}
