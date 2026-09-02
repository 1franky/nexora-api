package com.nexora.api.dashboard.web

import com.nexora.api.dashboard.domain.CategoryAmount
import com.nexora.api.dashboard.domain.DashboardView
import com.nexora.api.dashboard.domain.MonthlyPoint
import com.nexora.api.dashboard.domain.UpcomingCardPayment
import com.nexora.api.transaction.web.TransactionResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CategoryAmountResponse(val categoryId: UUID, val categoryName: String, val amount: BigDecimal) {
    companion object {
        fun from(a: CategoryAmount) = CategoryAmountResponse(a.categoryId, a.categoryName, a.amount)
    }
}

@Schema(description = "expectedPayment es solo lo que corresponde pagar en dueDate (compras normales sin pagar + la cuota del corte de los planes MSI/MCI), no la deuda total de la tarjeta.")
data class UpcomingCardPaymentResponse(
    val creditCardId: UUID,
    val creditCardName: String,
    val dueDate: LocalDate,
    val expectedPayment: BigDecimal,
) {
    companion object {
        fun from(p: UpcomingCardPayment) = UpcomingCardPaymentResponse(p.creditCardId, p.creditCardName, p.dueDate, p.expectedPayment)
    }
}

@Schema(description = "Un punto en una serie de tiempo mensual (últimos 6 meses).")
data class MonthlyPointResponse(
    @field:Schema(description = "Formato yyyy-MM.", example = "2026-09")
    val month: String,
    val amount: BigDecimal,
) {
    companion object {
        fun from(p: MonthlyPoint) = MonthlyPointResponse(p.month.toString(), p.amount)
    }
}

data class DashboardResponse(
    @field:Schema(description = "Mes de las métricas mensuales, formato yyyy-MM.", example = "2026-09")
    val month: String,
    @field:Schema(description = "Suma de las cuentas con includeInAvailableBalance=true, convertida a MXN.")
    val availableBalance: BigDecimal,
    @field:Schema(description = "Suma de las cuentas con includeInNetWorth=true, convertida a MXN.")
    val netWorth: BigDecimal,
    val incomeThisMonth: BigDecimal,
    val expenseThisMonth: BigDecimal,
    val monthlyBalance: BigDecimal,
    val expensesByCategory: List<CategoryAmountResponse>,
    val incomeByCategory: List<CategoryAmountResponse>,
    @field:Schema(description = "Suma de currentDebt de todas las tarjetas, convertida a MXN — deuda total, no lo que toca pagar este corte (ver upcomingPayments).")
    val creditCardDebt: BigDecimal,
    val availableCredit: BigDecimal,
    @field:Schema(description = "Una entrada por tarjeta con deuda > 0, ordenadas por dueDate.")
    val upcomingPayments: List<UpcomingCardPaymentResponse>,
    val activeMsiPlansCount: Int,
    @field:Schema(description = "Suma de installmentAmount de todos los planes MSI/MCI activos del usuario (todas las tarjetas).")
    val monthlyInstallmentCommitment: BigDecimal,
    @field:Schema(description = "Patrimonio neto al cierre de cada uno de los últimos 6 meses.")
    val netWorthEvolution: List<MonthlyPointResponse>,
    @field:Schema(description = "Gasto total de cada uno de los últimos 6 meses.")
    val expenseEvolution: List<MonthlyPointResponse>,
    val recentTransactions: List<TransactionResponse>,
) {
    companion object {
        fun from(view: DashboardView) = DashboardResponse(
            month = view.month.toString(),
            availableBalance = view.availableBalance,
            netWorth = view.netWorth,
            incomeThisMonth = view.incomeThisMonth,
            expenseThisMonth = view.expenseThisMonth,
            monthlyBalance = view.monthlyBalance,
            expensesByCategory = view.expensesByCategory.map(CategoryAmountResponse::from),
            incomeByCategory = view.incomeByCategory.map(CategoryAmountResponse::from),
            creditCardDebt = view.creditCardDebt,
            availableCredit = view.availableCredit,
            upcomingPayments = view.upcomingPayments.map(UpcomingCardPaymentResponse::from),
            activeMsiPlansCount = view.activeMsiPlansCount,
            monthlyInstallmentCommitment = view.monthlyInstallmentCommitment,
            netWorthEvolution = view.netWorthEvolution.map(MonthlyPointResponse::from),
            expenseEvolution = view.expenseEvolution.map(MonthlyPointResponse::from),
            recentTransactions = view.recentTransactions.map(TransactionResponse::from),
        )
    }
}
