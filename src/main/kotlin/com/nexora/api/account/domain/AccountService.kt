package com.nexora.api.account.domain

import com.nexora.api.common.domain.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

data class BalanceSummary(
    val availableBalance: BigDecimal,
    val netWorth: BigDecimal,
)

@Service
class AccountService(
    private val accountRepository: AccountRepository,
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

    fun listForUser(userId: UUID): List<Account> = accountRepository.findAllByUserId(userId)

    fun listForUserByType(userId: UUID, type: AccountType): List<Account> =
        accountRepository.findAllByUserIdAndType(userId, type)

    /** Busca una cuenta y valida que pertenezca a [userId]; si no, se trata igual que "no existe". */
    fun getOwned(userId: UUID, accountId: UUID): Account =
        accountRepository.findByIdAndUserId(accountId, userId)
            ?: throw NotFoundException("Cuenta no encontrada.")

    fun getBalanceSummary(userId: UUID): BalanceSummary {
        val accounts = accountRepository.findAllByUserId(userId)
        val availableBalance = accounts
            .filter { it.includeInAvailableBalance }
            .fold(BigDecimal.ZERO) { acc, account -> acc + account.balance }
        val netWorth = accounts
            .filter { it.includeInNetWorth }
            .fold(BigDecimal.ZERO) { acc, account -> acc + account.balance }
        return BalanceSummary(availableBalance, netWorth)
    }

    /** Ajusta el saldo de una cuenta ya cargada en la sesión actual (usado por TransactionService). */
    @Transactional
    fun applyBalanceDelta(account: Account, delta: BigDecimal): Account {
        account.balance = account.balance + delta
        return accountRepository.save(account)
    }
}
