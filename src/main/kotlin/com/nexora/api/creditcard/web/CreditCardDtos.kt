package com.nexora.api.creditcard.web

import com.nexora.api.creditcard.domain.CreditCardStatus
import com.nexora.api.creditcard.domain.CreditCardView
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(description = "Día del mes (1-28) en que cierra el ciclo de facturación.")
    val closingDay: Int,

    @field:NotNull(message = "El día límite de pago es obligatorio.")
    @field:Min(1) @field:Max(28)
    @field:Schema(description = "Día del mes (1-28) límite para pagar sin recargos, del ciclo que ya cerró.")
    val paymentDueDay: Int,

    @field:NotBlank(message = "La moneda es obligatoria.")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "La moneda debe ser un código ISO 4217 de 3 letras (ej. MXN).")
    val currency: String,
)

data class UpdateCreditCardRequest(
    @field:NotBlank(message = "El nombre es obligatorio.")
    val name: String,

    @field:NotBlank(message = "El banco es obligatorio.")
    val bank: String,

    @field:NotNull(message = "El límite de crédito es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El límite de crédito debe ser mayor a cero.")
    val creditLimit: BigDecimal,

    @field:NotNull(message = "El día de corte es obligatorio.")
    @field:Min(1) @field:Max(28)
    @field:Schema(description = "Día del mes (1-28) en que cierra el ciclo de facturación.")
    val closingDay: Int,

    @field:NotNull(message = "El día límite de pago es obligatorio.")
    @field:Min(1) @field:Max(28)
    @field:Schema(description = "Día del mes (1-28) límite para pagar sin recargos, del ciclo que ya cerró.")
    val paymentDueDay: Int,
)

data class CreditCardResponse(
    val id: UUID,
    val accountId: UUID,
    val name: String,
    val bank: String,
    val last4: String,
    val currency: String,
    val creditLimit: BigDecimal,
    @field:Schema(description = "Deuda total actual (>= 0) — incluye compras a MSI/MCI por su monto completo desde el día 1, no solo lo ya vencido.")
    val currentDebt: BigDecimal,
    val availableCredit: BigDecimal,
    val closingDay: Int,
    val paymentDueDay: Int,
    @field:Schema(description = "Próxima fecha de corte, calculada para hoy.")
    val nextClosingDate: LocalDate,
    @field:Schema(description = "Próxima fecha límite de pago, calculada para hoy.")
    val nextPaymentDueDate: LocalDate,
    val status: CreditCardStatus,
) {
    companion object {
        fun from(view: CreditCardView): CreditCardResponse = CreditCardResponse(
            id = requireNotNull(view.creditCard.id),
            accountId = requireNotNull(view.account.id),
            name = view.creditCard.name,
            bank = view.creditCard.bank,
            last4 = view.creditCard.last4,
            currency = view.account.currency,
            creditLimit = view.creditCard.creditLimit,
            currentDebt = view.currentDebt,
            availableCredit = view.availableCredit,
            closingDay = view.creditCard.closingDay,
            paymentDueDay = view.creditCard.paymentDueDay,
            nextClosingDate = view.nextClosingDate,
            nextPaymentDueDate = view.nextPaymentDueDate,
            status = view.creditCard.status,
        )
    }
}

@Schema(description = "Solo para compras de contado (sin plan) — se rechaza si la compra pertenece a un plan MSI/MCI.")
data class UpdateCreditCardPurchaseRequest(
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
    @field:Schema(description = "Cuenta de la que sale el dinero — no puede ser otra tarjeta de crédito ni una cuenta AFORE/PPR.")
    val fromAccountId: UUID,

    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    @field:NotNull(message = "La fecha es obligatoria.")
    val date: LocalDate,

    val description: String? = null,
    val reference: String? = null,
)
