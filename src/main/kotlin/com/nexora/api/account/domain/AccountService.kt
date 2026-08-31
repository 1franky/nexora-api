package com.nexora.api.account.domain

import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.exchangerate.domain.ExchangeRateService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class BalanceSummary(
    val availableBalance: BigDecimal,
    val netWorth: BigDecimal,
)

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val exchangeRateService: ExchangeRateService,
) {

    @Transactional
    fun create(
        userId: UUID,
        name: String,
        type: AccountType,
        currency: String,
        openingBalance: BigDecimal,
        includeInAvailableBalance: Boolean,
        includeInNetWorth: Boolean,
    ): Account {
        val account = Account(
            userId = userId,
            name = name.trim(),
            type = type,
            currency = currency.uppercase(),
            includeInAvailableBalance = includeInAvailableBalance,
            includeInNetWorth = includeInNetWorth,
            balance = openingBalance,
        )
        return accountRepository.save(account)
    }

    fun listForUser(userId: UUID): List<Account> = accountRepository.findAllByUserIdOrderByNameAsc(userId)

    fun listForUserByType(userId: UUID, type: AccountType): List<Account> =
        accountRepository.findAllByUserIdAndTypeOrderByNameAsc(userId, type)

    /** Busca una cuenta y valida que pertenezca a [userId]; si no, se trata igual que "no existe". */
    fun getOwned(userId: UUID, accountId: UUID): Account =
        accountRepository.findByIdAndUserId(accountId, userId)
            ?: throw NotFoundException("Cuenta no encontrada.")

    /**
     * Edita nombre e inclusión en disponible/patrimonio. Tipo, moneda y saldo
     * quedan fuera a propósito: cambiarlos rompería el significado del
     * historial de movimientos ya registrados sobre esta cuenta.
     */
    @Transactional
    fun update(
        userId: UUID,
        accountId: UUID,
        name: String,
        includeInAvailableBalance: Boolean,
        includeInNetWorth: Boolean,
    ): Account {
        val account = getOwned(userId, accountId)
        account.name = name.trim()
        account.includeInAvailableBalance = includeInAvailableBalance
        account.includeInNetWorth = includeInNetWorth
        return accountRepository.save(account)
    }

    /**
     * Solo el nombre — usado por [com.nexora.api.creditcard.domain.CreditCardService.update]
     * para mantener sincronizado el nombre de la Account subyacente de una
     * tarjeta sin tocar includeInAvailableBalance/includeInNetWorth (esos no
     * son editables para una cuenta de tipo CREDIT_CARD, los fija B3 al crearla).
     */
    @Transactional
    fun rename(userId: UUID, accountId: UUID, name: String): Account {
        val account = getOwned(userId, accountId)
        account.name = name.trim()
        return accountRepository.save(account)
    }

    /**
     * Disponible y patrimonio se agregan siempre en MXN (moneda base de la
     * app, ver [ExchangeRateService.rateToBase]): una cuenta en otra moneda
     * se convierte antes de sumar, no se suma tal cual como si ya fuera MXN.
     */
    fun getBalanceSummary(userId: UUID): BalanceSummary {
        val accounts = accountRepository.findAllByUserIdOrderByNameAsc(userId)
        val availableBalance = accounts
            .filter { it.includeInAvailableBalance }
            .fold(BigDecimal.ZERO) { acc, account -> acc + convertToBase(account) }
        val netWorth = accounts
            .filter { it.includeInNetWorth }
            .fold(BigDecimal.ZERO) { acc, account -> acc + convertToBase(account) }
        return BalanceSummary(availableBalance, netWorth)
    }

    private fun convertToBase(account: Account): BigDecimal =
        (account.balance * exchangeRateService.rateToBase(account.currency)).setScale(4, RoundingMode.HALF_UP)

    /** Ajusta el saldo de una cuenta ya cargada en la sesión actual (usado por TransactionService). */
    @Transactional
    fun applyBalanceDelta(account: Account, delta: BigDecimal): Account {
        account.balance = account.balance + delta
        return accountRepository.save(account)
    }
}
