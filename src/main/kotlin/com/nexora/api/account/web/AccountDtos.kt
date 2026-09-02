package com.nexora.api.account.web

import com.nexora.api.account.domain.Account
import com.nexora.api.account.domain.AccountStatus
import com.nexora.api.account.domain.AccountType
import com.nexora.api.account.domain.BalanceSummary
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(description = "Usa CREDIT_CARD solo vía POST /api/v1/credit-cards, no aquí — ahí se crea junto con los datos propios de la tarjeta (límite, corte, pago).")
    val type: AccountType,

    @field:NotBlank(message = "La moneda es obligatoria.")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "La moneda debe ser un código ISO 4217 de 3 letras (ej. MXN).")
    @field:Schema(example = "MXN")
    val currency: String,

    @field:DecimalMin(value = "0.0", message = "El saldo inicial no puede ser negativo.")
    @field:Schema(description = "Saldo con el que arranca la cuenta; no puede ser negativo (a diferencia del saldo de una tarjeta de crédito, que sí puede serlo).")
    val openingBalance: BigDecimal = BigDecimal.ZERO,

    @field:Schema(description = "Si se suma al \"disponible\" agregado (GET /accounts/summary y el dashboard).")
    val includeInAvailableBalance: Boolean = true,

    @field:Schema(description = "Si se suma al \"patrimonio neto\" agregado.")
    val includeInNetWorth: Boolean = true,
)

@Schema(description = "Tipo, moneda y saldo no son editables: cambiarlos rompería el significado del historial de movimientos ya registrados.")
data class UpdateAccountRequest(
    @field:NotBlank(message = "El nombre es obligatorio.")
    val name: String,

    val includeInAvailableBalance: Boolean,
    val includeInNetWorth: Boolean,
)

data class AccountResponse(
    val id: UUID,
    val name: String,
    val type: AccountType,
    val currency: String,
    @field:Schema(description = "Convención de signo: positivo es saldo a favor; en una tarjeta de crédito, negativo es deuda (ver CreditCardView.currentDebt).")
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

@Schema(description = "Ambos valores ya vienen convertidos a MXN (moneda base) cuando alguna cuenta está en otra moneda.")
data class BalanceSummaryResponse(
    val availableBalance: BigDecimal,
    val netWorth: BigDecimal,
) {
    companion object {
        fun from(summary: BalanceSummary) = BalanceSummaryResponse(summary.availableBalance, summary.netWorth)
    }
}
