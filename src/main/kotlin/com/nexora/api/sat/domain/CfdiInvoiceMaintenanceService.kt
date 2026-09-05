package com.nexora.api.sat.domain

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Correcciones puntuales sobre facturas ya guardadas — a diferencia de
 * [SatSyncService], no habla con el SAT: solo re-lee el `xmlContent` que ya
 * está en BD. Hoy solo tiene el recálculo de IVA (bug corregido en B13:
 * [CfdiParser] sumaba el desglose de impuestos de cada Concepto además del
 * ya agregado a nivel Comprobante, duplicando el IVA de toda factura
 * sincronizada antes del fix).
 */
@Service
class CfdiInvoiceMaintenanceService(
    private val cfdiInvoiceRepository: CfdiInvoiceRepository,
    private val cfdiParser: CfdiParser,
) {

    @Transactional
    fun recalcularIva(userId: UUID): Int {
        val corregidas = cfdiInvoiceRepository.findAllByUserId(userId).count { invoice ->
            val ivaCorrecto = cfdiParser.recalcularIva(invoice.xmlContent)
            val cambio = ivaCorrecto.compareTo(invoice.iva) != 0
            if (cambio) {
                invoice.iva = ivaCorrecto
                cfdiInvoiceRepository.save(invoice)
            }
            cambio
        }
        return corregidas
    }
}
