package com.nexora.api.report.web

import com.nexora.api.dashboard.web.CategoryAmountResponse
import com.nexora.api.dashboard.web.MonthlyPointResponse
import com.nexora.api.report.domain.ReportView
import com.nexora.api.transaction.web.TransactionResponse
import java.math.BigDecimal
import java.time.LocalDate

data class ReportResponse(
    val from: LocalDate,
    val to: LocalDate,
    val totalIncome: BigDecimal,
    val totalExpense: BigDecimal,
    val balance: BigDecimal,
    val expensesByCategory: List<CategoryAmountResponse>,
    val incomeByCategory: List<CategoryAmountResponse>,
    val monthlyIncome: List<MonthlyPointResponse>,
    val monthlyExpense: List<MonthlyPointResponse>,
    val transactions: List<TransactionResponse>,
) {
    companion object {
        fun from(view: ReportView) = ReportResponse(
            from = view.from,
            to = view.to,
            totalIncome = view.totalIncome,
            totalExpense = view.totalExpense,
            balance = view.balance,
            expensesByCategory = view.expensesByCategory.map(CategoryAmountResponse::from),
            incomeByCategory = view.incomeByCategory.map(CategoryAmountResponse::from),
            monthlyIncome = view.monthlyIncome.map(MonthlyPointResponse::from),
            monthlyExpense = view.monthlyExpense.map(MonthlyPointResponse::from),
            transactions = view.transactions.map(TransactionResponse::from),
        )
    }
}
