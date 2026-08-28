package com.nexora.api.transaction.web

import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.transaction.domain.TransactionService
import com.nexora.api.transaction.domain.TransactionType
import com.nexora.api.user.security.NexoraUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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

    @GetMapping
    fun listByAccount(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @RequestParam accountId: UUID,
    ): List<TransactionResponse> =
        transactionService.listForAccount(principal.userId, accountId).map(TransactionResponse::from)
}
