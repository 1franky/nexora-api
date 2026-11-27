package com.nexora.api.budget.web

import com.nexora.api.budget.domain.BudgetService
import com.nexora.api.common.web.ApiError
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
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
import java.time.YearMonth
import java.util.UUID

/**
 * Presupuestos mensuales (B15, plan.md sección 22): un tope en MXN por categoría de gasto o
 * general. Avisan con BUDGET_NEAR_LIMIT (80 %) y BUDGET_EXCEEDED (> 100 %) en /notifications.
 */
@Tag(name = "Presupuestos", description = "Topes de gasto mensuales por categoría o generales, con lo gastado en el mes.")
@RestController
@RequestMapping("/api/v1/budgets")
class BudgetController(
    private val budgetService: BudgetService,
) {

    @Operation(summary = "Crear un presupuesto", description = "Uno por categoría de gasto y uno general por usuario.")
    @ApiResponse(responseCode = "201", description = "Presupuesto creado, con lo gastado en el mes en curso.")
    @ApiResponse(
        responseCode = "400",
        description = "Monto no positivo, categoría de ingreso o archivada.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @ApiResponse(
        responseCode = "409",
        description = "Ya existe un presupuesto para esa categoría (o uno general).",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: BudgetRequest,
    ): ResponseEntity<BudgetResponse> {
        val budget = budgetService.create(principal.userId, request.categoryId, request.amount)
        val progress = budgetService.progressOf(principal.userId, requireNotNull(budget.id), YearMonth.now())
        return ResponseEntity.status(HttpStatus.CREATED).body(BudgetResponse.from(progress))
    }

    @Operation(summary = "Listar presupuestos con lo gastado", description = "El general primero y luego por nombre de categoría.")
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Mes a calcular (yyyy-MM). Por omisión, el actual.")
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") month: YearMonth?,
    ): List<BudgetResponse> =
        budgetService.progressForUser(principal.userId, month ?: YearMonth.now()).map(BudgetResponse::from)

    @Operation(summary = "Consultar un presupuesto con lo gastado")
    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del presupuesto.") @PathVariable id: UUID,
        @Parameter(description = "Mes a calcular (yyyy-MM). Por omisión, el actual.")
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") month: YearMonth?,
    ): BudgetResponse = BudgetResponse.from(budgetService.progressOf(principal.userId, id, month ?: YearMonth.now()))

    @Operation(summary = "Cambiar el monto de un presupuesto", description = "Aplica al mes en curso y a los siguientes.")
    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del presupuesto.") @PathVariable id: UUID,
        @Valid @RequestBody request: BudgetAmountRequest,
    ): BudgetResponse {
        budgetService.updateAmount(principal.userId, id, request.amount)
        return BudgetResponse.from(budgetService.progressOf(principal.userId, id, YearMonth.now()))
    }

    @Operation(summary = "Borrar un presupuesto", description = "Las notificaciones que ya generó se conservan.")
    @ApiResponse(responseCode = "204", description = "Borrado.")
    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del presupuesto.") @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        budgetService.delete(principal.userId, id)
        return ResponseEntity.noContent().build()
    }
}
