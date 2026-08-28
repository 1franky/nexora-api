package com.nexora.api.dashboard.web

import com.nexora.api.dashboard.domain.DashboardService
import com.nexora.api.user.security.NexoraUserDetails
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(
    private val dashboardService: DashboardService,
) {

    /**
     * Métricas del dashboard (plan.md, sección 10). [month] controla el
     * periodo de las métricas mensuales (ingresos/gastos/categorías);
     * por defecto es el mes actual — "Configurar el periodo de algunas
     * métricas" de la sección 10 se resuelve así por ahora, sin necesidad
     * de persistir preferencias de widgets todavía.
     */
    @GetMapping
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") month: YearMonth?,
        @RequestParam(required = false, defaultValue = "10") recentTransactionsLimit: Int,
    ): DashboardResponse {
        val view = dashboardService.getDashboard(
            userId = principal.userId,
            month = month ?: YearMonth.now(),
            recentTransactionsLimit = recentTransactionsLimit.coerceIn(1, 50),
        )
        return DashboardResponse.from(view)
    }
}
