package com.nexora.api.transaction.web

import com.nexora.api.transaction.domain.Transaction
import com.nexora.api.transaction.domain.TransactionStatus
import com.nexora.api.transaction.domain.TransactionType
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateTransactionRequest(
    @field:NotNull(message = "El tipo de movimiento es obligatorio.")
    val type: TransactionType,

    @field:NotNull(message = "La cuenta es obligatoria.")
    val accountId: UUID,

    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    @field:NotNull(message = "La fecha es obligatoria.")
    val date: LocalDate,

    val categoryId: UUID? = null,
    val description: String? = null,
    val reference: String? = null,
)

data class UpdateTransactionRequest(
    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    @field:NotNull(message = "La fecha es obligatoria.")
    val date: LocalDate,

    val categoryId: UUID? = null,
    val description: String? = null,
    val reference: String? = null,
)

data class CreateTransferRequest(
    @field:NotNull(message = "La cuenta origen es obligatoria.")
    val fromAccountId: UUID,

    @field:NotNull(message = "La cuenta destino es obligatoria.")
    val toAccountId: UUID,

    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    @field:NotNull(message = "La fecha es obligatoria.")
    val date: LocalDate,

    val description: String? = null,
    val reference: String? = null,
)

data class TransactionResponse(
    val id: UUID,
    val accountId: UUID,
    val type: TransactionType,
    val amount: BigDecimal,
    /**
     * Igual a [amount] pero con signo (positivo si aumenta el saldo de
     * [accountId], negativo si lo disminuye). Sin este campo, un cliente no
     * puede saber si una fila de tipo TRANSFER o CREDIT_CARD_PAYMENT es la
     * pierna de salida o la de entrada: ambas comparten el mismo [type], y
     * solo se diferencian por el signo interno de `balanceEffect`
     * (ver TransactionService.recordTransfer/recordCreditCardPayment).
     */
    val balanceEffect: BigDecimal,
    val date: LocalDate,
    val description: String?,
    val reference: String?,
    val categoryId: UUID?,
    val transferGroupId: UUID?,
    val counterAccountId: UUID?,
    val merchant: String?,
    val status: TransactionStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(transaction: Transaction): TransactionResponse = TransactionResponse(
            id = requireNotNull(transaction.id),
            accountId = transaction.accountId,
            type = transaction.type,
            amount = transaction.amount,
            balanceEffect = transaction.balanceEffect,
            date = transaction.date,
            description = transaction.description,
            reference = transaction.reference,
            categoryId = transaction.categoryId,
            transferGroupId = transaction.transferGroupId,
            counterAccountId = transaction.counterAccountId,
            merchant = transaction.merchant,
            status = transaction.status,
            createdAt = requireNotNull(transaction.createdAt),
        )
    }
}

data class TransferResponse(
    val outgoing: TransactionResponse,
    val incoming: TransactionResponse,
)
