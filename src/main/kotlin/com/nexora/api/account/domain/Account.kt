package com.nexora.api.account.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.util.UUID

enum class AccountType {
    DEBIT,
    SAVINGS,
    CREDIT_CARD,
    AFORE,
    PPR,
}

enum class AccountStatus {
    ACTIVE,
    ARCHIVED,
}

/**
 * Cuenta de un usuario (débito/corriente, ahorro, tarjeta de crédito, AFORE
 * o PPR — ver plan.md, sección 2.1). Los detalles específicos de tarjetas de
 * crédito (límite, corte, fecha de pago) se agregan en B3 con una entidad
 * CreditCard aparte que referencia esta cuenta.
 *
 * [userId] se guarda como referencia simple (no una relación JPA) a
 * propósito: los módulos de dominio se mantienen desacoplados entre sí,
 * pensando en la posible evolución a microservicios (plan.md, sección 34).
 */
@Entity
@Table(name = "accounts")
class Account(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: AccountType,

    @Column(nullable = false, length = 3)
    var currency: String,

    @Column(name = "include_in_available_balance", nullable = false)
    var includeInAvailableBalance: Boolean,

    @Column(name = "include_in_net_worth", nullable = false)
    var includeInNetWorth: Boolean,

    @Column(nullable = false, precision = 19, scale = 4)
    var balance: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AccountStatus = AccountStatus.ACTIVE,

) : BaseEntity() {

    /** Optimistic locking: varias transacciones pueden intentar mover el saldo de la misma cuenta a la vez. */
    @Version
    var version: Long = 0
}
