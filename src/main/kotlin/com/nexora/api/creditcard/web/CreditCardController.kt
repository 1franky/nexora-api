package com.nexora.api.creditcard.web

import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.web.ApiError
import com.nexora.api.creditcard.domain.CreditCardService
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.transaction.domain.TransactionService
import com.nexora.api.transaction.web.TransactionResponse
import com.nexora.api.transaction.web.TransferResponse
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(name = "Tarjetas de crédito", description = "Alta/edición de tarjetas, compras de contado y pagos. Las compras a MSI/MCI viven en /installment-plans.")
@RestController
@RequestMapping("/api/v1/credit-cards")
class CreditCardController(
    private val creditCardService: CreditCardService,
    private val transactionService: TransactionService,
    private val installmentPlanService: InstallmentPlanService,
) {

    @Operation(summary = "Crear una tarjeta de crédito", description = "También crea la Account subyacente (tipo CREDIT_CARD) con saldo inicial 0.")
    @ApiResponse(responseCode = "201", description = "Tarjeta creada.")
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: CreateCreditCardRequest,
    ): ResponseEntity<CreditCardResponse> {
        val view = creditCardService.create(
            userId = principal.userId,
            name = request.name,
            bank = request.bank,
            last4 = request.last4,
            creditLimit = request.creditLimit,
            closingDay = request.closingDay,
            paymentDueDay = request.paymentDueDay,
            currency = request.currency,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(CreditCardResponse.from(view))
    }

    @Operation(summary = "Listar tarjetas de crédito")
    @GetMapping
    fun list(@AuthenticationPrincipal principal: NexoraUserDetails): List<CreditCardResponse> =
        creditCardService.listForUser(principal.userId).map(CreditCardResponse::from)

    @Operation(summary = "Consultar una tarjeta", description = "Incluye deuda actual, crédito disponible y próxima fecha de corte/pago, ya calculadas para hoy.")
    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la tarjeta.") @PathVariable id: UUID,
    ): CreditCardResponse = CreditCardResponse.from(creditCardService.getOwned(principal.userId, id))

    @Operation(
        summary = "Editar nombre, banco, límite y días de corte/pago",
        description = "No cambia los planes MSI/MCI ya generados: sus cuotas quedan con las fechas calculadas al momento de la compra.",
    )
    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la tarjeta.") @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateCreditCardRequest,
    ): CreditCardResponse {
        val view = creditCardService.update(
            userId = principal.userId,
            creditCardId = id,
            name = request.name,
            bank = request.bank,
            creditLimit = request.creditLimit,
            closingDay = request.closingDay,
            paymentDueDay = request.paymentDueDay,
        )
        return CreditCardResponse.from(view)
    }

    @Operation(
        summary = "Registrar una compra de contado",
        description = "Compra \"de contado\" (sin MSI/MCI, eso es POST /credit-cards/{id}/installment-plans). Aumenta la deuda por el monto completo.",
    )
    @ApiResponse(responseCode = "201", description = "Compra registrada.")
    @PostMapping("/{id}/purchases")
    fun purchase(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la tarjeta.") @PathVariable id: UUID,
        @Valid @RequestBody request: CreditCardPurchaseRequest,
    ): ResponseEntity<TransactionResponse> {
        val card = creditCardService.getOwned(principal.userId, id) // valida propiedad de la tarjeta
        val transaction = transactionService.recordCreditCardPurchase(
            userId = principal.userId,
            cardAccountId = card.account.id!!,
            amount = request.amount,
            date = request.date,
            merchant = request.merchant,
            categoryId = request.categoryId,
            description = request.description,
            reference = request.reference,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(transaction))
    }

    @Operation(
        summary = "Editar una compra de contado",
        description = "Reajusta la deuda de la tarjeta por la diferencia. Rechazada si la compra pertenece a un plan MSI/MCI — esas se editan desde PUT /installment-plans/{id}.",
    )
    @ApiResponse(
        responseCode = "400",
        description = "La compra pertenece a un plan MSI/MCI.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PutMapping("/{id}/purchases/{transactionId}")
    fun updatePurchase(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la tarjeta.") @PathVariable id: UUID,
        @Parameter(description = "Id del movimiento de la compra.") @PathVariable transactionId: UUID,
        @Valid @RequestBody request: UpdateCreditCardPurchaseRequest,
    ): TransactionResponse {
        val card = creditCardService.getOwned(principal.userId, id) // valida propiedad de la tarjeta
        if (installmentPlanService.isLinkedToPlan(transactionId)) {
            throw BusinessRuleException("Esta compra es a MSI/MCI: edítala desde su plan de meses, no aquí.")
        }
        val transaction = transactionService.updateCreditCardPurchase(
            userId = principal.userId,
            cardAccountId = card.account.id!!,
            transactionId = transactionId,
            amount = request.amount,
            date = request.date,
            merchant = request.merchant,
            categoryId = request.categoryId,
            description = request.description,
            reference = request.reference,
        )
        return TransactionResponse.from(transaction)
    }

    @Operation(
        summary = "Pagar la tarjeta",
        description = "Transferencia (dos filas ligadas) desde otra cuenta del usuario — no AFORE/PPR ni otra tarjeta de crédito. " +
            "También marca como pagadas las cuotas MSI/MCI pendientes del ciclo que este pago cubre " +
            "(ver InstallmentPlanService.markDueInstallmentsAsPaidByPayment).",
    )
    @ApiResponse(responseCode = "201", description = "Pago registrado.")
    @ApiResponse(
        responseCode = "400",
        description = "La cuenta origen es otra tarjeta de crédito, o una cuenta AFORE/PPR.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PostMapping("/{id}/payments")
    fun pay(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la tarjeta.") @PathVariable id: UUID,
        @Valid @RequestBody request: CreditCardPaymentRequest,
    ): ResponseEntity<TransferResponse> {
        val card = creditCardService.getOwned(principal.userId, id) // valida propiedad de la tarjeta
        val result = transactionService.recordCreditCardPayment(
            userId = principal.userId,
            fromAccountId = request.fromAccountId,
            cardAccountId = card.account.id!!,
            amount = request.amount,
            date = request.date,
            description = request.description,
            reference = request.reference,
        )
        // Nota de producto: un pago marca como pagadas las cuotas MSI/MCI pendientes
        // del ciclo que cubre — ver InstallmentPlanService.markDueInstallmentsAsPaidByPayment.
        installmentPlanService.markDueInstallmentsAsPaidByPayment(principal.userId, id, request.date)
        val body = TransferResponse(
            outgoing = TransactionResponse.from(result.outgoing),
            incoming = TransactionResponse.from(result.incoming),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(body)
    }
}
