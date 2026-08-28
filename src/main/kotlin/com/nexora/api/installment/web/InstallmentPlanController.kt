package com.nexora.api.installment.web

import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.user.security.NexoraUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Compras a MSI/MCI (plan.md, sección 6). Se crean anidadas bajo la
 * tarjeta (`/credit-cards/{cardId}/installment-plans`, igual que compras y
 * pagos en B3); una vez creadas se consultan/operan por su propio id.
 */
@RestController
class InstallmentPlanController(
    private val installmentPlanService: InstallmentPlanService,
) {

    @PostMapping("/api/v1/credit-cards/{cardId}/installment-plans")
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable cardId: UUID,
        @Valid @RequestBody request: CreateInstallmentPlanRequest,
    ): ResponseEntity<InstallmentPlanResponse> {
        val view = installmentPlanService.create(
            userId = principal.userId,
            creditCardId = cardId,
            amount = request.amount,
            date = request.date,
            merchant = request.merchant,
            installmentCount = request.installmentCount,
            interestRate = request.interestRate,
            categoryId = request.categoryId,
            description = request.description,
            reference = request.reference,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(InstallmentPlanResponse.from(view))
    }

    @GetMapping("/api/v1/credit-cards/{cardId}/installment-plans")
    fun listForCard(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable cardId: UUID,
    ): List<InstallmentPlanResponse> =
        installmentPlanService.listForCreditCard(principal.userId, cardId).map(InstallmentPlanResponse::from)

    @GetMapping("/api/v1/installment-plans/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
    ): InstallmentPlanResponse = InstallmentPlanResponse.from(installmentPlanService.getOwned(principal.userId, id))

    @PostMapping("/api/v1/installment-plans/{id}/installments/{installmentId}/pay")
    fun payInstallment(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
        @PathVariable installmentId: UUID,
    ): InstallmentPlanResponse {
        val view = installmentPlanService.payInstallment(principal.userId, id, installmentId)
        return InstallmentPlanResponse.from(view)
    }
}
