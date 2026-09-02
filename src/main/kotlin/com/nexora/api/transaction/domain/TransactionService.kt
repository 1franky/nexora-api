package com.nexora.api.transaction.domain

import com.nexora.api.account.domain.Account
import com.nexora.api.account.domain.AccountService
import com.nexora.api.account.domain.AccountStatus
import com.nexora.api.account.domain.AccountType
import com.nexora.api.audit.domain.AuditEventType
import com.nexora.api.audit.domain.AuditLogService
import com.nexora.api.category.domain.Category
import com.nexora.api.category.domain.CategoryService
import com.nexora.api.category.domain.CategoryStatus
import com.nexora.api.category.domain.CategoryType
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val accountService: AccountService,
    private val categoryService: CategoryService,
    private val auditLogService: AuditLogService,
) {

    @Transactional
    fun recordIncome(
        userId: UUID,
        accountId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): Transaction = recordSimple(userId, accountId, TransactionType.INCOME, amount, date, categoryId, description, reference)

    @Transactional
    fun recordExpense(
        userId: UUID,
        accountId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): Transaction = recordSimple(userId, accountId, TransactionType.EXPENSE, amount, date, categoryId, description, reference)

    private fun recordSimple(
        userId: UUID,
        accountId: UUID,
        type: TransactionType,
        amount: BigDecimal,
        date: LocalDate,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): Transaction {
        requirePositiveAmount(amount)
        val account = requireActiveOwnedAccount(userId, accountId)
        val category = categoryId?.let { categoryService.getOwned(userId, it) }
        if (category != null) {
            val expectedCategoryType = if (type == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
            if (category.type != expectedCategoryType) {
                throw BusinessRuleException(
                    "La categoría '${category.name}' es de tipo ${category.type}, no se puede usar en un movimiento de tipo $type."
                )
            }
            requireActiveCategory(category)
        }

        val balanceEffect = if (type == TransactionType.INCOME) amount else amount.negate()
        val transaction = Transaction(
            accountId = account.id!!,
            type = type,
            amount = amount,
            balanceEffect = balanceEffect,
            date = date,
            description = description?.trim(),
            reference = reference?.trim(),
            categoryId = categoryId,
        )
        accountService.applyBalanceDelta(account, balanceEffect)
        val saved = transactionRepository.save(transaction)
        val kind = if (type == TransactionType.INCOME) "Ingreso" else "Gasto"
        auditLogService.record(
            userId, AuditEventType.TRANSACTION_CREATED, "Transaction", requireNotNull(saved.id),
            "$kind de \$${money(amount)} en '${account.name}'.",
        )
        return saved
    }

    /**
     * Registra una transferencia entre dos cuentas del mismo usuario como
     * una única operación atómica: dos filas de Transaction (una por
     * cuenta), nunca contabilizada como ingreso + gasto (plan.md, regla
     * arquitectónica #4).
     */
    @Transactional
    fun recordTransfer(
        userId: UUID,
        fromAccountId: UUID,
        toAccountId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        description: String?,
        reference: String?,
    ): TransferResult {
        requirePositiveAmount(amount)
        if (fromAccountId == toAccountId) {
            throw BusinessRuleException("La cuenta origen y la cuenta destino no pueden ser la misma.")
        }
        val fromAccount = requireActiveOwnedAccount(userId, fromAccountId)
        val toAccount = requireActiveOwnedAccount(userId, toAccountId)

        val transferGroupId = UUID.randomUUID()

        val outgoing = Transaction(
            accountId = fromAccount.id!!,
            type = TransactionType.TRANSFER,
            amount = amount,
            balanceEffect = amount.negate(),
            date = date,
            description = description?.trim(),
            reference = reference?.trim(),
            transferGroupId = transferGroupId,
            counterAccountId = toAccount.id,
        )
        val incoming = Transaction(
            accountId = toAccount.id!!,
            type = TransactionType.TRANSFER,
            amount = amount,
            balanceEffect = amount,
            date = date,
            description = description?.trim(),
            reference = reference?.trim(),
            transferGroupId = transferGroupId,
            counterAccountId = fromAccount.id,
        )

        accountService.applyBalanceDelta(fromAccount, outgoing.balanceEffect)
        accountService.applyBalanceDelta(toAccount, incoming.balanceEffect)

        val savedOutgoing = transactionRepository.save(outgoing)
        val savedIncoming = transactionRepository.save(incoming)
        // Una fila de auditoría por transferencia, no una por pierna — las dos piernas son la misma operación de negocio.
        auditLogService.record(
            userId, AuditEventType.TRANSACTION_CREATED, "Transaction", requireNotNull(savedOutgoing.id),
            "Transferencia de \$${money(amount)} de '${fromAccount.name}' a '${toAccount.name}'.",
        )
        return TransferResult(savedOutgoing, savedIncoming)
    }

    /**
     * Compra "de contado" con una tarjeta de crédito (sin MSI/MCI, eso es
     * B4). Solo afecta a la cuenta de la tarjeta: aumenta la deuda
     * (balanceEffect negativo).
     */
    @Transactional
    fun recordCreditCardPurchase(
        userId: UUID,
        cardAccountId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        merchant: String,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): Transaction {
        requirePositiveAmount(amount)
        val cardAccount = requireActiveOwnedAccount(userId, cardAccountId)
        requireAccountType(cardAccount, AccountType.CREDIT_CARD, "La cuenta '${cardAccount.name}' no es una tarjeta de crédito.")
        val category = categoryId?.let { categoryService.getOwned(userId, it) }
        if (category != null && category.type != CategoryType.EXPENSE) {
            throw BusinessRuleException("La categoría '${category.name}' debe ser de tipo EXPENSE para usarse en una compra.")
        }
        if (category != null) requireActiveCategory(category)

        val balanceEffect = amount.negate()
        val transaction = Transaction(
            accountId = cardAccount.id!!,
            type = TransactionType.CREDIT_CARD_PURCHASE,
            amount = amount,
            balanceEffect = balanceEffect,
            date = date,
            description = description?.trim(),
            reference = reference?.trim(),
            merchant = merchant.trim(),
            categoryId = categoryId,
        )
        accountService.applyBalanceDelta(cardAccount, balanceEffect)
        val saved = transactionRepository.save(transaction)
        auditLogService.record(
            userId, AuditEventType.CREDIT_CARD_PURCHASE_CREATED, "Transaction", requireNotNull(saved.id),
            "Compra de \$${money(amount)} en '${merchant.trim()}' con '${cardAccount.name}'.",
        )
        return saved
    }

    /**
     * Edita una compra de tarjeta ya registrada (monto, fecha, comercio,
     * categoría, descripción, referencia), reajustando el saldo de la
     * tarjeta por la diferencia. No distingue compra normal de compra a
     * MSI/MCI — [com.nexora.api.installment.domain.InstallmentPlanService.update]
     * es quien decide si esta compra es de un plan y, si ya tiene cuotas
     * pagadas, restringe qué se puede tocar antes de llamar aquí.
     */
    @Transactional
    fun updateCreditCardPurchase(
        userId: UUID,
        cardAccountId: UUID,
        transactionId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        merchant: String,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): Transaction {
        requirePositiveAmount(amount)
        val cardAccount = requireActiveOwnedAccount(userId, cardAccountId)
        requireAccountType(cardAccount, AccountType.CREDIT_CARD, "La cuenta '${cardAccount.name}' no es una tarjeta de crédito.")
        val transaction = transactionRepository.findById(transactionId)
            .orElseThrow { NotFoundException("Movimiento no encontrado.") }
        if (transaction.accountId != cardAccount.id || transaction.type != TransactionType.CREDIT_CARD_PURCHASE) {
            throw NotFoundException("Movimiento no encontrado.")
        }
        val category = categoryId?.let { categoryService.getOwned(userId, it) }
        if (category != null && category.type != CategoryType.EXPENSE) {
            throw BusinessRuleException("La categoría '${category.name}' debe ser de tipo EXPENSE para usarse en una compra.")
        }
        if (category != null) requireActiveCategory(category)

        val newBalanceEffect = amount.negate()
        val delta = newBalanceEffect - transaction.balanceEffect
        transaction.amount = amount
        transaction.balanceEffect = newBalanceEffect
        transaction.date = date
        transaction.merchant = merchant.trim()
        transaction.categoryId = categoryId
        transaction.description = description?.trim()
        transaction.reference = reference?.trim()
        accountService.applyBalanceDelta(cardAccount, delta)
        val saved = transactionRepository.save(transaction)
        auditLogService.record(
            userId, AuditEventType.TRANSACTION_UPDATED, "Transaction", requireNotNull(saved.id),
            "Compra de '${cardAccount.name}' editada: monto actual \$${money(amount)} en '${merchant.trim()}'.",
        )
        return saved
    }

    /**
     * Pago de una tarjeta de crédito desde otra cuenta del mismo usuario.
     * Igual que una transferencia (dos filas ligadas por transferGroupId),
     * pero con tipo CREDIT_CARD_PAYMENT: nunca se contabiliza como un gasto
     * adicional, porque el gasto ya ocurrió en la compra (plan.md, sección 8).
     */
    @Transactional
    fun recordCreditCardPayment(
        userId: UUID,
        fromAccountId: UUID,
        cardAccountId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        description: String?,
        reference: String?,
    ): TransferResult {
        requirePositiveAmount(amount)
        val fromAccount = requireActiveOwnedAccount(userId, fromAccountId)
        val cardAccount = requireActiveOwnedAccount(userId, cardAccountId)
        requireAccountType(cardAccount, AccountType.CREDIT_CARD, "La cuenta '${cardAccount.name}' no es una tarjeta de crédito.")
        if (fromAccount.type == AccountType.CREDIT_CARD) {
            throw BusinessRuleException("No se puede pagar una tarjeta de crédito con otra tarjeta de crédito.")
        }
        // AFORE/PPR son cuentas de retiro, no de uso corriente: no tiene sentido pagar una
        // tarjeta desde ahí (nexora-web/nexora-android ya las ocultan del selector "cuenta origen").
        if (fromAccount.type == AccountType.AFORE || fromAccount.type == AccountType.PPR) {
            throw BusinessRuleException("No se puede pagar una tarjeta de crédito desde una cuenta de tipo ${fromAccount.type}.")
        }

        val transferGroupId = UUID.randomUUID()

        val outgoing = Transaction(
            accountId = fromAccount.id!!,
            type = TransactionType.CREDIT_CARD_PAYMENT,
            amount = amount,
            balanceEffect = amount.negate(),
            date = date,
            description = description?.trim(),
            reference = reference?.trim(),
            transferGroupId = transferGroupId,
            counterAccountId = cardAccount.id,
        )
        val incoming = Transaction(
            accountId = cardAccount.id!!,
            type = TransactionType.CREDIT_CARD_PAYMENT,
            amount = amount,
            balanceEffect = amount,
            date = date,
            description = description?.trim(),
            reference = reference?.trim(),
            transferGroupId = transferGroupId,
            counterAccountId = fromAccount.id,
        )

        accountService.applyBalanceDelta(fromAccount, outgoing.balanceEffect)
        accountService.applyBalanceDelta(cardAccount, incoming.balanceEffect)

        val savedOutgoing = transactionRepository.save(outgoing)
        val savedIncoming = transactionRepository.save(incoming)
        auditLogService.record(
            userId, AuditEventType.PAYMENT_CREATED, "Transaction", requireNotNull(savedOutgoing.id),
            "Pago de \$${money(amount)} a '${cardAccount.name}' desde '${fromAccount.name}'.",
        )
        return TransferResult(savedOutgoing, savedIncoming)
    }

    /**
     * Edita un movimiento simple (INCOME/EXPENSE) ya registrado, reajustando
     * el saldo de su cuenta por la diferencia. Transferencias y movimientos
     * de tarjeta no se editan por aquí: una transferencia tiene dos piernas
     * que deben mantenerse en espejo, y una compra de tarjeta ya tiene su
     * propio endpoint ([updateCreditCardPurchase]) que sabe de planes MSI/MCI.
     */
    @Transactional
    fun updateSimple(
        userId: UUID,
        transactionId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        categoryId: UUID?,
        description: String?,
        reference: String?,
    ): Transaction {
        requirePositiveAmount(amount)
        val transaction = transactionRepository.findById(transactionId)
            .orElseThrow { NotFoundException("Movimiento no encontrado.") }
        val account = accountService.getOwned(userId, transaction.accountId)
        if (transaction.type != TransactionType.INCOME && transaction.type != TransactionType.EXPENSE) {
            throw BusinessRuleException("Este movimiento no se puede editar desde aquí.")
        }
        val category = categoryId?.let { categoryService.getOwned(userId, it) }
        if (category != null) {
            val expectedCategoryType = if (transaction.type == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
            if (category.type != expectedCategoryType) {
                throw BusinessRuleException(
                    "La categoría '${category.name}' es de tipo ${category.type}, no se puede usar en un movimiento de tipo ${transaction.type}."
                )
            }
            requireActiveCategory(category)
        }

        val newBalanceEffect = if (transaction.type == TransactionType.INCOME) amount else amount.negate()
        val delta = newBalanceEffect - transaction.balanceEffect
        transaction.amount = amount
        transaction.balanceEffect = newBalanceEffect
        transaction.date = date
        transaction.categoryId = categoryId
        transaction.description = description?.trim()
        transaction.reference = reference?.trim()
        accountService.applyBalanceDelta(account, delta)
        val saved = transactionRepository.save(transaction)
        val kind = if (transaction.type == TransactionType.INCOME) "Ingreso" else "Gasto"
        auditLogService.record(
            userId, AuditEventType.TRANSACTION_UPDATED, "Transaction", requireNotNull(saved.id),
            "$kind de '${account.name}' editado: monto actual \$${money(amount)}.",
        )
        return saved
    }

    /**
     * Borra un movimiento, revirtiendo su efecto en el/los saldo(s)
     * involucrados. TRANSFER borra las dos piernas juntas (nunca una sola,
     * se rompería el balance entre las dos cuentas). CREDIT_CARD_PAYMENT no
     * se borra por aquí: revertirlo implicaría des-marcar como pagadas las
     * cuotas MSI/MCI que ese pago haya marcado
     * ([com.nexora.api.installment.domain.InstallmentPlanService.markDueInstallmentsAsPaidByPayment]),
     * y no hay forma segura de saber cuáles eran "ya pagadas antes" vs.
     * "marcadas por este pago" sin guardar más historial del que existe hoy.
     * Si [transactionId] es una compra de tarjeta ligada a un plan MSI/MCI,
     * el llamador (TransactionController) es quien lo valida antes de
     * llegar aquí, igual que ya hace [com.nexora.api.creditcard.web.CreditCardController]
     * para editar una compra — evita una dependencia circular entre
     * TransactionService e InstallmentPlanService.
     */
    @Transactional
    fun delete(userId: UUID, transactionId: UUID) {
        val transaction = transactionRepository.findById(transactionId)
            .orElseThrow { NotFoundException("Movimiento no encontrado.") }
        val account = accountService.getOwned(userId, transaction.accountId)
        when (transaction.type) {
            TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.CREDIT_CARD_PURCHASE -> {
                accountService.applyBalanceDelta(account, transaction.balanceEffect.negate())
                transactionRepository.delete(transaction)
            }

            TransactionType.TRANSFER -> {
                val groupId = transaction.transferGroupId ?: throw NotFoundException("Movimiento no encontrado.")
                val legs = transactionRepository.findAllByTransferGroupId(groupId)
                legs.forEach { leg ->
                    val legAccount = accountService.getOwned(userId, leg.accountId)
                    accountService.applyBalanceDelta(legAccount, leg.balanceEffect.negate())
                }
                transactionRepository.deleteAll(legs)
            }

            else -> throw BusinessRuleException("Este movimiento no se puede borrar desde aquí.")
        }
        auditLogService.record(
            userId, AuditEventType.TRANSACTION_DELETED, "Transaction", requireNotNull(transaction.id),
            "Movimiento de tipo ${transaction.type} por \$${money(transaction.amount)} en '${account.name}' borrado.",
        )
    }

    /**
     * [accountId] es opcional: con él, lista solo esa cuenta (validando
     * propiedad, como antes); sin él, lista de todas las cuentas del
     * usuario juntas — pedido explícitamente para no obligar a elegir una
     * cuenta antes de poder ver los movimientos (issue de nexora-web sobre
     * el filtro de Movimientos).
     */
    /**
     * Lectura directa por id, sin validar propiedad — usada por
     * [com.nexora.api.installment.domain.InstallmentPlanService], que ya
     * valida la propiedad del plan (y por lo tanto de esta transacción) por
     * su propia cadena plan -> tarjeta -> cuenta antes de llamar aquí.
     */
    fun getById(id: UUID): Transaction? = transactionRepository.findById(id).orElse(null)

    /**
     * Igual que [getById] pero en lote — una sola query en vez de una por
     * id. Usada por [com.nexora.api.installment.domain.InstallmentPlanService.listForCreditCard]
     * para no hacer un N+1 (antes: una query de Transaction *y* una de
     * Installment por cada plan de la tarjeta).
     */
    fun getAllByIds(ids: List<UUID>): List<Transaction> =
        if (ids.isEmpty()) emptyList() else transactionRepository.findAllById(ids)

    fun listForUser(userId: UUID, accountId: UUID?): List<Transaction> {
        val sort = Sort.by(Sort.Direction.DESC, "date", "createdAt")
        if (accountId != null) {
            accountService.getOwned(userId, accountId) // valida propiedad de la cuenta
            return transactionRepository.findAllByAccountId(accountId, sort)
        }
        val accountIds = accountService.listForUser(userId).mapNotNull { it.id }
        if (accountIds.isEmpty()) return emptyList()
        return transactionRepository.findAllByAccountIdIn(accountIds, sort)
    }

    private fun requireActiveOwnedAccount(userId: UUID, accountId: UUID): Account {
        val account = accountService.getOwned(userId, accountId)
        if (account.status != AccountStatus.ACTIVE) {
            throw BusinessRuleException("La cuenta '${account.name}' no está activa.")
        }
        return account
    }

    private fun requireAccountType(account: Account, expected: AccountType, message: String) {
        if (account.type != expected) {
            throw BusinessRuleException(message)
        }
    }

    /** Una categoría archivada (gestión completa, plan.md sección 19) sigue existiendo para lo ya categorizado, pero no se puede usar en un movimiento nuevo. */
    private fun requireActiveCategory(category: Category) {
        if (category.status != CategoryStatus.ACTIVE) {
            throw BusinessRuleException("La categoría '${category.name}' está archivada.")
        }
    }

    private fun requirePositiveAmount(amount: BigDecimal) {
        if (amount <= BigDecimal.ZERO) {
            throw BusinessRuleException("El monto debe ser mayor a cero.")
        }
    }

    /** Formato consistente para los montos que aparecen en AuditLog.summary. */
    private fun money(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

data class TransferResult(val outgoing: Transaction, val incoming: Transaction)
