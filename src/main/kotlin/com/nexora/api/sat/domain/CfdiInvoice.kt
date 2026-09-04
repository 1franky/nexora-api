package com.nexora.api.sat.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class CfdiEstadoSat {
    VIGENTE,
    CANCELADO,
}

/**
 * Metadata + XML crudo de un CFDI descargado del SAT (plan, sección 5).
 * [tipo] es relativo al usuario dueño de la fila: EMITIDAS si el usuario es
 * el emisor (rfcEmisor == su RFC conectado), RECIBIDAS si es el receptor —
 * ver [com.nexora.api.sat.domain.CfdiParser].
 *
 * [xmlContent] es el XML tal como lo entregó el SAT (sin transformar): unos
 * pocos KB por factura, se guarda en Postgres igual que el certificado/llave
 * de [SatCertificate] — no se necesita un object storage aparte para el
 * volumen esperado en finanzas personales (plan, sección 4.1).
 */
@Entity
@Table(name = "cfdi_invoice")
class CfdiInvoice(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "uuid_fiscal", nullable = false, length = 36)
    var uuidFiscal: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var tipo: CfdiTipo,

    @Column(name = "rfc_emisor", nullable = false, length = 13)
    var rfcEmisor: String,

    @Column(name = "nombre_emisor")
    var nombreEmisor: String?,

    @Column(name = "rfc_receptor", nullable = false, length = 13)
    var rfcReceptor: String,

    @Column(name = "nombre_receptor")
    var nombreReceptor: String?,

    @Column(name = "fecha_emision", nullable = false)
    var fechaEmision: Instant,

    @Column(nullable = false, precision = 14, scale = 2)
    var subtotal: BigDecimal,

    @Column(nullable = false, precision = 14, scale = 2)
    var iva: BigDecimal,

    @Column(nullable = false, precision = 14, scale = 2)
    var total: BigDecimal,

    @Column(nullable = false, length = 3)
    var moneda: String,

    @Column(name = "forma_pago", length = 10)
    var formaPago: String?,

    @Column(name = "metodo_pago", length = 10)
    var metodoPago: String?,

    @Column(name = "uso_cfdi", length = 10)
    var usoCfdi: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_sat", nullable = false, length = 20)
    var estadoSat: CfdiEstadoSat = CfdiEstadoSat.VIGENTE,

    // Sin @Lob (ver SatCertificate) — mapea a BYTEA, no a OID.
    @Column(name = "xml_content", nullable = false)
    var xmlContent: ByteArray,

) : BaseEntity()
