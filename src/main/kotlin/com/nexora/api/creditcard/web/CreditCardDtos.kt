package com.nexora.api.creditcard.web

import com.nexora.api.creditcard.domain.CreditCardStatus
import com.nexora.api.creditcard.domain.CreditCardView
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CreateCreditCardRequest(
    @field:NotBlank(message = "El nombre es obligatorio.")
    val name: String,

    @field:NotBlank(message = "El banco es obligatorio.")
    val bank: String,

    @field:NotBlank(message = "Los últimos 4 dígitos son obligatorios.")
    @field:Pattern(regexp = "^[0-9]{4}$", message = "Deben ser exactamente 4 dígitos.")
    val last4: String,

    @field:NotNull(message = "El límite de crédito es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El límite de crédito debe ser mayor a cero.")
    val creditLimit: BigDecimal,

    @field:NotNull(message = "El día de corte es obligatorio.")
    @field:Min(1) @field:Max(28)
    val closingDay: Int,

    @field:NotNull(message = "El día límite de pago es obligatorio.")
    @field:Min(1) @field:Max(28)
    val paymentDueDay: Int,

    @field:NotBlank(message = "La moneda es obligatoria.")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "La moneda debe ser un código ISO 4217 de 3 letras (ej. MXN).")
    val currency: String,
)

data class CreditCardResponse(
    val id: UUID,
    val accountId: UUID,
    val name: String,
    val bank: String,
    val last4: String,
    val currency: String,
    val creditLimit: BigDecimal,
    val currentDebt: BigDecimal,
    val availableCredit: BigDecimal,
    val closingDay: Int,
    val paymentDueDay: Int,
    val nextClosingDate: LocalDate,
    val nextPaymentDueDate: LocalDate,
    val status: CreditCardStatus,
) {
    companion object {
        fun from(view: CreditCardView): CreditCardResponse {
            val currentDebt = view.account.balance.negate().max(BigDecimal.ZERO)
            return CreditCardResponse(
                id = requireNotNull(view.creditCard.id),
                accountId = requireNotNull(view.account.id),
                name = view.creditCard.name,
                bank = view.creditCard.bank,
                last4 = view.creditCard.last4,
                currency = view.account.currency,
                creditLimit = view.creditCard.creditLimit,
                currentDebt = currentDebt,
                availableCredit = view.availableCredit,
                closingDay = view.creditCard.closingDay,
                paymentDueDay = view.creditCard.paymentDueDay,
                nextClosingDate = view.nextClosingDate,
                nextPaymentDueDate = view.nextPaymentDueDate,
                status = view.creditCard.status,
            )
        }
    }
}

data class CreditCardPurchaseRequest(
    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    @field:NotNull(message = "La fecha es obligatoria.")
    val date: LocalDate,

    @field:NotBlank(message = "El comercio es obligatorio.")
    val merchant: String,

    val categoryId: UUID? = null,
    val description: String? = null,
    val reference: String? = null,
)

data class CreditCardPaymentRequest(
    @field:NotNull(message = "La cuenta de origen del pago es obligatoria.")
    val fromAccountId: UUID,

    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    @field:NotNull(message = "La fecha es obligatoria.")
    val date: LocalDate,

    val description: String? = null,
    val reference: String? = null,
)
