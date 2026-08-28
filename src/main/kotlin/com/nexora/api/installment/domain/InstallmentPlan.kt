package com.nexora.api.installment.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class InstallmentPlanType {
    /** Meses sin intereses. */
    MSI,

    /** Meses con intereses. */
    MCI,
}

enum class InstallmentPlanStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
}

/**
 * Un plan de meses sin/con intereses (plan.md, sección 6) para una compra
 * con tarjeta. La compra original se registra como una única
 * CREDIT_CARD_PURCHASE por [totalAmount] (monto original + interés, si
 * aplica) — el saldo/crédito disponible de la tarjeta ya refleja el total
 * adeudado desde el día 1, igual que en un estado de cuenta real. Este plan
 * y sus [Installment] son, sobre esa base, únicamente el calendario y
 * seguimiento de cuotas (no vuelven a mover el saldo).
 *
 * El interés se calcula como tasa simple mensual sobre el monto original
 * (no amortización/anualidad): `interestAmount = originalAmount *
 * (interestRate/100) * installmentCount`. Es una simplificación deliberada
 * para este MVP.
 */
@Entity
@Table(name = "installment_plans")
class InstallmentPlan(

    @Column(name = "credit_card_id", nullable = false)
    var creditCardId: UUID,

    @Column(name = "transaction_id", nullable = false)
    var transactionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 10)
    var planType: InstallmentPlanType,

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4)
    var originalAmount: BigDecimal,

    @Column(name = "installment_count", nullable = false)
    var installmentCount: Int,

    @Column(name = "interest_rate", nullable = false, precision = 9, scale = 4)
    var interestRate: BigDecimal,

    @Column(name = "interest_amount", nullable = false, precision = 19, scale = 4)
    var interestAmount: BigDecimal,

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    var totalAmount: BigDecimal,

    @Column(name = "installment_amount", nullable = false, precision = 19, scale = 4)
    var installmentAmount: BigDecimal,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    var endDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,

) : BaseEntity()
