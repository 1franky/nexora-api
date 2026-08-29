package com.nexora.api.report.web

import com.nexora.api.report.domain.ReportService
import com.nexora.api.transaction.domain.TransactionType
import com.nexora.api.user.security.NexoraUserDetails
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

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
    @GetMapping
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) type: TransactionType?,
    ): ReportResponse {
        val view = reportService.getReport(principal.userId, from, to, accountId, type)
        return ReportResponse.from(view)
    }
}
