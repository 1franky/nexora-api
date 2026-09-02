package com.nexora.api.account.web

import com.nexora.api.account.domain.AccountService
import com.nexora.api.common.web.ApiError
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

@Tag(name = "Cuentas", description = "Cuentas de débito/ahorro/AFORE/PPR y tarjetas de crédito (estas últimas también en /credit-cards).")
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val accountService: AccountService,
) {

    @Operation(summary = "Crear una cuenta", description = "DEBIT, SAVINGS, AFORE o PPR. Las tarjetas de crédito se crean vía POST /api/v1/credit-cards, no aquí.")
    @ApiResponse(responseCode = "201", description = "Cuenta creada.")
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

    @Operation(summary = "Listar cuentas", description = "Todas las cuentas del usuario (incluye tarjetas de crédito), ordenadas por nombre.")
    @GetMapping
    fun list(@AuthenticationPrincipal principal: NexoraUserDetails): List<AccountResponse> =
        accountService.listForUser(principal.userId).map(AccountResponse::from)

    @Operation(summary = "Consultar una cuenta")
    @ApiResponse(
        responseCode = "404",
        description = "No existe, o pertenece a otro usuario (se trata igual que \"no existe\", nunca se revela la existencia ajena).",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la cuenta.") @PathVariable id: UUID,
    ): AccountResponse = AccountResponse.from(accountService.getOwned(principal.userId, id))

    @Operation(
        summary = "Editar nombre e inclusión en disponible/patrimonio",
        description = "Tipo, moneda y saldo quedan fuera a propósito: cambiarlos rompería el significado del historial de movimientos ya registrados sobre esta cuenta.",
    )
    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la cuenta.") @PathVariable id: UUID,
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

    @Operation(
        summary = "Disponible y patrimonio neto",
        description = "Suma de las cuentas marcadas includeInAvailableBalance/includeInNetWorth, convertidas a MXN si están en otra moneda (ver ExchangeRateService).",
    )
    @GetMapping("/summary")
    fun summary(@AuthenticationPrincipal principal: NexoraUserDetails): BalanceSummaryResponse =
        BalanceSummaryResponse.from(accountService.getBalanceSummary(principal.userId))
}
