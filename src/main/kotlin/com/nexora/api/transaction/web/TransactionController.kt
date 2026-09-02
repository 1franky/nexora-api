package com.nexora.api.transaction.web

import com.nexora.api.account.domain.AccountService
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.common.web.ApiError
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.transaction.domain.TransactionService
import com.nexora.api.transaction.domain.TransactionType
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Movimientos", description = "Ingresos, gastos, y edición/borrado de cualquier movimiento (incluye transferencias y tarjeta).")
@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val transactionService: TransactionService,
    private val accountService: AccountService,
    private val installmentPlanService: InstallmentPlanService,
) {

    @Operation(
        summary = "Registrar un ingreso o gasto",
        description = "Solo INCOME/EXPENSE — para transferencias usa POST /api/v1/transfers, y para compras/pagos de tarjeta, /api/v1/credit-cards.",
    )
    @ApiResponse(responseCode = "201", description = "Movimiento registrado.")
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

    @Operation(
        summary = "Listar movimientos",
        description = "Sin accountId, lista los movimientos de todas las cuentas del usuario juntos, más recientes primero.",
    )
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Filtra a una sola cuenta; sin este parámetro trae todas.") @RequestParam(required = false) accountId: UUID?,
    ): List<TransactionResponse> =
        transactionService.listForUser(principal.userId, accountId).map(TransactionResponse::from)

    @Operation(
        summary = "Editar un ingreso o gasto",
        description = "Solo INCOME/EXPENSE — transferencias y compras/pagos de tarjeta tienen sus propios flujos de edición " +
            "(no comparten este endpoint porque afectan más de una cuenta, o tienen reglas propias de MSI/MCI).",
    )
    @ApiResponse(
        responseCode = "400",
        description = "El movimiento no es INCOME/EXPENSE.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del movimiento.") @PathVariable id: UUID,
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

    @Operation(
        summary = "Borrar un movimiento",
        description = "INCOME/EXPENSE revierte el saldo de su cuenta; TRANSFER borra las dos piernas y revierte ambos saldos; " +
            "CREDIT_CARD_PURCHASE revierte la deuda de la tarjeta (rechazada si pertenece a un plan MSI/MCI — bórrala desde " +
            "el plan). CREDIT_CARD_PAYMENT no se puede borrar: revertirlo implicaría des-marcar cuotas MSI/MCI que ese pago " +
            "haya marcado como pagadas, sin historial suficiente para saber cuáles.",
    )
    @ApiResponse(responseCode = "204", description = "Movimiento borrado.")
    @ApiResponse(
        responseCode = "400",
        description = "El movimiento es un pago de tarjeta, o una compra ligada a un plan MSI/MCI.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del movimiento.") @PathVariable id: UUID,
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
