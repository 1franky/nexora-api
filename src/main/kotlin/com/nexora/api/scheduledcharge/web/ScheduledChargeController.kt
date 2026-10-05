package com.nexora.api.scheduledcharge.web

import com.nexora.api.common.web.ApiError
import com.nexora.api.scheduledcharge.domain.ScheduledChargeService
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Cargos programados (B14, plan-cargos-programados.md): suscripciones y
 * domiciliaciones que se registran solas cada mes/año en una tarjeta
 * (como compra) o en una cuenta de débito/ahorro (como gasto).
 */
@Tag(name = "Cargos programados", description = "Suscripciones/domiciliaciones que generan un movimiento automáticamente en su fecha.")
@RestController
@RequestMapping("/api/v1/scheduled-charges")
class ScheduledChargeController(
    private val scheduledChargeService: ScheduledChargeService,
) {

    @Operation(
        summary = "Crear un cargo programado",
        description = "Si la fecha de inicio es pasada, registra de inmediato los cargos atrasados hasta hoy.",
    )
    @ApiResponse(responseCode = "201", description = "Cargo programado creado.")
    @ApiResponse(
        responseCode = "400",
        description = "Cuenta AFORE/PPR o inactiva, categoría no válida, o reglas de fecha inconsistentes.",
        content = [Content(schema = Schema(implementation = ApiError::class))],
    )
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: ScheduledChargeRequest,
    ): ResponseEntity<ScheduledChargeResponse> {
        val view = scheduledChargeService.create(principal.userId, request.toInput())
        return ResponseEntity.status(HttpStatus.CREATED).body(ScheduledChargeResponse.from(view))
    }

    @Operation(summary = "Listar cargos programados", description = "Activos y pausados primero (por próximo cargo), luego terminados y cancelados.")
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Solo los de esta cuenta.") @RequestParam(required = false) accountId: UUID?,
    ): List<ScheduledChargeResponse> =
        scheduledChargeService.listForUser(principal.userId, accountId).map(ScheduledChargeResponse::from)

    @Operation(summary = "Consultar un cargo programado")
    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del cargo programado.") @PathVariable id: UUID,
    ): ScheduledChargeResponse = ScheduledChargeResponse.from(scheduledChargeService.getOwned(principal.userId, id))

    @Operation(
        summary = "Editar un cargo programado",
        description = "Solo aplica hacia adelante: los movimientos ya generados no cambian. La fecha de inicio no se " +
            "puede cambiar si el cargo ya generó movimientos.",
    )
    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del cargo programado.") @PathVariable id: UUID,
        @Valid @RequestBody request: ScheduledChargeRequest,
    ): ScheduledChargeResponse =
        ScheduledChargeResponse.from(scheduledChargeService.update(principal.userId, id, request.toInput()))

    @Operation(summary = "Pausar un cargo programado")
    @PostMapping("/{id}/pause")
    fun pause(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del cargo programado.") @PathVariable id: UUID,
    ): ScheduledChargeResponse = ScheduledChargeResponse.from(scheduledChargeService.pause(principal.userId, id))

    @Operation(
        summary = "Reanudar un cargo programado",
        description = "Sigue desde hoy: lo que habría tocado mientras estuvo pausado no se registra.",
    )
    @PostMapping("/{id}/resume")
    fun resume(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del cargo programado.") @PathVariable id: UUID,
    ): ScheduledChargeResponse = ScheduledChargeResponse.from(scheduledChargeService.resume(principal.userId, id))

    @Operation(
        summary = "Cancelar un cargo programado",
        description = "Baja lógica: deja de generar movimientos, pero los ya registrados se conservan.",
    )
    @ApiResponse(responseCode = "204", description = "Cancelado.")
    @DeleteMapping("/{id}")
    fun cancel(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del cargo programado.") @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        scheduledChargeService.cancel(principal.userId, id)
        return ResponseEntity.noContent().build()
    }
}
