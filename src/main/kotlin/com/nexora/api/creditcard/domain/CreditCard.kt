package com.nexora.api.creditcard.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

enum class CreditCardStatus {
    ACTIVE,
    ARCHIVED,
}

/**
 * Datos específicos de una tarjeta de crédito, complementarios a la
 * [com.nexora.api.account.domain.Account] (tipo CREDIT_CARD) que referencia
 * — ver plan.md, sección 4. El saldo, disponible, etc. viven en la Account
 * (regla: los cálculos financieros importantes se hacen sobre el ledger de
 * Transaction, no aquí).
 *
 * No se almacenan datos sensibles: solo los últimos 4 dígitos (plan.md,
 * sección 13 "Seguridad").
 */
@Entity
@Table(name = "credit_cards")
class CreditCard(

    @Column(name = "account_id", nullable = false, unique = true)
    var accountId: UUID,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var bank: String,

    @Column(name = "last4", nullable = false, length = 4)
    var last4: String,

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
    var creditLimit: BigDecimal,

    @Column(name = "closing_day", nullable = false)
    var closingDay: Int,

    @Column(name = "payment_due_day", nullable = false)
    var paymentDueDay: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CreditCardStatus = CreditCardStatus.ACTIVE,

) : BaseEntity()
