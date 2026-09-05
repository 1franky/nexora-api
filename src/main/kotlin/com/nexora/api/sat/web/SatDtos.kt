package com.nexora.api.sat.web

import com.nexora.api.sat.domain.CfdiEstadoSat
import com.nexora.api.sat.domain.CfdiInvoice
import com.nexora.api.sat.domain.CfdiTipo
import com.nexora.api.sat.domain.SatCertificate
import com.nexora.api.sat.domain.SatCertificateStatus
import com.nexora.api.sat.domain.SatContraparteRfc
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class SatCertificateResponse(
    val rfc: String,
    val status: SatCertificateStatus,
    val validUntil: Instant,
    val lastSyncAt: Instant?,
) {
    companion object {
        fun from(entity: SatCertificate) = SatCertificateResponse(
            rfc = entity.rfc,
            status = entity.status,
            validUntil = entity.validUntil,
            lastSyncAt = entity.lastSyncAt,
        )
    }
}

@Schema(description = "Sincronización manual por rango de fechas explícito (plan, sección 6.1) — omitir ambos campos para una sync incremental normal (desde la última exitosa, o los últimos meses configurados si es la primera vez).")
data class SyncRequest(
    val desde: Instant? = null,
    val hasta: Instant? = null,
)

data class CfdiInvoiceResponse(
    val id: UUID,
    val uuidFiscal: String,
    val tipo: CfdiTipo,
    val rfcEmisor: String,
    val nombreEmisor: String?,
    val rfcReceptor: String,
    val nombreReceptor: String?,
    val fechaEmision: Instant,
    val subtotal: BigDecimal,
    val iva: BigDecimal,
    val total: BigDecimal,
    val moneda: String,
    val formaPago: String?,
    val metodoPago: String?,
    val usoCfdi: String?,
    val estadoSat: CfdiEstadoSat,
) {
    companion object {
        fun from(entity: CfdiInvoice) = CfdiInvoiceResponse(
            id = requireNotNull(entity.id),
            uuidFiscal = entity.uuidFiscal,
            tipo = entity.tipo,
            rfcEmisor = entity.rfcEmisor,
            nombreEmisor = entity.nombreEmisor,
            rfcReceptor = entity.rfcReceptor,
            nombreReceptor = entity.nombreReceptor,
            fechaEmision = entity.fechaEmision,
            subtotal = entity.subtotal,
            iva = entity.iva,
            total = entity.total,
            moneda = entity.moneda,
            formaPago = entity.formaPago,
            metodoPago = entity.metodoPago,
            usoCfdi = entity.usoCfdi,
            estadoSat = entity.estadoSat,
        )
    }
}

data class RecalcularIvaResponse(val corregidas: Int)

@Schema(description = "RFC de una contraparte (empleador, proveedor de servicios, etc.) a registrar para poder sincronizar tus CFDI RECIBIDAS de ese RFC — el SAT exige el RFC del emisor específico en cada solicitud de recibidas.")
data class CreateSatContraparteRequest(
    @field:NotBlank(message = "El RFC es obligatorio.")
    val rfc: String,
    val alias: String? = null,
)

data class SatContraparteResponse(
    val id: UUID,
    val rfc: String,
    val alias: String?,
) {
    companion object {
        fun from(entity: SatContraparteRfc) = SatContraparteResponse(
            id = requireNotNull(entity.id),
            rfc = entity.rfc,
            alias = entity.alias,
        )
    }
}
