package com.nexora.api.installment.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InstallmentPlanRepository : JpaRepository<InstallmentPlan, UUID> {
    fun findAllByCreditCardId(creditCardId: UUID): List<InstallmentPlan>
}
