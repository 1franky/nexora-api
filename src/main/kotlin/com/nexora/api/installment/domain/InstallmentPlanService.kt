package com.nexora.api.installment.domain

import com.nexora.api.audit.domain.AuditEventType
import com.nexora.api.audit.domain.AuditLogService
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.creditcard.domain.BillingCycleCalculator
import com.nexora.api.creditcard.domain.CreditCardService
import com.nexora.api.transaction.domain.Transaction
import com.nexora.api.transaction.domain.TransactionService
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Vista de un plan con sus cuotas, los valores calculados (plan.md, sección
 * 6.3) y la Transaction de la compra original — de ahí salen comercio,
 * categoría, descripción y referencia, que no viven en el plan.
 */
data class InstallmentPlanView(
    val plan: InstallmentPlan,
    val installments: List<Installment>,
    val transaction: Transaction,
) {
    val installmentsPaid: Int get() = installments.count { it.status == InstallmentStatus.PAID }
    val installmentsPending: Int get() = installments.count { it.status == InstallmentStatus.PENDING }
    val financedBalance: BigDecimal
        get() = installments.filter { it.status == InstallmentStatus.PENDING }
            .fold(BigDecimal.ZERO) { acc, i -> acc + i.amount }
    val nextInstallment: Installment?
        get() = installments.filter { it.status == InstallmentStatus.PENDING }.minByOrNull { it.dueDate }
}

private const val MIN_INSTALLMENTS = 2
private const val MAX_INSTALLMENTS = 60

@Service
class InstallmentPlanService(
    private val installmentPlanRepository: InstallmentPlanRepository,
    private val installmentRepository: InstallmentRepository,
    private val creditCardService: CreditCardService,
    private val transactionService: TransactionService,
    private val billingCycleCalculator: BillingCycleCalculator,
    private val auditLogService: AuditLogService,
) {

    /**
     * Crea una compra financiada a MSI (interestRate = 0) o MCI
     * (interestRate > 0). Registra la compra original por el monto total
     * (original + interés) como una única CREDIT_CARD_PURCHASE — el saldo
     * de la tarjeta ya refleja el total adeudado desde el día 1 — y genera
     * el calendario de cuotas a partir de la próxima fecha límite de pago
     * de la tarjeta.
     */
    @Transactional
    fun create(
        userId: UUID,
        creditCardId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        merchant: String,
        installmentCount: Int,
        interestRate: BigDecimal,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): InstallmentPlanView {
        if (amount <= BigDecimal.ZERO) {
            throw BusinessRuleException("El monto debe ser mayor a cero.")
        }
        if (installmentCount < MIN_INSTALLMENTS || installmentCount > MAX_INSTALLMENTS) {
            throw BusinessRuleException("El número de cuotas debe estar entre $MIN_INSTALLMENTS y $MAX_INSTALLMENTS.")
        }
        if (interestRate < BigDecimal.ZERO) {
            throw BusinessRuleException("La tasa de interés no puede ser negativa.")
        }

        val card = creditCardService.getOwned(userId, creditCardId)

        val interestAmount = amount
            .multiply(interestRate)
            .divide(BigDecimal(100), 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal(installmentCount))
            .setScale(4, RoundingMode.HALF_UP)
        val totalAmount = amount + interestAmount
        val planType = if (interestRate.compareTo(BigDecimal.ZERO) == 0) InstallmentPlanType.MSI else InstallmentPlanType.MCI

        // La compra completa (incl. interés) afecta el saldo de la tarjeta de una sola vez,
        // igual que cualquier otra compra (ver TransactionService.recordCreditCardPurchase).
        val purchase = transactionService.recordCreditCardPurchase(
            userId = userId,
            cardAccountId = card.account.id!!,
            amount = totalAmount,
            date = date,
            merchant = merchant,
            categoryId = categoryId,
            description = description,
            reference = reference,
        )

        val firstClosing = billingCycleCalculator.closingDateOnOrAfter(card.creditCard.closingDay, date)
        val firstDueDate = billingCycleCalculator.paymentDueDateFor(firstClosing, card.creditCard.closingDay, card.creditCard.paymentDueDay)

        val regularAmount = totalAmount.divide(BigDecimal(installmentCount), 4, RoundingMode.DOWN)
        val lastAmount = totalAmount - regularAmount.multiply(BigDecimal(installmentCount - 1))

        val plan = installmentPlanRepository.save(
            InstallmentPlan(
                creditCardId = creditCardId,
                transactionId = purchase.id!!,
                planType = planType,
                originalAmount = amount,
                installmentCount = installmentCount,
                interestRate = interestRate,
                interestAmount = interestAmount,
                totalAmount = totalAmount,
                installmentAmount = regularAmount,
                startDate = date,
                endDate = firstDueDate.plusMonths((installmentCount - 1).toLong()),
            )
        )

        val installments = (1..installmentCount).map { number ->
            Installment(
                installmentPlanId = plan.id!!,
                number = number,
                dueDate = firstDueDate.plusMonths((number - 1).toLong()),
                amount = if (number == installmentCount) lastAmount else regularAmount,
            )
        }
        val savedInstallments = installmentRepository.saveAll(installments)

        // La compra en sí ya quedó auditada dentro de recordCreditCardPurchase
        // (CREDIT_CARD_PURCHASE_CREATED); esta es la parte específica del plan
        // — una fila por plan, no una por cuota (serían installmentCount filas
        // de puro ruido para un log pensado para lectura humana, ver AuditLog).
        auditLogService.record(
            userId, AuditEventType.INSTALLMENT_PLAN_CREATED, "InstallmentPlan", requireNotNull(plan.id),
            "Plan ${planType.name} de \$${totalAmount.setScale(2, RoundingMode.HALF_UP).toPlainString()} a $installmentCount cuotas en '${merchant.trim()}'.",
        )

        return InstallmentPlanView(plan, savedInstallments, purchase)
    }

    /**
     * Usado por [com.nexora.api.creditcard.web.CreditCardController] para
     * rechazar la edición de una compra a MSI/MCI por el endpoint de compra
     * normal — esa debe editarse por su propio plan (ver [update]), que sabe
     * recalcular el calendario de cuotas y respeta las ya pagadas.
     */
    fun isLinkedToPlan(transactionId: UUID): Boolean =
        installmentPlanRepository.findByTransactionId(transactionId) != null

    /**
     * Al pagar la tarjeta (nota de producto): las cuotas MSI/MCI pendientes
     * "del mes corriente" — con fecha límite dentro del mes de [paymentDate],
     * o ya vencidas — quedan marcadas como pagadas, mismo efecto que
     * [payInstallment] pero automático. Es solo seguimiento (ver
     * [Installment]): no vuelve a mover el saldo, eso ya lo hizo el pago real.
     *
     * No se usa [billingCycleCalculator] aquí a propósito:
     * `closingDateOnOrAfter` calcula el *siguiente* corte (pensado para "si
     * compro hoy, cuándo se factura"), no el ciclo que este pago está
     * cubriendo — con corte 15 y pago límite 5, pagar el 1 de octubre daría
     * un corte del 15 de octubre y un límite del 5 de noviembre, un mes
     * entero por delante de la cuota que realmente se está pagando (5 de
     * octubre). "Fin del mes de la fecha del pago" es la aproximación simple
     * que evita ese error.
     */
    @Transactional
    fun markDueInstallmentsAsPaidByPayment(userId: UUID, creditCardId: UUID, paymentDate: LocalDate) {
        creditCardService.getOwned(userId, creditCardId) // valida propiedad de la tarjeta
        val cutoff = YearMonth.from(paymentDate).atEndOfMonth()

        val activePlans = installmentPlanRepository.findAllByCreditCardId(creditCardId)
            .filter { it.status == InstallmentPlanStatus.ACTIVE }

        for (plan in activePlans) {
            val installments = installmentRepository.findAllByInstallmentPlanIdOrderByNumber(plan.id!!)
            val dueInstallments = installments.filter { it.status == InstallmentStatus.PENDING && it.dueDate <= cutoff }
            if (dueInstallments.isEmpty()) continue

            val now = Instant.now()
            dueInstallments.forEach {
                it.status = InstallmentStatus.PAID
                it.paidAt = now
            }
            installmentRepository.saveAll(dueInstallments)

            // filter() devuelve las mismas referencias, no copias: dueInstallments ya mutó
            // los objetos dentro de installments, así que este chequeo ya ve el estado nuevo.
            if (installments.all { it.status == InstallmentStatus.PAID }) {
                plan.status = InstallmentPlanStatus.COMPLETED
                installmentPlanRepository.save(plan)
            }
        }
    }

    /**
     * A diferencia de [toView] (una query de Transaction y una de
     * Installment por plan — bien para un solo plan, pero un N+1 si se usa
     * en una lista), aquí se piden en lote las Transaction y los
     * Installment de todos los planes de la tarjeta en una sola query cada
     * una, y se arma cada [InstallmentPlanView] en memoria.
     */
    fun listForCreditCard(userId: UUID, creditCardId: UUID): List<InstallmentPlanView> {
        creditCardService.getOwned(userId, creditCardId) // valida propiedad de la tarjeta
        val plans = installmentPlanRepository.findAllByCreditCardId(creditCardId)
        if (plans.isEmpty()) return emptyList()

        val planIds = plans.mapNotNull { it.id }
        val transactionsById = transactionService.getAllByIds(plans.map { it.transactionId }).associateBy { it.id }
        val installmentsByPlanId = installmentRepository
            .findAllByInstallmentPlanIdIn(planIds, Sort.by(Sort.Direction.ASC, "number"))
            .groupBy { it.installmentPlanId }

        return plans.map { plan ->
            val transaction = requireNotNull(transactionsById[plan.transactionId]) {
                "Transacción de la compra original del plan no encontrada (id=${plan.transactionId})."
            }
            InstallmentPlanView(plan, installmentsByPlanId[plan.id] ?: emptyList(), transaction)
        }
    }

    /**
     * Edita un plan MSI/MCI. Si ya hay alguna cuota pagada, solo se pueden
     * cambiar los campos cosméticos de la compra (comercio, categoría,
     * descripción, referencia) — monto, fecha e número de cuotas deben
     * llegar iguales a los actuales, o se rechaza. Sin cuotas pagadas, se
     * recalcula el plan completo (interés, total, calendario) igual que al
     * crearlo, y se regeneran sus cuotas.
     */
    @Transactional
    fun update(
        userId: UUID,
        planId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        merchant: String,
        installmentCount: Int,
        interestRate: BigDecimal,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): InstallmentPlanView {
        val view = getOwned(userId, planId)
        val plan = view.plan
        val card = creditCardService.getOwned(userId, plan.creditCardId)

        if (view.installmentsPaid > 0) {
            val structuralChange = amount.compareTo(plan.originalAmount) != 0 ||
                date != plan.startDate ||
                installmentCount != plan.installmentCount ||
                interestRate.compareTo(plan.interestRate) != 0
            if (structuralChange) {
                throw BusinessRuleException(
                    "Ya hay cuotas pagadas en este plan: no se puede cambiar el monto, la fecha ni el número de " +
                        "cuotas. Solo se pueden editar comercio, categoría, descripción y referencia."
                )
            }
            val transaction = transactionService.updateCreditCardPurchase(
                userId = userId,
                cardAccountId = card.account.id!!,
                transactionId = plan.transactionId,
                amount = amount,
                date = date,
                merchant = merchant,
                categoryId = categoryId,
                description = description,
                reference = reference,
            )
            return InstallmentPlanView(plan, view.installments, transaction)
        }

        if (amount <= BigDecimal.ZERO) {
            throw BusinessRuleException("El monto debe ser mayor a cero.")
        }
        if (installmentCount < MIN_INSTALLMENTS || installmentCount > MAX_INSTALLMENTS) {
            throw BusinessRuleException("El número de cuotas debe estar entre $MIN_INSTALLMENTS y $MAX_INSTALLMENTS.")
        }
        if (interestRate < BigDecimal.ZERO) {
            throw BusinessRuleException("La tasa de interés no puede ser negativa.")
        }

        val interestAmount = amount
            .multiply(interestRate)
            .divide(BigDecimal(100), 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal(installmentCount))
            .setScale(4, RoundingMode.HALF_UP)
        val totalAmount = amount + interestAmount
        val planType = if (interestRate.compareTo(BigDecimal.ZERO) == 0) InstallmentPlanType.MSI else InstallmentPlanType.MCI

        val transaction = transactionService.updateCreditCardPurchase(
            userId = userId,
            cardAccountId = card.account.id!!,
            transactionId = plan.transactionId,
            amount = totalAmount,
            date = date,
            merchant = merchant,
            categoryId = categoryId,
            description = description,
            reference = reference,
        )

        val firstClosing = billingCycleCalculator.closingDateOnOrAfter(card.creditCard.closingDay, date)
        val firstDueDate = billingCycleCalculator.paymentDueDateFor(firstClosing, card.creditCard.closingDay, card.creditCard.paymentDueDay)
        val regularAmount = totalAmount.divide(BigDecimal(installmentCount), 4, RoundingMode.DOWN)
        val lastAmount = totalAmount - regularAmount.multiply(BigDecimal(installmentCount - 1))

        plan.planType = planType
        plan.originalAmount = amount
        plan.installmentCount = installmentCount
        plan.interestRate = interestRate
        plan.interestAmount = interestAmount
        plan.totalAmount = totalAmount
        plan.installmentAmount = regularAmount
        plan.startDate = date
        plan.endDate = firstDueDate.plusMonths((installmentCount - 1).toLong())
        installmentPlanRepository.save(plan)

        // flush explícito: sin él, Hibernate puede reordenar el batch e intentar
        // insertar las cuotas nuevas antes de que el DELETE llegue a la base,
        // chocando con el unique (installment_plan_id, number) de las viejas.
        installmentRepository.deleteAll(view.installments)
        installmentRepository.flush()
        val newInstallments = (1..installmentCount).map { number ->
            Installment(
                installmentPlanId = plan.id!!,
                number = number,
                dueDate = firstDueDate.plusMonths((number - 1).toLong()),
                amount = if (number == installmentCount) lastAmount else regularAmount,
            )
        }
        val savedInstallments = installmentRepository.saveAll(newInstallments)
        return InstallmentPlanView(plan, savedInstallments, transaction)
    }

    /**
     * Planes ACTIVE de todas las tarjetas del usuario, sin cargar sus cuotas
     * (usado por el dashboard para agregaciones a nivel de plan, no de cuota).
     */
    fun listActivePlansForUser(userId: UUID): List<InstallmentPlan> {
        val cardIds = creditCardService.listForUser(userId).mapNotNull { it.creditCard.id }
        if (cardIds.isEmpty()) return emptyList()
        return installmentPlanRepository.findAllByCreditCardIdIn(cardIds)
            .filter { it.status == InstallmentPlanStatus.ACTIVE }
    }

    /**
     * Cuotas PENDING de los planes ACTIVE de cada tarjeta, agrupadas por
     * `creditCardId` — usado por el dashboard para calcular cuánto de la
     * deuda total de cada tarjeta corresponde en realidad al próximo pago
     * (ver DashboardService.upcomingPayments). `CreditCardView.currentDebt`
     * incluye el financiamiento completo de los planes MSI/MCI desde el
     * día 1 (la compra se registra por el total, no cuota a cuota — ver
     * [create]), así que no sirve tal cual como "lo que toca pagar este
     * corte": una compra de $12,000 a 12 MSI debe mostrar $1,000 de próximo
     * pago, no los $12,000 completos.
     */
    fun pendingInstallmentsByCard(userId: UUID): Map<UUID, List<Installment>> {
        val plans = listActivePlansForUser(userId)
        if (plans.isEmpty()) return emptyMap()
        val cardIdByPlanId = plans.associate { requireNotNull(it.id) to it.creditCardId }
        val pending = installmentRepository.findAllByInstallmentPlanIdInAndStatus(cardIdByPlanId.keys.toList(), InstallmentStatus.PENDING)
        return pending.groupBy { cardIdByPlanId.getValue(it.installmentPlanId) }
    }

    /** Busca un plan y valida que su tarjeta pertenezca a [userId]; si no, se trata igual que "no existe". */
    fun getOwned(userId: UUID, planId: UUID): InstallmentPlanView {
        val plan = installmentPlanRepository.findById(planId)
            .orElseThrow { NotFoundException("Plan de meses no encontrado.") }
        creditCardService.getOwned(userId, plan.creditCardId) // valida propiedad de la tarjeta dueña del plan
        return toView(plan)
    }

    @Transactional
    fun payInstallment(userId: UUID, planId: UUID, installmentId: UUID): InstallmentPlanView {
        val view = getOwned(userId, planId)
        val installment = installmentRepository.findByIdAndInstallmentPlanId(installmentId, planId)
            ?: throw NotFoundException("Cuota no encontrada.")
        if (installment.status == InstallmentStatus.PAID) {
            throw BusinessRuleException("Esta cuota ya está marcada como pagada.")
        }
        installment.status = InstallmentStatus.PAID
        installment.paidAt = Instant.now()
        installmentRepository.save(installment)

        val updatedInstallments = installmentRepository.findAllByInstallmentPlanIdOrderByNumber(planId)
        if (updatedInstallments.all { it.status == InstallmentStatus.PAID }) {
            view.plan.status = InstallmentPlanStatus.COMPLETED
            installmentPlanRepository.save(view.plan)
        }
        return InstallmentPlanView(view.plan, updatedInstallments, view.transaction)
    }

    private fun toView(plan: InstallmentPlan): InstallmentPlanView {
        val transaction = requireNotNull(transactionService.getById(plan.transactionId)) {
            "Transacción de la compra original del plan no encontrada (id=${plan.transactionId})."
        }
        return InstallmentPlanView(plan, installmentRepository.findAllByInstallmentPlanIdOrderByNumber(plan.id!!), transaction)
    }
}
