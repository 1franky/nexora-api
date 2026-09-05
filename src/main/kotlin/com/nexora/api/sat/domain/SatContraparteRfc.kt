package com.nexora.api.sat.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * RFC de una contraparte (empleador, proveedor de servicios, etc.) que el
 * usuario registra a mano para poder traer sus CFDI RECIBIDAS — el SAT
 * exige el RFC del emisor específico en cada solicitud de descarga de
 * recibidas, no hay forma de pedir "todo lo que me han facturado" (ver
 * SatWsDescargaMasivaClient.solicitarDescarga, y plan-integracion-sat.md).
 *
 * [alias] es solo para que el usuario identifique la fila en la UI ("Mi
 * trabajo", "CFE"); no se usa en ninguna llamada al SAT.
 */
@Entity
@Table(name = "sat_contraparte_rfc")
class SatContraparteRfc(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(nullable = false, length = 13)
    var rfc: String,

    @Column(length = 100)
    var alias: String? = null,

) : BaseEntity()
