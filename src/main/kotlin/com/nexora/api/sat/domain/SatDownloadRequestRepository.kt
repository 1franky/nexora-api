package com.nexora.api.sat.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SatDownloadRequestRepository : JpaRepository<SatDownloadRequest, UUID> {
    fun findAllBySatCertificateIdOrderByCreatedAtDesc(satCertificateId: UUID): List<SatDownloadRequest>
    fun findAllByEstadoIn(estados: List<SatDownloadRequestStatus>): List<SatDownloadRequest>
}
