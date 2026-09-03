package com.nexora.api.dashboard.domain

import com.nexora.api.account.domain.Account
import com.nexora.api.account.domain.AccountService
import com.nexora.api.category.domain.Category
import com.nexora.api.category.domain.CategoryService
import com.nexora.api.creditcard.domain.BillingCycleCalculator
import com.nexora.api.creditcard.domain.CreditCardService
import com.nexora.api.creditcard.domain.CreditCardView
import com.nexora.api.exchangerate.domain.BASE_CURRENCY
import com.nexora.api.exchangerate.domain.ExchangeRateService
import com.nexora.api.installment.domain.Installment
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.installment.domain.InstallmentPlanType
import com.nexora.api.transaction.domain.Transaction
import com.nexora.api.transaction.domain.TransactionRepository
import com.nexora.api.transaction.domain.TransactionType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
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

/**
 * Qué cuenta como "gasto" para expenseThisMonth/expensesByCategory/expenseEvolution:
 * EXPENSE (cuenta de débito/efectivo) y CREDIT_CARD_PURCHASE (compra de
 * tarjeta, ver TransactionService.recordCreditCardPurchase — se registra por
 * el monto completo el día de la compra, igual que currentDebt). CREDIT_CARD_PAYMENT
 * queda fuera: es solo mover dinero para cubrir una deuda ya contada aquí como
 * gasto en el momento de la compra, no gasto nuevo.
 */
private val EXPENSE_TYPES = setOf(TransactionType.EXPENSE, TransactionType.CREDIT_CARD_PURCHASE)

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
    private val exchangeRateService: ExchangeRateService,
    private val billingCycleCalculator: BillingCycleCalculator,
) {

    fun getDashboard(userId: UUID, month: YearMonth, recentTransactionsLimit: Int): DashboardView {
        val accounts = accountService.listForUser(userId)
        val accountIds = accounts.mapNotNull { it.id }
        val netWorthAccounts = accounts.filter { it.includeInNetWorth }
        val balanceSummary = accountService.getBalanceSummary(userId)

        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val monthTransactions = transactionsFor(accountIds, monthStart, monthEnd)

        val incomeThisMonth = monthTransactions.filter { it.type == TransactionType.INCOME }.sumAmount()
        val expenseThisMonth = monthTransactions.filter { it.type in EXPENSE_TYPES }.sumAmount()

        val categoriesById = categoryService.listForUser(userId).associateBy { it.id }
        val expensesByCategory = groupByCategory(monthTransactions, EXPENSE_TYPES, categoriesById)
        val incomeByCategory = groupByCategory(monthTransactions, setOf(TransactionType.INCOME), categoriesById)

        // creditCardDebt/availableCredit se agregan en MXN igual que disponible/patrimonio
        // (ver AccountService.getBalanceSummary): una tarjeta en otra moneda se convierte,
        // no se suma tal cual. upcomingPayments sí queda en la moneda propia de cada
        // tarjeta (expectedPayment), porque ahí se muestra junto al nombre de esa tarjeta,
        // no agregado con las demás.
        val creditCards = creditCardService.listForUser(userId)
        val creditCardDebt = creditCards.fold(BigDecimal.ZERO) { acc, c -> acc + c.currentDebt.toBase(c.account.currency) }
        val availableCredit = creditCards.fold(BigDecimal.ZERO) { acc, c -> acc + c.availableCredit.toBase(c.account.currency) }
        val pendingInstallmentsByCard = installmentPlanService.pendingInstallmentsByCard(userId)
        val upcomingPayments = creditCards
            .filter { it.currentDebt > BigDecimal.ZERO }
            .map { card -> upcomingPaymentFor(card, pendingInstallmentsByCard[card.creditCard.id] ?: emptyList()) }
            .sortedBy { it.dueDate }

        val activePlans = installmentPlanService.listActivePlansForUser(userId)
        val activeMsiPlansCount = activePlans.count { it.planType == InstallmentPlanType.MSI }
        val monthlyInstallmentCommitment = activePlans.fold(BigDecimal.ZERO) { acc, p -> acc + p.installmentAmount }

        val netWorthEvolution = netWorthEvolution(netWorthAccounts, balanceSummary.netWorth)
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

    /**
     * currentDebt es la deuda total de la tarjeta (una compra a MSI/MCI se
     * registra por su monto completo desde el día 1, ver
     * InstallmentPlanService.create; una compra de contado normal también
     * se registra completa el día de la compra), no lo que toca pagar en
     * el próximo corte. Dos cosas quedan fuera de "próximo pago":
     * - Las cuotas MSI/MCI pendientes cuya fecha límite es posterior al
     *   pago que se está calculando (a una compra de $12,000 a 12 MSI
     *   solo le corresponde $1,000 este corte).
     * - Las compras de contado (sin plan) fechadas después del corte de
     *   este ciclo — ya pertenecen al ciclo siguiente, se facturan hasta
     *   el próximo corte de ese (con corte 27, una compra el día 29 no
     *   entra en el pago que vence este mes).
     *
     * Además, [billingCycleCalculator] decide *cuál* ciclo es el
     * relevante: si hoy cae en el período de gracia de un ciclo que ya
     * cerró (después de su corte, pero antes de su propia fecha límite de
     * pago), ese es el que urge — no el que cierra más adelante. Si ese
     * ciclo de gracia no tiene nada pendiente de verdad (p.ej. una tarjeta
     * nueva sin actividad todavía en ese ciclo hipotético — todo lo debido
     * es más nuevo que su corte), se usa el ciclo que sigue en su lugar.
     */
    private fun upcomingPaymentFor(card: CreditCardView, installments: List<Installment>): UpcomingCardPayment {
        val today = LocalDate.now()
        val cardAccountId = requireNotNull(card.account.id)
        val cycle = billingCycleCalculator.currentPaymentCycle(card.creditCard.closingDay, card.creditCard.paymentDueDay, today)

        // El corte de gracia siempre es <= el corte hacia adelante — una sola query cubre ambos candidatos.
        val earliestClosing = minOf(cycle.closingDate, card.nextClosingDate)
        val purchasesSinceEarliestClosing = transactionRepository
            .findAllByAccountIdAndTypeAndDateAfter(cardAccountId, TransactionType.CREDIT_CARD_PURCHASE, earliestClosing)
            .filterNot { installmentPlanService.isLinkedToPlan(requireNotNull(it.id)) }

        fun expectedAsOf(closingDate: LocalDate, paymentDueDate: LocalDate): BigDecimal {
            val futurePurchases = purchasesSinceEarliestClosing.filter { it.date > closingDate }.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
            val futureInstallments = installments.filter { it.dueDate > paymentDueDate }.fold(BigDecimal.ZERO) { acc, i -> acc + i.amount }
            return (card.currentDebt - futurePurchases - futureInstallments).max(BigDecimal.ZERO)
        }

        if (cycle.isGracePeriod) {
            val graceExpected = expectedAsOf(cycle.closingDate, cycle.paymentDueDate)
            if (graceExpected > BigDecimal.ZERO) {
                return UpcomingCardPayment(requireNotNull(card.creditCard.id), card.creditCard.name, cycle.paymentDueDate, graceExpected)
            }
        }
        val forwardExpected = expectedAsOf(card.nextClosingDate, card.nextPaymentDueDate)
        return UpcomingCardPayment(requireNotNull(card.creditCard.id), card.creditCard.name, card.nextPaymentDueDate, forwardExpected)
    }

    private fun transactionsFor(accountIds: List<UUID>, start: LocalDate, end: LocalDate): List<Transaction> =
        if (accountIds.isEmpty()) emptyList() else transactionRepository.findAllByAccountIdInAndDateBetween(accountIds, start, end)

    private fun groupByCategory(
        transactions: List<Transaction>,
        types: Set<TransactionType>,
        categoriesById: Map<UUID?, Category>,
    ): List<CategoryAmount> =
        transactions.filter { it.type in types && it.categoryId != null }
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
     *
     * `currentNetWorth` ya viene en MXN (ver AccountService.getBalanceSummary),
     * así que cada balanceEffect que se le resta también se convierte a MXN
     * con la moneda de su propia cuenta — si no, una cuenta en otra moneda
     * desalinearía los puntos pasados del patrimonio actual.
     */
    private fun netWorthEvolution(netWorthAccounts: List<Account>, currentNetWorth: BigDecimal): List<MonthlyPoint> {
        val today = LocalDate.now()
        val cutoffs = monthWindow(today).map { it to minOf(it.atEndOfMonth(), today) }
        if (netWorthAccounts.isEmpty()) return cutoffs.map { (yearMonth, _) -> MonthlyPoint(yearMonth, BigDecimal.ZERO) }
        val accountIds = netWorthAccounts.mapNotNull { it.id }
        val currencyByAccountId = netWorthAccounts.associate { it.id to it.currency }
        val earliestCutoff = cutoffs.first().second
        val laterTransactions = transactionRepository.findAllByAccountIdInAndDateAfter(accountIds, earliestCutoff)
        return cutoffs.map { (yearMonth, cutoff) ->
            val effectAfterCutoff = laterTransactions.filter { it.date > cutoff }
                .fold(BigDecimal.ZERO) { acc, t -> acc + t.balanceEffect.toBase(currencyByAccountId[t.accountId] ?: BASE_CURRENCY) }
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
        val expensesByMonth = transactions.filter { it.type in EXPENSE_TYPES }
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

    /** Convierte a MXN (ver ExchangeRateService.rateToBase) con la misma precisión de dinero que el resto de la app. */
    private fun BigDecimal.toBase(currency: String): BigDecimal =
        (this * exchangeRateService.rateToBase(currency)).setScale(4, RoundingMode.HALF_UP)
}
