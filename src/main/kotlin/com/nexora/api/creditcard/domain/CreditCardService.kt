package com.nexora.api.creditcard.domain

import com.nexora.api.account.domain.Account
import com.nexora.api.account.domain.AccountService
import com.nexora.api.account.domain.AccountType
import com.nexora.api.common.domain.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Vista combinada de una tarjeta con su cuenta y los valores calculados del ciclo actual. */
data class CreditCardView(
    val creditCard: CreditCard,
    val account: Account,
    val availableCredit: BigDecimal,
    val nextClosingDate: LocalDate,
    val nextPaymentDueDate: LocalDate,
) {
    /** Saldo utilizado (deuda), siempre >= 0 — ver convención de signo en [Account.balance]. */
    val currentDebt: BigDecimal get() = account.balance.negate().max(BigDecimal.ZERO)
}

@Service
class CreditCardService(
    private val creditCardRepository: CreditCardRepository,
    private val accountService: AccountService,
    private val billingCycleCalculator: BillingCycleCalculator,
) {

    @Transactional
    fun create(
        userId: UUID,
        name: String,
        bank: String,
        last4: String,
        creditLimit: BigDecimal,
        closingDay: Int,
        paymentDueDay: Int,
        currency: String,
    ): CreditCardView {
        require(closingDay in 1..28) { "El día de corte debe estar entre 1 y 28." }
        require(paymentDueDay in 1..28) { "El día límite de pago debe estar entre 1 y 28." }

        // La tarjeta es, para efectos de saldo/patrimonio, una Account más: el
        // saldo se guarda negativo cuando hay deuda (ver plan.md, sección 2.3),
        // y no cuenta como "dinero disponible" (regla de negocio de B3).
        val account = accountService.create(
            userId = userId,
            name = name,
            type = AccountType.CREDIT_CARD,
            currency = currency,
            openingBalance = BigDecimal.ZERO,
            includeInAvailableBalance = false,
            includeInNetWorth = true,
        )
        val creditCard = CreditCard(
            accountId = account.id!!,
            name = name.trim(),
            bank = bank.trim(),
            last4 = last4,
            creditLimit = creditLimit,
            closingDay = closingDay,
            paymentDueDay = paymentDueDay,
        )
        return toView(creditCardRepository.save(creditCard), account)
    }

    fun listForUser(userId: UUID): List<CreditCardView> {
        val cardAccounts = accountService.listForUserByType(userId, AccountType.CREDIT_CARD)
        val cardsByAccountId = creditCardRepository.findAllByAccountIdIn(cardAccounts.mapNotNull { it.id })
            .associateBy { it.accountId }
        return cardAccounts.mapNotNull { account -> cardsByAccountId[account.id]?.let { toView(it, account) } }
    }

    /** Busca una tarjeta y valida que pertenezca a [userId]; si no, se trata igual que "no existe". */
    fun getOwned(userId: UUID, creditCardId: UUID): CreditCardView {
        val creditCard = creditCardRepository.findById(creditCardId)
            .orElseThrow { NotFoundException("Tarjeta de crédito no encontrada.") }
        val account = accountService.getOwned(userId, creditCard.accountId)
        return toView(creditCard, account)
    }

    private fun toView(creditCard: CreditCard, account: Account): CreditCardView {
        val today = LocalDate.now()
        val nextClosingDate = billingCycleCalculator.closingDateOnOrAfter(creditCard.closingDay, today)
        val nextPaymentDueDate = billingCycleCalculator.paymentDueDateFor(nextClosingDate, creditCard.paymentDueDay)
        return CreditCardView(
            creditCard = creditCard,
            account = account,
            availableCredit = creditCard.creditLimit + account.balance,
            nextClosingDate = nextClosingDate,
            nextPaymentDueDate = nextPaymentDueDate,
        )
    }
}
