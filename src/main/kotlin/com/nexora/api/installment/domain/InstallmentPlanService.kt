package com.nexora.api.installment.domain

import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.creditcard.domain.BillingCycleCalculator
import com.nexora.api.creditcard.domain.CreditCardService
import com.nexora.api.transaction.domain.TransactionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Vista de un plan con sus cuotas y los valores calculados (plan.md, sección 6.3). */
data class InstallmentPlanView(
    val plan: InstallmentPlan,
    val installments: List<Installment>,
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
        val firstDueDate = billingCycleCalculator.paymentDueDateFor(firstClosing, card.creditCard.paymentDueDay)

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

        return InstallmentPlanView(plan, savedInstallments)
    }

    fun listForCreditCard(userId: UUID, creditCardId: UUID): List<InstallmentPlanView> {
        creditCardService.getOwned(userId, creditCardId) // valida propiedad de la tarjeta
        return installmentPlanRepository.findAllByCreditCardId(creditCardId).map { toView(it) }
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
        return InstallmentPlanView(view.plan, updatedInstallments)
    }

    private fun toView(plan: InstallmentPlan): InstallmentPlanView =
        InstallmentPlanView(plan, installmentRepository.findAllByInstallmentPlanIdOrderByNumber(plan.id!!))
}
