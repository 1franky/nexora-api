package com.nexora.api.installment.web

import com.nexora.api.common.web.ApiError
import com.nexora.api.installment.domain.InstallmentPlanService
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Compras a MSI/MCI (plan.md, sección 6). Se crean anidadas bajo la
 * tarjeta (`/credit-cards/{cardId}/installment-plans`, igual que compras y
 * pagos en B3); una vez creadas se consultan/operan por su propio id.
 */
@Tag(name = "Planes MSI/MCI", description = "Compras a meses (con o sin intereses) de una tarjeta y el calendario de sus cuotas.")
@RestController
class InstallmentPlanController(
    private val installmentPlanService: InstallmentPlanService,
) {

    @Operation(
        summary = "Comprar a MSI/MCI",
        description = "Registra la compra por su monto total (original + interés si es MCI) como una única compra de tarjeta, " +
            "y genera el calendario de cuotas mensuales a partir de la próxima fecha límite de pago de la tarjeta.",
    )
    @ApiResponse(responseCode = "201", description = "Plan creado.")
    @PostMapping("/api/v1/credit-cards/{cardId}/installment-plans")
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la tarjeta.") @PathVariable cardId: UUID,
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

    @Operation(summary = "Listar los planes de una tarjeta", description = "Incluye planes ACTIVE, COMPLETED y CANCELLED.")
    @GetMapping("/api/v1/credit-cards/{cardId}/installment-plans")
    fun listForCard(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la tarjeta.") @PathVariable cardId: UUID,
    ): List<InstallmentPlanResponse> =
        installmentPlanService.listForCreditCard(principal.userId, cardId).map(InstallmentPlanResponse::from)

    @Operation(summary = "Consultar un plan", description = "Incluye el calendario completo de cuotas (pagadas y pendientes).")
    @GetMapping("/api/v1/installment-plans/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del plan.") @PathVariable id: UUID,
    ): InstallmentPlanResponse = InstallmentPlanResponse.from(installmentPlanService.getOwned(principal.userId, id))

    @Operation(
        summary = "Editar un plan",
        description = "Sin cuotas pagadas: recalcula el total y regenera todas las cuotas si cambia monto/plazo/tasa. " +
            "Con alguna cuota ya pagada: monto, plazo e interés quedan bloqueados (rompería lo ya cobrado) — solo se " +
            "puede editar comercio, categoría, descripción y referencia.",
    )
    @ApiResponse(
        responseCode = "400",
        description = "Se intentó cambiar monto/plazo/interés con alguna cuota ya pagada.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PutMapping("/api/v1/installment-plans/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del plan.") @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateInstallmentPlanRequest,
    ): InstallmentPlanResponse {
        val view = installmentPlanService.update(
            userId = principal.userId,
            planId = id,
            amount = request.amount,
            date = request.date,
            merchant = request.merchant,
            installmentCount = request.installmentCount,
            interestRate = request.interestRate,
            categoryId = request.categoryId,
            description = request.description,
            reference = request.reference,
        )
        return InstallmentPlanResponse.from(view)
    }

    @Operation(
        summary = "Marcar una cuota como pagada",
        description = "Solo seguimiento/reporte — no vuelve a mover el saldo de la tarjeta (eso ya ocurrió con la compra " +
            "original, y ocurre de nuevo con cada pago real vía POST /credit-cards/{id}/payments, que además marca " +
            "automáticamente la cuota del ciclo que cubre).",
    )
    @ApiResponse(
        responseCode = "400",
        description = "La cuota ya estaba marcada como pagada.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PostMapping("/api/v1/installment-plans/{id}/installments/{installmentId}/pay")
    fun payInstallment(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del plan.") @PathVariable id: UUID,
        @Parameter(description = "Id de la cuota.") @PathVariable installmentId: UUID,
    ): InstallmentPlanResponse {
        val view = installmentPlanService.payInstallment(principal.userId, id, installmentId)
        return InstallmentPlanResponse.from(view)
    }
}
