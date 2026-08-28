package com.nexora.api.account.web

import com.nexora.api.account.domain.Account
import com.nexora.api.account.domain.AccountStatus
import com.nexora.api.account.domain.AccountType
import com.nexora.api.account.domain.BalanceSummary
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateAccountRequest(
    @field:NotBlank(message = "El nombre es obligatorio.")
    val name: String,

    @field:NotNull(message = "El tipo de cuenta es obligatorio.")
    val type: AccountType,

    @field:NotBlank(message = "La moneda es obligatoria.")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "La moneda debe ser un código ISO 4217 de 3 letras (ej. MXN).")
    val currency: String,

    @field:DecimalMin(value = "0.0", message = "El saldo inicial no puede ser negativo.")
    val openingBalance: BigDecimal = BigDecimal.ZERO,

    val includeInAvailableBalance: Boolean = true,
    val includeInNetWorth: Boolean = true,
)

data class AccountResponse(
    val id: UUID,
    val name: String,
    val type: AccountType,
    val currency: String,
    val balance: BigDecimal,
    val includeInAvailableBalance: Boolean,
    val includeInNetWorth: Boolean,
    val status: AccountStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(account: Account): AccountResponse = AccountResponse(
            id = requireNotNull(account.id),
            name = account.name,
            type = account.type,
            currency = account.currency,
            balance = account.balance,
            includeInAvailableBalance = account.includeInAvailableBalance,
            includeInNetWorth = account.includeInNetWorth,
            status = account.status,
            createdAt = requireNotNull(account.createdAt),
            updatedAt = requireNotNull(account.updatedAt),
        )
    }
}

data class BalanceSummaryResponse(
    val availableBalance: BigDecimal,
    val netWorth: BigDecimal,
) {
    companion object {
        fun from(summary: BalanceSummary) = BalanceSummaryResponse(summary.availableBalance, summary.netWorth)
    }
}
