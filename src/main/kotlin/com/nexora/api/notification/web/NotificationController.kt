package com.nexora.api.notification.web

import com.nexora.api.notification.domain.NotificationService
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(
    name = "Notificaciones",
    description = "Avisos generados a partir de PAYMENT_DUE/PAYMENT_DUE_SOON/INSTALLMENT_DUE. " +
        "PAYMENT_OVERDUE/BUDGET_EXCEEDED/UNUSUAL_EXPENSE están declarados pero aún no se disparan (B7, issue #7).",
)
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {

    @Operation(summary = "Listar notificaciones", description = "Genera al vuelo lo que falte para el usuario y devuelve la lista actualizada.")
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Si es true, solo trae las no leídas.") @RequestParam(required = false, defaultValue = "false") unreadOnly: Boolean,
    ): List<NotificationResponse> {
        notificationService.generateForUser(principal.userId)
        return notificationService.listForUser(principal.userId, unreadOnly).map(NotificationResponse::from)
    }

    @Operation(summary = "Marcar una notificación como leída")
    @PostMapping("/{id}/read")
    fun markAsRead(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la notificación.") @PathVariable id: UUID,
    ): NotificationResponse = NotificationResponse.from(notificationService.markAsRead(principal.userId, id))

    @Operation(summary = "Marcar todas las notificaciones como leídas")
    @PostMapping("/read-all")
    fun markAllAsRead(@AuthenticationPrincipal principal: NexoraUserDetails): ResponseEntity<Void> {
        notificationService.markAllAsRead(principal.userId)
        return ResponseEntity.noContent().build()
    }
}
