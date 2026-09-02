package com.nexora.api.report.web

import com.nexora.api.report.domain.ReportService
import com.nexora.api.transaction.domain.TransactionType
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@Tag(name = "Reportes", description = "Reporte financiero por rango de fechas libre, opcionalmente acotado a una cuenta o tipo de movimiento.")
@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val reportService: ReportService,
) {

    /**
     * Reporte financiero por rango de fechas libre (nexora-web W6, plan.md
     * sección 9): a diferencia de /dashboard, [from]/[to] son arbitrarios y
     * el resultado puede acotarse a una sola cuenta o tipo de movimiento.
     */
    @Operation(
        summary = "Generar un reporte",
        description = "A diferencia de /dashboard (mes actual), from/to son un rango de fechas arbitrario elegido por el usuario.",
    )
    @GetMapping
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Fecha inicial, inclusiva.") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "Fecha final, inclusiva.") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @Parameter(description = "Acota el reporte a una sola cuenta.") @RequestParam(required = false) accountId: UUID?,
        @Parameter(description = "Acota el reporte a un solo tipo de movimiento (INCOME, EXPENSE, etc.).") @RequestParam(required = false) type: TransactionType?,
    ): ReportResponse {
        val view = reportService.getReport(principal.userId, from, to, accountId, type)
        return ReportResponse.from(view)
    }
}
