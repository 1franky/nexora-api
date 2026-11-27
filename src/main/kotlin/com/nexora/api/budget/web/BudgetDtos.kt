package com.nexora.api.budget.web

import com.nexora.api.budget.domain.BudgetProgress
import com.nexora.api.budget.domain.BudgetStatus
import com.nexora.api.category.domain.CategoryStatus
import com.nexora.api.exchangerate.domain.BASE_CURRENCY
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BudgetRequest(
    @field:Schema(description = "Categoría de gasto activa. null = presupuesto general (todo el gasto del mes).")
    val categoryId: UUID? = null,

    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    @field:Schema(description = "Tope mensual en MXN.")
    val amount: BigDecimal,
)

@Schema(description = "Solo se cambia el monto; para otra categoría se borra el presupuesto y se crea otro.")
data class BudgetAmountRequest(
    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    @field:Schema(description = "Tope mensual en MXN.")
    val amount: BigDecimal,
)

data class BudgetResponse(
    val id: UUID,
    @field:Schema(description = "null en el presupuesto general.")
    val categoryId: UUID?,
    @field:Schema(description = "null en el presupuesto general.")
    val categoryName: String?,
    @field:Schema(description = "true si la categoría se archivó después; el presupuesto sigue contando.")
    val categoryArchived: Boolean,
    val amount: BigDecimal,
    @field:Schema(description = "Siempre MXN: el gasto en otras monedas se convierte.")
    val currency: String,
    @field:Schema(description = "Mes al que se refieren spent/remaining/percentUsed/status (yyyy-MM).")
    val month: String,
    val spent: BigDecimal,
    @field:Schema(description = "Negativo cuando ya se pasó.")
    val remaining: BigDecimal,
    val percentUsed: BigDecimal,
    @field:Schema(description = "OK (< 80 %), NEAR_LIMIT (80–100 %) o EXCEEDED (> 100 %).")
    val status: BudgetStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(p: BudgetProgress) = BudgetResponse(
            id = requireNotNull(p.budget.id),
            categoryId = p.budget.categoryId,
            categoryName = p.category?.name,
            categoryArchived = p.category?.status == CategoryStatus.ARCHIVED,
            amount = p.budget.amount,
            currency = BASE_CURRENCY,
            month = p.month.toString(),
            spent = p.spent,
            remaining = p.remaining,
            percentUsed = p.percentUsed,
            status = p.status,
            createdAt = requireNotNull(p.budget.createdAt),
        )
    }
}
