package com.nexora.api.installment.web

import com.nexora.api.installment.domain.Installment
import com.nexora.api.installment.domain.InstallmentPlanStatus
import com.nexora.api.installment.domain.InstallmentPlanType
import com.nexora.api.installment.domain.InstallmentPlanView
import com.nexora.api.installment.domain.InstallmentStatus
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateInstallmentPlanRequest(
    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    @field:NotNull(message = "La fecha es obligatoria.")
    val date: LocalDate,

    @field:NotBlank(message = "El comercio es obligatorio.")
    val merchant: String,

    @field:NotNull(message = "El número de cuotas es obligatorio.")
    @field:Min(2, message = "Debe haber al menos 2 cuotas (una compra de 1 sola exhibición se registra como compra normal).")
    @field:Max(60)
    val installmentCount: Int,

    /** Tasa de interés simple mensual, en porcentaje (ej. 2.5 = 2.5%/mes). 0 = MSI. */
    @field:DecimalMin(value = "0.0", message = "La tasa de interés no puede ser negativa.")
    val interestRate: BigDecimal = BigDecimal.ZERO,

    val categoryId: UUID? = null,
    val description: String? = null,
    val reference: String? = null,
)

data class InstallmentResponse(
    val id: UUID,
    val number: Int,
    val dueDate: LocalDate,
    val amount: BigDecimal,
    val status: InstallmentStatus,
    val paidAt: Instant?,
) {
    companion object {
        fun from(installment: Installment) = InstallmentResponse(
            id = requireNotNull(installment.id),
            number = installment.number,
            dueDate = installment.dueDate,
            amount = installment.amount,
            status = installment.status,
            paidAt = installment.paidAt,
        )
    }
}

data class InstallmentPlanResponse(
    val id: UUID,
    val creditCardId: UUID,
    val transactionId: UUID,
    val planType: InstallmentPlanType,
    val originalAmount: BigDecimal,
    val installmentCount: Int,
    val interestRate: BigDecimal,
    val interestAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val installmentAmount: BigDecimal,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: InstallmentPlanStatus,
    val installmentsPaid: Int,
    val installmentsPending: Int,
    val financedBalance: BigDecimal,
    val nextInstallment: InstallmentResponse?,
    val installments: List<InstallmentResponse>,
) {
    companion object {
        fun from(view: InstallmentPlanView): InstallmentPlanResponse {
            val plan = view.plan
            return InstallmentPlanResponse(
                id = requireNotNull(plan.id),
                creditCardId = plan.creditCardId,
                transactionId = plan.transactionId,
                planType = plan.planType,
                originalAmount = plan.originalAmount,
                installmentCount = plan.installmentCount,
                interestRate = plan.interestRate,
                interestAmount = plan.interestAmount,
                totalAmount = plan.totalAmount,
                installmentAmount = plan.installmentAmount,
                startDate = plan.startDate,
                endDate = plan.endDate,
                status = plan.status,
                installmentsPaid = view.installmentsPaid,
                installmentsPending = view.installmentsPending,
                financedBalance = view.financedBalance,
                nextInstallment = view.nextInstallment?.let(InstallmentResponse::from),
                installments = view.installments.map(InstallmentResponse::from),
            )
        }
    }
}
