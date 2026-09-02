package com.nexora.api.installment.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Sort
import java.time.LocalDate
import java.util.UUID

interface InstallmentRepository : JpaRepository<Installment, UUID> {
    fun findAllByInstallmentPlanIdOrderByNumber(installmentPlanId: UUID): List<Installment>
    fun findByIdAndInstallmentPlanId(id: UUID, installmentPlanId: UUID): Installment?

    /**
     * Todas las cuotas de varios planes en una sola query (agrupar por
     * installmentPlanId y ordenar por number queda del lado del llamador) —
     * usada para evitar el N+1 de pedir las cuotas plan por plan.
     */
    fun findAllByInstallmentPlanIdIn(installmentPlanIds: List<UUID>, sort: Sort): List<Installment>
    fun findAllByInstallmentPlanIdInAndStatusAndDueDateBetween(
        installmentPlanIds: List<UUID>,
        status: InstallmentStatus,
        start: LocalDate,
        end: LocalDate,
    ): List<Installment>

    fun findAllByInstallmentPlanIdInAndStatus(installmentPlanIds: List<UUID>, status: InstallmentStatus): List<Installment>
}
