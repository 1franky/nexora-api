package com.nexora.api.installment.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class InstallmentStatus {
    PENDING,
    PAID,
}

/**
 * Una cuota individual de un [InstallmentPlan]. Marcarla como pagada es
 * solo seguimiento/reporte (plan.md, sección 6.3: cuotas pagadas/pendientes,
 * saldo financiado, próxima cuota) — no vuelve a mover el saldo de la
 * tarjeta, eso ya ocurrió con la compra original y ocurre de nuevo con cada
 * pago mensual real (POST /credit-cards/{id}/payments).
 */
@Entity
@Table(name = "installments")
class Installment(

    @Column(name = "installment_plan_id", nullable = false)
    var installmentPlanId: UUID,

    @Column(nullable = false)
    var number: Int,

    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate,

    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: InstallmentStatus = InstallmentStatus.PENDING,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

) : BaseEntity()
