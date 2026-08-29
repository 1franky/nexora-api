package com.nexora.api.report.domain

import com.nexora.api.account.domain.AccountService
import com.nexora.api.category.domain.Category
import com.nexora.api.category.domain.CategoryService
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.dashboard.domain.CategoryAmount
import com.nexora.api.dashboard.domain.MonthlyPoint
import com.nexora.api.transaction.domain.Transaction
import com.nexora.api.transaction.domain.TransactionRepository
import com.nexora.api.transaction.domain.TransactionType
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class ReportView(
    val from: LocalDate,
    val to: LocalDate,
    val totalIncome: BigDecimal,
    val totalExpense: BigDecimal,
    val balance: BigDecimal,
    val expensesByCategory: List<CategoryAmount>,
    val incomeByCategory: List<CategoryAmount>,
    val monthlyIncome: List<MonthlyPoint>,
    val monthlyExpense: List<MonthlyPoint>,
    val transactions: List<Transaction>,
)

/**
 * Reportes por rango de fechas libre, a diferencia del dashboard (siempre
 * mes calendario o ventana fija de 6 meses, [com.nexora.api.dashboard.domain.DashboardService]).
 * Filtrable por cuenta y tipo de movimiento. No hay entidades nuevas: todo
 * se calcula del mismo ledger de Transaction; reutiliza CategoryAmount y
 * MonthlyPoint del módulo dashboard para no duplicar estructuras idénticas
 * (reportes avanzados de verdad, plan.md sección 31, quedan post-MVP).
 */
@Service
class ReportService(
    private val accountService: AccountService,
    private val categoryService: CategoryService,
    private val transactionRepository: TransactionRepository,
) {

    fun getReport(userId: UUID, from: LocalDate, to: LocalDate, accountId: UUID?, type: TransactionType?): ReportView {
        if (from.isAfter(to)) {
            throw BusinessRuleException("La fecha inicial no puede ser posterior a la fecha final.")
        }

        val accountIds = if (accountId != null) {
            listOf(requireNotNull(accountService.getOwned(userId, accountId).id))
        } else {
            accountService.listForUser(userId).mapNotNull { it.id }
        }

        val inRange = if (accountIds.isEmpty()) emptyList() else
            transactionRepository.findAllByAccountIdInAndDateBetween(accountIds, from, to)

        val transactions = (if (type != null) inRange.filter { it.type == type } else inRange)
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAt ?: Instant.MIN })

        val totalIncome = transactions.filter { it.type == TransactionType.INCOME }.sumAmount()
        val totalExpense = transactions.filter { it.type == TransactionType.EXPENSE }.sumAmount()

        val categoriesById = categoryService.listForUser(userId).associateBy { it.id }
        val expensesByCategory = groupByCategory(transactions, TransactionType.EXPENSE, categoriesById)
        val incomeByCategory = groupByCategory(transactions, TransactionType.INCOME, categoriesById)

        val months = monthsBetween(from, to)
        val monthlyIncome = monthlyBreakdown(transactions, TransactionType.INCOME, months)
        val monthlyExpense = monthlyBreakdown(transactions, TransactionType.EXPENSE, months)

        return ReportView(
            from = from,
            to = to,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            balance = totalIncome - totalExpense,
            expensesByCategory = expensesByCategory,
            incomeByCategory = incomeByCategory,
            monthlyIncome = monthlyIncome,
            monthlyExpense = monthlyExpense,
            transactions = transactions,
        )
    }

    private fun groupByCategory(
        transactions: List<Transaction>,
        type: TransactionType,
        categoriesById: Map<UUID?, Category>,
    ): List<CategoryAmount> =
        transactions.filter { it.type == type && it.categoryId != null }
            .groupBy { it.categoryId!! }
            .map { (categoryId, list) ->
                CategoryAmount(categoryId, categoriesById[categoryId]?.name ?: "Sin categoría", list.sumAmount())
            }
            .sortedByDescending { it.amount }

    private fun monthlyBreakdown(transactions: List<Transaction>, type: TransactionType, months: List<YearMonth>): List<MonthlyPoint> {
        val byMonth = transactions.filter { it.type == type }
            .groupBy { YearMonth.from(it.date) }
            .mapValues { (_, list) -> list.sumAmount() }
        return months.map { MonthlyPoint(it, byMonth[it] ?: BigDecimal.ZERO) }
    }

    private fun monthsBetween(from: LocalDate, to: LocalDate): List<YearMonth> {
        val start = YearMonth.from(from)
        val end = YearMonth.from(to)
        val months = mutableListOf<YearMonth>()
        var current = start
        while (!current.isAfter(end)) {
            months.add(current)
            current = current.plusMonths(1)
        }
        return months
    }

    private fun List<Transaction>.sumAmount(): BigDecimal = fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
}
