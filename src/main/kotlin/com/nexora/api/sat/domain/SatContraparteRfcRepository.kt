package com.nexora.api.sat.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SatContraparteRfcRepository : JpaRepository<SatContraparteRfc, UUID> {

    fun findAllByUserIdOrderByCreatedAtAsc(userId: UUID): List<SatContraparteRfc>

    fun findByIdAndUserId(id: UUID, userId: UUID): SatContraparteRfc?

    fun existsByUserIdAndRfc(userId: UUID, rfc: String): Boolean
}
