package com.nexora.api.transaction.web

import com.nexora.api.account.domain.AccountService
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.transaction.domain.TransactionService
import com.nexora.api.transaction.domain.TransactionType
import com.nexora.api.user.security.NexoraUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Ingresos y gastos. Las transferencias tienen su propio endpoint dedicado
 * ([com.nexora.api.transaction.web.TransferController]) porque afectan a
 * dos cuentas a la vez (plan.md, sección 16 "API REST").
 */
@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val transactionService: TransactionService,
    private val accountService: AccountService,
    private val installmentPlanService: InstallmentPlanService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: CreateTransactionRequest,
    ): ResponseEntity<TransactionResponse> {
        val transaction = when (request.type) {
            TransactionType.INCOME -> transactionService.recordIncome(
                userId = principal.userId,
                accountId = request.accountId,
                amount = request.amount,
                date = request.date,
                categoryId = request.categoryId,
                description = request.description,
                reference = request.reference,
            )

            TransactionType.EXPENSE -> transactionService.recordExpense(
                userId = principal.userId,
                accountId = request.accountId,
                amount = request.amount,
                date = request.date,
                categoryId = request.categoryId,
                description = request.description,
                reference = request.reference,
            )

            else -> throw BusinessRuleException(
                "El tipo ${request.type} no se registra por este endpoint (usa /api/v1/transfers para transferencias)."
            )
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(transaction))
    }

    /** Sin [accountId], lista los movimientos de todas las cuentas del usuario juntos. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @RequestParam(required = false) accountId: UUID?,
    ): List<TransactionResponse> =
        transactionService.listForUser(principal.userId, accountId).map(TransactionResponse::from)

    /** Solo movimientos INCOME/EXPENSE — transferencias y tarjeta tienen sus propios flujos de edición. */
    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateTransactionRequest,
    ): TransactionResponse {
        val transaction = transactionService.updateSimple(
            userId = principal.userId,
            transactionId = id,
            amount = request.amount,
            date = request.date,
            categoryId = request.categoryId,
            description = request.description,
            reference = request.reference,
        )
        return TransactionResponse.from(transaction)
    }

    /**
     * Borra cualquier movimiento (ver [TransactionService.delete] para el
     * detalle por tipo). Una compra de tarjeta ligada a un plan MSI/MCI se
     * rechaza aquí mismo, antes de llegar al servicio, por la misma razón
     * de dependencias que ya resuelve [com.nexora.api.creditcard.web.CreditCardController.updatePurchase].
     */
    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val transaction = transactionService.getById(id) ?: throw NotFoundException("Movimiento no encontrado.")
        accountService.getOwned(principal.userId, transaction.accountId) // valida propiedad
        if (transaction.type == TransactionType.CREDIT_CARD_PURCHASE && installmentPlanService.isLinkedToPlan(id)) {
            throw BusinessRuleException("Esta compra es a MSI/MCI: bórrala desde su plan de meses, no aquí.")
        }
        transactionService.delete(principal.userId, id)
        return ResponseEntity.noContent().build()
    }
}
