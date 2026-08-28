package com.nexora.api.installment.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InstallmentRepository : JpaRepository<Installment, UUID> {
    fun findAllByInstallmentPlanIdOrderByNumber(installmentPlanId: UUID): List<Installment>
    fun findByIdAndInstallmentPlanId(id: UUID, installmentPlanId: UUID): Installment?
}
