package com.nexora.api.notification.web

import com.nexora.api.notification.domain.Notification
import com.nexora.api.notification.domain.NotificationStatus
import com.nexora.api.notification.domain.NotificationType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class NotificationResponse(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val message: String,
    @field:Schema(description = "Id de la tarjeta o cuota a la que se refiere el aviso (según type), para poder navegar a su detalle.")
    val relatedEntityId: UUID?,
    val status: NotificationStatus,
    val createdAt: Instant,
    val readAt: Instant?,
) {
    companion object {
        fun from(notification: Notification) = NotificationResponse(
            id = requireNotNull(notification.id),
            type = notification.type,
            title = notification.title,
            message = notification.message,
            relatedEntityId = notification.relatedEntityId,
            status = notification.status,
            createdAt = requireNotNull(notification.createdAt),
            readAt = notification.readAt,
        )
    }
}
