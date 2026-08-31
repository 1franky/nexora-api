package com.nexora.api.account.web

import com.nexora.api.account.domain.AccountService
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
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val accountService: AccountService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: CreateAccountRequest,
    ): ResponseEntity<AccountResponse> {
        val account = accountService.create(
            userId = principal.userId,
            name = request.name,
            type = request.type,
            currency = request.currency,
            openingBalance = request.openingBalance,
            includeInAvailableBalance = request.includeInAvailableBalance,
            includeInNetWorth = request.includeInNetWorth,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account))
    }

    @GetMapping
    fun list(@AuthenticationPrincipal principal: NexoraUserDetails): List<AccountResponse> =
        accountService.listForUser(principal.userId).map(AccountResponse::from)

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
    ): AccountResponse = AccountResponse.from(accountService.getOwned(principal.userId, id))

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateAccountRequest,
    ): AccountResponse {
        val account = accountService.update(
            userId = principal.userId,
            accountId = id,
            name = request.name,
            includeInAvailableBalance = request.includeInAvailableBalance,
            includeInNetWorth = request.includeInNetWorth,
        )
        return AccountResponse.from(account)
    }

    @GetMapping("/summary")
    fun summary(@AuthenticationPrincipal principal: NexoraUserDetails): BalanceSummaryResponse =
        BalanceSummaryResponse.from(accountService.getBalanceSummary(principal.userId))
}
