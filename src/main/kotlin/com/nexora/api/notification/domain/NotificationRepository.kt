package com.nexora.api.notification.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<Notification>
    fun findAllByUserIdAndStatusOrderByCreatedAtDesc(userId: UUID, status: NotificationStatus): List<Notification>
    fun findByIdAndUserId(id: UUID, userId: UUID): Notification?

    /** Evita generar la misma notificación dos veces para el mismo día lógico (ver [Notification.forDate]). */
    fun existsByUserIdAndTypeAndRelatedEntityIdAndForDate(
        userId: UUID,
        type: NotificationType,
        relatedEntityId: UUID,
        forDate: LocalDate,
    ): Boolean
}
