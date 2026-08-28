package com.nexora.api.transaction.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Tipos de movimiento (plan.md, sección 3). Por ahora B2 solo implementa
 * INCOME, EXPENSE y TRANSFER; el resto se agrega en B3/B4 pero ya se
 * declaran aquí para no tener que migrar el enum más adelante.
 */
enum class TransactionType {
    INCOME,
    EXPENSE,
    TRANSFER,
    CREDIT_CARD_PURCHASE,
    CREDIT_CARD_PAYMENT,
    REFUND,
    ADJUSTMENT,
}

enum class TransactionStatus {
    POSTED,
    VOIDED,
}

/**
 * Un movimiento afecta a **una** cuenta ([accountId]). Una transferencia se
 * modela como **dos** filas (una por cuenta) que comparten [transferGroupId]
 * y se referencian mutuamente por [counterAccountId] — así nunca se
 * contabiliza como ingreso + gasto (plan.md, regla arquitectónica #4), y el
 * saldo de cada cuenta se calcula igual sin importar el tipo de movimiento.
 *
 * [amount] es siempre la magnitud (> 0) que se muestra al usuario;
 * [balanceEffect] es el valor con signo que realmente se suma al saldo de
 * la cuenta (positivo = entra dinero, negativo = sale dinero).
 */
@Entity
@Table(name = "transactions")
class Transaction(

    @Column(name = "account_id", nullable = false)
    var accountId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var type: TransactionType,

    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal,

    @Column(name = "balance_effect", nullable = false, precision = 19, scale = 4)
    var balanceEffect: BigDecimal,

    @Column(nullable = false)
    var date: LocalDate,

    var description: String? = null,

    var reference: String? = null,

    @Column(name = "category_id")
    var categoryId: UUID? = null,

    @Column(name = "transfer_group_id")
    var transferGroupId: UUID? = null,

    @Column(name = "counter_account_id")
    var counterAccountId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TransactionStatus = TransactionStatus.POSTED,

) : BaseEntity()
