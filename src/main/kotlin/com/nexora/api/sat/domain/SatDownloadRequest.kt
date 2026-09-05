package com.nexora.api.sat.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class CfdiTipo {
    EMITIDAS,
    RECIBIDAS,
}

/** Estados del protocolo de Descarga Masiva (plan, sección 3) — reflejan 1:1 los que devuelve el SAT en VerificaSolicitudDescarga. */
enum class SatDownloadRequestStatus {
    PENDIENTE,
    EN_PROCESO,
    TERMINADA,
    ERROR,
    RECHAZADA,
}

/**
 * Una solicitud de descarga masiva contra el SAT (pasos 2-4 del protocolo,
 * plan-integracion-sat.md sección 3): un rango de fechas + tipo
 * (emitidas/recibidas) para un [SatCertificate]. [idsPaquetes] guarda los
 * `IdPaquete` devueltos por `VerificaSolicitudDescarga` separados por coma
 * (normalmente uno solo; el SAT puede partir un rango grande en varios).
 */
@Entity
@Table(name = "sat_download_request")
class SatDownloadRequest(

    @Column(name = "sat_certificate_id", nullable = false)
    var satCertificateId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var tipo: CfdiTipo,

    @Column(name = "fecha_inicio", nullable = false)
    var fechaInicio: Instant,

    @Column(name = "fecha_fin", nullable = false)
    var fechaFin: Instant,

    @Column(name = "id_solicitud_sat", length = 100)
    var idSolicitudSat: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var estado: SatDownloadRequestStatus = SatDownloadRequestStatus.PENDIENTE,

    @Column(name = "ids_paquetes", columnDefinition = "TEXT")
    var idsPaquetes: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    /** Solo en RECIBIDAS (B12): el RFC emisor específico consultado — el SAT no permite pedir "todos mis recibidos" en una sola solicitud. Null en EMITIDAS. */
    @Column(name = "rfc_contraparte", length = 13)
    var rfcContraparte: String? = null,

) : BaseEntity()
