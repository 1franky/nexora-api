package com.nexora.api.sat.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class SatCertificateStatus {
    /** Alta validada contra el SAT (login de prueba exitoso) y sincronizando con normalidad. */
    ACTIVO,

    /** El SAT rechazó la última autenticación (contraseña cambiada en el portal, certificado revocado, etc.) — requiere que el usuario reconecte. */
    ERROR_AUTENTICACION,

    /** El usuario desconectó la e.firma explícitamente. Se conserva la fila (no se borra) solo si hace falta auditar cuándo se desconectó; el material sensible ya se borró — ver [com.nexora.api.sat.domain.SatCertificateService.revoke]. */
    REVOCADO,
}

/**
 * e.firma (FIEL) conectada por el usuario para descargar sus CFDI del SAT
 * (plan-integracion-sat.md, sección 4-5). El RFC se toma del propio
 * certificado al validarlo, no se captura a mano.
 *
 * Envelope encryption (plan, sección 4.1/4.2): [privateKeyEncrypted] y
 * [passwordEncrypted] están cifrados con la DEK de este registro
 * ([dekEncrypted], generada al alta); la DEK a su vez está cifrada con la
 * clave maestra del servidor (`NEXORA_SAT_ENCRYPTION_KEY`, fuera de la BD)
 * — ver [com.nexora.api.sat.domain.SatCryptoService]. [certificateDer] es
 * público (certificado X.509, sin la llave privada) y no necesita cifrado.
 *
 * Nunca se hace soft-delete de una fila: al revocar, [privateKeyEncrypted],
 * [passwordEncrypted] y [dekEncrypted] se sobrescriben con arreglos vacíos
 * y el estado pasa a [SatCertificateStatus.REVOCADO] — ver
 * [com.nexora.api.sat.domain.SatCertificateService.revoke].
 */
@Entity
@Table(name = "sat_certificate")
class SatCertificate(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(nullable = false, length = 13)
    var rfc: String,

    // Sin @Lob a propósito: en Hibernate, @Lob sobre un ByteArray mapea a
    // Postgres OID (Large Object, una tabla aparte) en vez de BYTEA. La
    // migración (V11) crea columnas BYTEA — el tipo simple ya guarda esto
    // como BYTEA sin más anotación.
    @Column(name = "certificate_der", nullable = false)
    var certificateDer: ByteArray,

    @Column(name = "private_key_encrypted", nullable = false)
    var privateKeyEncrypted: ByteArray,

    @Column(name = "password_encrypted", nullable = false)
    var passwordEncrypted: ByteArray,

    @Column(name = "dek_encrypted", nullable = false)
    var dekEncrypted: ByteArray,

    @Column(name = "valid_until", nullable = false)
    var validUntil: Instant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: SatCertificateStatus = SatCertificateStatus.ACTIVO,

    @Column(name = "last_sync_at")
    var lastSyncAt: Instant? = null,

) : BaseEntity()
