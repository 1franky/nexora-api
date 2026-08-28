package com.nexora.api.dashboard.domain

import com.nexora.api.account.domain.AccountService
import com.nexora.api.category.domain.Category
import com.nexora.api.category.domain.CategoryService
import com.nexora.api.creditcard.domain.CreditCardService
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.installment.domain.InstallmentPlanType
import com.nexora.api.transaction.domain.Transaction
import com.nexora.api.transaction.domain.TransactionRepository
import com.nexora.api.transaction.domain.TransactionType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class CategoryAmount(val categoryId: UUID, val categoryName: String, val amount: BigDecimal)

data class UpcomingCardPayment(
    val creditCardId: UUID,
    val creditCardName: String,
    val dueDate: LocalDate,
    val expectedPayment: BigDecimal,
)

data class MonthlyPoint(val month: YearMonth, val amount: BigDecimal)

data class DashboardView(
    val month: YearMonth,
    val availableBalance: BigDecimal,
    val netWorth: BigDecimal,
    val incomeThisMonth: BigDecimal,
    val expenseThisMonth: BigDecimal,
    val monthlyBalance: BigDecimal,
    val expensesByCategory: List<CategoryAmount>,
    val incomeByCategory: List<CategoryAmount>,
    val creditCardDebt: BigDecimal,
    val availableCredit: BigDecimal,
    val upcomingPayments: List<UpcomingCardPayment>,
    val activeMsiPlansCount: Int,
    val monthlyInstallmentCommitment: BigDecimal,
    val netWorthEvolution: List<MonthlyPoint>,
    val expenseEvolution: List<MonthlyPoint>,
    val recentTransactions: List<Transaction>,
)

/**
 * Agrega las métricas del dashboard (plan.md, sección 10) a partir de lo ya
 * construido en B2-B4: no hay entidades ni tablas nuevas, todo se calcula a
 * partir del ledger de Transaction, las cuentas y las tarjetas. Metas de
 * ahorro y reportes avanzados quedan fuera (plan.md, sección 31, son
 * explícitamente posteriores al MVP); la configuración de widgets
 * (agregar/quitar/reordenar) es responsabilidad de la Web (sección 19, W7),
 * este servicio solo expone los datos.
 */
@Service
class DashboardService(
    private val accountService: AccountService,
    private val categoryService: CategoryService,
    private val transactionRepository: TransactionRepository,
    private val creditCardService: CreditCardService,
    private val installmentPlanService: InstallmentPlanService,
) {

    fun getDashboard(userId: UUID, month: YearMonth, recentTransactionsLimit: Int): DashboardView {
        val accounts = accountService.listForUser(userId)
        val accountIds = accounts.mapNotNull { it.id }
        val netWorthAccountIds = accounts.filter { it.includeInNetWorth }.mapNotNull { it.id }
        val balanceSummary = accountService.getBalanceSummary(userId)

        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val monthTransactions = transactionsFor(accountIds, monthStart, monthEnd)

        val incomeThisMonth = monthTransactions.filter { it.type == TransactionType.INCOME }.sumAmount()
        val expenseThisMonth = monthTransactions.filter { it.type == TransactionType.EXPENSE }.sumAmount()

        val categoriesById = categoryService.listForUser(userId).associateBy { it.id }
        val expensesByCategory = groupByCategory(monthTransactions, TransactionType.EXPENSE, categoriesById)
        val incomeByCategory = groupByCategory(monthTransactions, TransactionType.INCOME, categoriesById)

        val creditCards = creditCardService.listForUser(userId)
        val creditCardDebt = creditCards.fold(BigDecimal.ZERO) { acc, c -> acc + c.currentDebt }
        val availableCredit = creditCards.fold(BigDecimal.ZERO) { acc, c -> acc + c.availableCredit }
        val upcomingPayments = creditCards
            .filter { it.currentDebt > BigDecimal.ZERO }
            .sortedBy { it.nextPaymentDueDate }
            .map { UpcomingCardPayment(requireNotNull(it.creditCard.id), it.creditCard.name, it.nextPaymentDueDate, it.currentDebt) }

        val activePlans = installmentPlanService.listActivePlansForUser(userId)
        val activeMsiPlansCount = activePlans.count { it.planType == InstallmentPlanType.MSI }
        val monthlyInstallmentCommitment = activePlans.fold(BigDecimal.ZERO) { acc, p -> acc + p.installmentAmount }

        val netWorthEvolution = netWorthEvolution(netWorthAccountIds, balanceSummary.netWorth)
        val expenseEvolution = expenseEvolution(accountIds)

        val recentTransactions = if (accountIds.isEmpty()) emptyList() else transactionRepository.findAllByAccountIdIn(
            accountIds,
            PageRequest.of(0, recentTransactionsLimit, Sort.by(Sort.Direction.DESC, "date", "createdAt")),
        )

        return DashboardView(
            month = month,
            availableBalance = balanceSummary.availableBalance,
            netWorth = balanceSummary.netWorth,
            incomeThisMonth = incomeThisMonth,
            expenseThisMonth = expenseThisMonth,
            monthlyBalance = incomeThisMonth - expenseThisMonth,
            expensesByCategory = expensesByCategory,
            incomeByCategory = incomeByCategory,
            creditCardDebt = creditCardDebt,
            availableCredit = availableCredit,
            upcomingPayments = upcomingPayments,
            activeMsiPlansCount = activeMsiPlansCount,
            monthlyInstallmentCommitment = monthlyInstallmentCommitment,
            netWorthEvolution = netWorthEvolution,
            expenseEvolution = expenseEvolution,
            recentTransactions = recentTransactions,
        )
    }

    private fun transactionsFor(accountIds: List<UUID>, start: LocalDate, end: LocalDate): List<Transaction> =
        if (accountIds.isEmpty()) emptyList() else transactionRepository.findAllByAccountIdInAndDateBetween(accountIds, start, end)

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

    /**
     * Patrimonio neto al cierre de cada uno de los últimos 6 meses,
     * "rebobinando" desde el patrimonio actual: en vez de reconstruir hacia
     * adelante (lo que requeriría un saldo inicial fechado, y el saldo de
     * apertura de una cuenta no lo está), se resta a `currentNetWorth` el
     * efecto de las transacciones posteriores a cada fecha de corte —
     * matemáticamente equivalente y no depende de cuándo se creó la cuenta.
     */
    private fun netWorthEvolution(netWorthAccountIds: List<UUID>, currentNetWorth: BigDecimal): List<MonthlyPoint> {
        val today = LocalDate.now()
        val cutoffs = monthWindow(today).map { it to minOf(it.atEndOfMonth(), today) }
        if (netWorthAccountIds.isEmpty()) return cutoffs.map { (yearMonth, _) -> MonthlyPoint(yearMonth, BigDecimal.ZERO) }
        val earliestCutoff = cutoffs.first().second
        val laterTransactions = transactionRepository.findAllByAccountIdInAndDateAfter(netWorthAccountIds, earliestCutoff)
        return cutoffs.map { (yearMonth, cutoff) ->
            val effectAfterCutoff = laterTransactions.filter { it.date > cutoff }
                .fold(BigDecimal.ZERO) { acc, t -> acc + t.balanceEffect }
            MonthlyPoint(yearMonth, currentNetWorth - effectAfterCutoff)
        }
    }

    /** Gasto total de cada uno de los últimos 6 meses, calculado directamente del ledger (sin rebobinar). */
    private fun expenseEvolution(accountIds: List<UUID>): List<MonthlyPoint> {
        val today = LocalDate.now()
        val months = monthWindow(today)
        if (accountIds.isEmpty()) return months.map { MonthlyPoint(it, BigDecimal.ZERO) }
        val windowStart = months.first().atDay(1)
        val transactions = transactionRepository.findAllByAccountIdInAndDateBetween(accountIds, windowStart, today)
        val expensesByMonth = transactions.filter { it.type == TransactionType.EXPENSE }
            .groupBy { YearMonth.from(it.date) }
            .mapValues { (_, list) -> list.sumAmount() }
        return months.map { MonthlyPoint(it, expensesByMonth[it] ?: BigDecimal.ZERO) }
    }

    /** Los últimos 6 meses, del más antiguo al más reciente (incluyendo el mes actual). */
    private fun monthWindow(today: LocalDate): List<YearMonth> {
        val current = YearMonth.from(today)
        return (5 downTo 0).map { offset -> current.minusMonths(offset.toLong()) }
    }

    private fun List<Transaction>.sumAmount(): BigDecimal = fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
}
