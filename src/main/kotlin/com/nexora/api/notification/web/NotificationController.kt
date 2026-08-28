package com.nexora.api.notification.web

import com.nexora.api.notification.domain.NotificationService
import com.nexora.api.user.security.NexoraUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {

    /** Genera al vuelo lo que falte para el usuario y devuelve la lista actualizada. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @RequestParam(required = false, defaultValue = "false") unreadOnly: Boolean,
    ): List<NotificationResponse> {
        notificationService.generateForUser(principal.userId)
        return notificationService.listForUser(principal.userId, unreadOnly).map(NotificationResponse::from)
    }

    @PostMapping("/{id}/read")
    fun markAsRead(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @PathVariable id: UUID,
    ): NotificationResponse = NotificationResponse.from(notificationService.markAsRead(principal.userId, id))

    @PostMapping("/read-all")
    fun markAllAsRead(@AuthenticationPrincipal principal: NexoraUserDetails): ResponseEntity<Void> {
        notificationService.markAllAsRead(principal.userId)
        return ResponseEntity.noContent().build()
    }
}
