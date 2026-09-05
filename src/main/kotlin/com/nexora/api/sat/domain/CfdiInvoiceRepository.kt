package com.nexora.api.sat.domain

import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.Instant
import java.util.UUID

interface CfdiInvoiceRepository : JpaRepository<CfdiInvoice, UUID>, JpaSpecificationExecutor<CfdiInvoice> {

    /** Idempotencia al sincronizar (plan, sección 6): mismo CFDI en un re-sync o en rangos que se traslapan no debe duplicarse. */
    fun findByUserIdAndUuidFiscal(userId: UUID, uuidFiscal: String): CfdiInvoice?

    /** Usado por [CfdiInvoiceMaintenanceService] para recorrer y corregir las facturas ya guardadas de un usuario. */
    fun findAllByUserId(userId: UUID): List<CfdiInvoice>
}

/**
 * Filtros del listado (todos opcionales, `null` = sin restringir), como
 * [Specification] en vez de un `@Query` JPQL con parámetros nullable: con
 * Postgres + JDBC, un parámetro que solo aparece dentro de una rama
 * `:param IS NULL` (para "filtro opcional") no siempre trae tipo suficiente
 * para que el driver prepare el statement ("could not determine data type
 * of parameter") — Criteria API evita el problema de raíz porque cada
 * predicado se añade solo cuando el filtro realmente aplica, nunca como
 * comparación con un parámetro potencialmente null.
 */
object CfdiInvoiceSpecifications {

    fun search(userId: UUID, tipo: CfdiTipo?, desde: Instant?, hasta: Instant?, texto: String?): Specification<CfdiInvoice> =
        Specification { root, _, cb ->
            val predicates = mutableListOf(cb.equal(root.get<UUID>("userId"), userId))
            tipo?.let { predicates += cb.equal(root.get<CfdiTipo>("tipo"), it) }
            desde?.let { predicates += cb.greaterThanOrEqualTo(root.get("fechaEmision"), it) }
            hasta?.let { predicates += cb.lessThanOrEqualTo(root.get("fechaEmision"), it) }
            texto?.let { likeTexto ->
                val pattern = "%${likeTexto.lowercase()}%"
                predicates += cb.or(
                    cb.like(cb.lower(root.get("rfcEmisor")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.get("nombreEmisor"), "")), pattern),
                    cb.like(cb.lower(root.get("rfcReceptor")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.get("nombreReceptor"), "")), pattern),
                    cb.like(cb.lower(root.get("uuidFiscal")), pattern),
                )
            }
            cb.and(*predicates.toTypedArray())
        }
}
