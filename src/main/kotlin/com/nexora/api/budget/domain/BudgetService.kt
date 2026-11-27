package com.nexora.api.budget.domain

import com.nexora.api.account.domain.AccountService
import com.nexora.api.category.domain.Category
import com.nexora.api.category.domain.CategoryService
import com.nexora.api.category.domain.CategoryStatus
import com.nexora.api.category.domain.CategoryType
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.ConflictException
import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.dashboard.domain.EXPENSE_TYPES
import com.nexora.api.exchangerate.domain.BASE_CURRENCY
import com.nexora.api.exchangerate.domain.ExchangeRateService
import com.nexora.api.transaction.domain.Transaction
import com.nexora.api.transaction.domain.TransactionRepository
import com.nexora.api.transaction.domain.TransactionStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.UUID

/** Porcentaje del monto a partir del cual el presupuesto está «cerca del límite». */
val BUDGET_NEAR_LIMIT_PERCENT = BigDecimal(80)

/**
 * - OK: menos del 80 % gastado.
 * - NEAR_LIMIT: del 80 % al 100 % (justo el monto todavía no es pasarse).
 * - EXCEEDED: más que el monto.
 */
enum class BudgetStatus {
    OK,
    NEAR_LIMIT,
    EXCEEDED,
}

/**
 * Un presupuesto con lo gastado en [month]. [category] es null en el presupuesto general.
 * [remaining] es negativo cuando ya se pasó.
 */
data class BudgetProgress(
    val budget: Budget,
    val category: Category?,
    val month: YearMonth,
    val spent: BigDecimal,
    val remaining: BigDecimal,
    val percentUsed: BigDecimal,
    val status: BudgetStatus,
)

/**
 * Presupuestos mensuales (B15, plan.md sección 22).
 *
 * El gasto del mes se calcula del mismo ledger que el dashboard ([EXPENSE_TYPES]: gastos de
 * débito/efectivo y compras con tarjeta, estas por el monto completo el día de la compra
 * aunque sean a MSI/MCI), sin movimientos anulados, y convertido a MXN con el tipo de cambio
 * de cada cuenta. Las notificaciones las genera
 * [com.nexora.api.notification.domain.NotificationService] a partir de [progressForUser].
 */
@Service
class BudgetService(
    private val budgetRepository: BudgetRepository,
    private val categoryService: CategoryService,
    private val accountService: AccountService,
    private val transactionRepository: TransactionRepository,
    private val exchangeRateService: ExchangeRateService,
) {

    @Transactional
    fun create(userId: UUID, categoryId: UUID?, amount: BigDecimal): Budget {
        requirePositive(amount)
        if (categoryId == null) {
            if (budgetRepository.existsByUserIdAndCategoryIdIsNull(userId)) {
                throw ConflictException("Ya tienes un presupuesto general.")
            }
        } else {
            val category = categoryService.getOwned(userId, categoryId)
            if (category.type != CategoryType.EXPENSE) {
                throw BusinessRuleException("Solo las categorías de gasto llevan presupuesto.")
            }
            if (category.status != CategoryStatus.ACTIVE) {
                throw BusinessRuleException("La categoría está archivada.")
            }
            if (budgetRepository.existsByUserIdAndCategoryId(userId, categoryId)) {
                throw ConflictException("Ya hay un presupuesto para esa categoría.")
            }
        }
        return budgetRepository.save(Budget(userId = userId, categoryId = categoryId, amount = amount))
    }

    fun getOwned(userId: UUID, budgetId: UUID): Budget =
        budgetRepository.findByIdAndUserId(budgetId, userId)
            ?: throw NotFoundException("Presupuesto no encontrado.")

    @Transactional
    fun updateAmount(userId: UUID, budgetId: UUID, amount: BigDecimal): Budget {
        requirePositive(amount)
        val budget = getOwned(userId, budgetId)
        budget.amount = amount
        return budgetRepository.save(budget)
    }

    /** Borrado físico: un presupuesto no tiene movimientos propios; las notificaciones ya enviadas se quedan. */
    @Transactional
    fun delete(userId: UUID, budgetId: UUID) {
        budgetRepository.delete(getOwned(userId, budgetId))
    }

    fun progressOf(userId: UUID, budgetId: UUID, month: YearMonth): BudgetProgress =
        progress(userId, listOf(getOwned(userId, budgetId)), month).single()

    /** El general primero y luego por nombre de categoría (mismo orden que [CategoryService.listForUser]). */
    fun progressForUser(userId: UUID, month: YearMonth): List<BudgetProgress> =
        progress(userId, budgetRepository.findAllByUserId(userId), month)

    private fun progress(userId: UUID, budgets: List<Budget>, month: YearMonth): List<BudgetProgress> {
        if (budgets.isEmpty()) return emptyList()
        val categories = categoryService.listForUser(userId)
        val categoryOrder = categories.mapIndexed { index, c -> c.id to index }.toMap()
        val categoriesById = categories.associateBy { it.id }
        val expenses = monthExpensesInBase(userId, month)
        val total = expenses.fold(BigDecimal.ZERO) { acc, e -> acc + e.second }
        val byCategory = expenses.filter { it.first != null }
            .groupBy({ it.first!! }, { it.second })
            .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO, BigDecimal::add) }

        return budgets
            .sortedBy { it.categoryId?.let { id -> (categoryOrder[id] ?: Int.MAX_VALUE) + 1 } ?: 0 }
            .map { budget ->
                val spent = (if (budget.categoryId == null) total else byCategory[budget.categoryId] ?: BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP)
                val percent = spent.multiply(BigDecimal(100)).divide(budget.amount, 1, RoundingMode.HALF_UP)
                BudgetProgress(
                    budget = budget,
                    category = budget.categoryId?.let { categoriesById[it] },
                    month = month,
                    spent = spent,
                    remaining = budget.amount.setScale(2, RoundingMode.HALF_UP) - spent,
                    percentUsed = percent,
                    status = when {
                        spent > budget.amount -> BudgetStatus.EXCEEDED
                        percent >= BUDGET_NEAR_LIMIT_PERCENT -> BudgetStatus.NEAR_LIMIT
                        else -> BudgetStatus.OK
                    },
                )
            }
    }

    /** (categoría, monto en MXN) de cada gasto del mes. */
    private fun monthExpensesInBase(userId: UUID, month: YearMonth): List<Pair<UUID?, BigDecimal>> {
        val accounts = accountService.listForUser(userId)
        if (accounts.isEmpty()) return emptyList()
        val currencyByAccount = accounts.associate { it.id to it.currency }
        val rates = mutableMapOf<String, BigDecimal>()
        return transactionRepository
            .findAllByAccountIdInAndDateBetween(accounts.mapNotNull { it.id }, month.atDay(1), month.atEndOfMonth())
            .filter { it.type in EXPENSE_TYPES && it.status == TransactionStatus.POSTED }
            .map { t: Transaction ->
                val currency = currencyByAccount[t.accountId] ?: BASE_CURRENCY
                val rate = rates.getOrPut(currency) { exchangeRateService.rateToBase(currency) }
                t.categoryId to t.amount * rate
            }
    }

    private fun requirePositive(amount: BigDecimal) {
        if (amount <= BigDecimal.ZERO) throw BusinessRuleException("El monto debe ser mayor a cero.")
    }
}
