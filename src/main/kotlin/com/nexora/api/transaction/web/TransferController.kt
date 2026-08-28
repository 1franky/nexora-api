package com.nexora.api.transaction.web

import com.nexora.api.transaction.domain.TransactionService
import com.nexora.api.user.security.NexoraUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val transactionService: TransactionService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: CreateTransferRequest,
    ): ResponseEntity<TransferResponse> {
        val result = transactionService.recordTransfer(
            userId = principal.userId,
            fromAccountId = request.fromAccountId,
            toAccountId = request.toAccountId,
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
