package com.nexora.api.sat.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SatCertificateRepository : JpaRepository<SatCertificate, UUID> {
    fun findByUserId(userId: UUID): SatCertificate?
    fun findAllByStatus(status: SatCertificateStatus): List<SatCertificate>
}
