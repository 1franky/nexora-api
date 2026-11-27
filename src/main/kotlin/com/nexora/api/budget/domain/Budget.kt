package com.nexora.api.budget.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

/**
 * Presupuesto mensual (B15, plan.md sección 22): un tope de gasto en MXN que se
 * reinicia cada mes calendario (el sobrante no se acumula).
 *
 * - Con [categoryId]: cuenta solo el gasto de esa categoría.
 * - Sin [categoryId]: presupuesto **general**, cuenta todo el gasto del mes.
 *
 * Uno por categoría y uno general por usuario. La categoría no se cambia: para
 * otra categoría se borra y se crea otro. Editar el monto aplica al mes en curso
 * y a los siguientes; no guarda historial de montos.
 */
@Entity
@Table(name = "budgets")
class Budget(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "category_id", updatable = false)
    var categoryId: UUID?,

    /** En MXN (moneda base), igual que los totales del dashboard. */
    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal,

) : BaseEntity()
