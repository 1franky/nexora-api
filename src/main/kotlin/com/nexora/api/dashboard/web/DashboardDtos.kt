package com.nexora.api.dashboard.web

import com.nexora.api.dashboard.domain.CategoryAmount
import com.nexora.api.dashboard.domain.DashboardView
import com.nexora.api.dashboard.domain.MonthlyPoint
import com.nexora.api.dashboard.domain.UpcomingCardPayment
import com.nexora.api.transaction.web.TransactionResponse
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CategoryAmountResponse(val categoryId: UUID, val categoryName: String, val amount: BigDecimal) {
    companion object {
        fun from(a: CategoryAmount) = CategoryAmountResponse(a.categoryId, a.categoryName, a.amount)
    }
}

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

data class MonthlyPointResponse(val month: String, val amount: BigDecimal) {
    companion object {
        fun from(p: MonthlyPoint) = MonthlyPointResponse(p.month.toString(), p.amount)
    }
}

data class DashboardResponse(
    val month: String,
    val availableBalance: BigDecimal,
    val netWorth: BigDecimal,
    val incomeThisMonth: BigDecimal,
    val expenseThisMonth: BigDecimal,
    val monthlyBalance: BigDecimal,
    val expensesByCategory: List<CategoryAmountResponse>,
    val incomeByCategory: List<CategoryAmountResponse>,
    val creditCardDebt: BigDecimal,
    val availableCredit: BigDecimal,
    val upcomingPayments: List<UpcomingCardPaymentResponse>,
    val activeMsiPlansCount: Int,
    val monthlyInstallmentCommitment: BigDecimal,
    val netWorthEvolution: List<MonthlyPointResponse>,
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
