package com.nexora.api.creditcard.web

import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.creditcard.domain.CreditCardService
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.transaction.domain.TransactionService
import com.nexora.api.transaction.web.TransactionResponse
import com.nexora.api.transaction.web.TransferResponse
import com.nexora.api.user.security.NexoraUserDetails
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

@RestController
@RequestMapping("/api/v1/credit-cards")
class CreditCardController(
    private val creditCardService: CreditCardService,
    private val transactionService: TransactionService,
    private val installmentPlanService: InstallmentPlanService,
) {

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

    @GetMapping
    fun list(@AuthenticationPrincipal principal: NexoraUserDetails): List<CreditCardResponse> =
        creditCardService.listForUser(principal.userId).map(CreditCardResponse::from)

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
    ): CreditCardResponse = CreditCardResponse.from(creditCardService.getOwned(principal.userId, id))

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
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

    @PostMapping("/{id}/purchases")
    fun purchase(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
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

    @PutMapping("/{id}/purchases/{transactionId}")
    fun updatePurchase(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
        @PathVariable transactionId: UUID,
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

    @PostMapping("/{id}/payments")
    fun pay(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
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
        val body = TransferResponse(
            outgoing = TransactionResponse.from(result.outgoing),
            incoming = TransactionResponse.from(result.incoming),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(body)
    }
}
