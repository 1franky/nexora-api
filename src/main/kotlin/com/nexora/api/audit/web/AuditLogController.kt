package com.nexora.api.audit.web

import com.nexora.api.audit.domain.AuditLogService
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(
    name = "Auditoría",
    description = "Historial de eventos financieros del usuario (compras, pagos, movimientos, planes MSI/MCI) — plan.md, sección 13.",
)
@RestController
@RequestMapping("/api/v1/audit-log")
class AuditLogController(
    private val auditLogService: AuditLogService,
) {

    @Operation(
        summary = "Listar el historial de auditoría",
        description = "Más recientes primero. Sobrevive al borrado de la entidad afectada (entityId puede ya no existir).",
    )
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Cuántos eventos traer (1-200).") @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<AuditLogResponse> =
        auditLogService.listForUser(principal.userId, limit.coerceIn(1, 200)).map(AuditLogResponse::from)
}
