package com.nexora.api.notification.domain

import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.creditcard.domain.CreditCardService
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.installment.domain.InstallmentRepository
import com.nexora.api.installment.domain.InstallmentStatus
import com.nexora.api.user.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val DUE_SOON_WINDOW_DAYS = 3L

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val creditCardService: CreditCardService,
    private val installmentPlanService: InstallmentPlanService,
    private val installmentRepository: InstallmentRepository,
) {

    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * Revisa tarjetas y cuotas del usuario y genera las notificaciones que
     * falten (no duplica si ya se generó una del mismo tipo/entidad hoy).
     * Se llama tanto al listar (para que el usuario siempre vea datos al
     * día al abrir la app) como desde [NotificationScheduler] (para poder
     * empujarlas luego por push/email aunque el usuario no abra la app).
     */
    @Transactional
    fun generateForUser(userId: UUID, today: LocalDate = LocalDate.now()): List<Notification> {
        val created = mutableListOf<Notification>()

        val cards = creditCardService.listForUser(userId)
        for (card in cards) {
            if (card.currentDebt <= BigDecimal.ZERO) continue
            val daysUntilDue = ChronoUnit.DAYS.between(today, card.nextPaymentDueDate)
            val type = when {
                daysUntilDue == 0L -> NotificationType.PAYMENT_DUE
                daysUntilDue in 1..DUE_SOON_WINDOW_DAYS -> NotificationType.PAYMENT_DUE_SOON
                else -> null
            } ?: continue

            val amount = card.currentDebt.setScale(2, RoundingMode.HALF_UP)
            val message = if (type == NotificationType.PAYMENT_DUE) {
                "Tu tarjeta ${card.creditCard.name} vence hoy. Pago estimado: \$$amount."
            } else {
                "Tu tarjeta ${card.creditCard.name} vence en $daysUntilDue día(s). Pago estimado: \$$amount."
            }
            ensureNotification(userId, type, requireNotNull(card.creditCard.id), "Pago de tarjeta", message, today)
                ?.let { created += it }
        }

        val activePlans = installmentPlanService.listActivePlansForUser(userId)
        if (activePlans.isNotEmpty()) {
            val cardNameByCardId = cards.associate { it.creditCard.id to it.creditCard.name }
            val planIds = activePlans.mapNotNull { it.id }
            val dueInstallments = installmentRepository.findAllByInstallmentPlanIdInAndStatusAndDueDateBetween(
                planIds, InstallmentStatus.PENDING, today, today.plusDays(DUE_SOON_WINDOW_DAYS),
            )
            val planById = activePlans.associateBy { it.id }
            for (installment in dueInstallments) {
                val plan = planById[installment.installmentPlanId] ?: continue
                val daysUntilDue = ChronoUnit.DAYS.between(today, installment.dueDate)
                val cardName = cardNameByCardId[plan.creditCardId] ?: "tu tarjeta"
                val amount = installment.amount.setScale(2, RoundingMode.HALF_UP)
                val message = if (daysUntilDue == 0L) {
                    "Tu cuota ${installment.number}/${plan.installmentCount} de $cardName vence hoy. Monto: \$$amount."
                } else {
                    "Tu cuota ${installment.number}/${plan.installmentCount} de $cardName vence en $daysUntilDue día(s). Monto: \$$amount."
                }
                ensureNotification(
                    userId, NotificationType.INSTALLMENT_DUE, requireNotNull(installment.id), "Cuota por vencer", message, today,
                )?.let { created += it }
            }
        }

        return created
    }

    /**
     * Corre la generación para todos los usuarios (usado por el scheduler
     * diario). Cada usuario se procesa en su propia transacción (vía
     * [generateForUser]); si uno falla no debe afectar a los demás.
     */
    fun generateForAllUsers(today: LocalDate = LocalDate.now()) {
        userRepository.findAll().forEach { user ->
            try {
                generateForUser(requireNotNull(user.id), today)
            } catch (ex: Exception) {
                log.warn("No se pudieron generar notificaciones para el usuario {}", user.id, ex)
            }
        }
    }

    fun listForUser(userId: UUID, unreadOnly: Boolean): List<Notification> =
        if (unreadOnly) notificationRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, NotificationStatus.UNREAD)
        else notificationRepository.findAllByUserIdOrderByCreatedAtDesc(userId)

    @Transactional
    fun markAsRead(userId: UUID, notificationId: UUID): Notification {
        val notification = notificationRepository.findByIdAndUserId(notificationId, userId)
            ?: throw NotFoundException("Notificación no encontrada.")
        if (notification.status == NotificationStatus.UNREAD) {
            notification.status = NotificationStatus.READ
            notification.readAt = Instant.now()
            notificationRepository.save(notification)
        }
        return notification
    }

    @Transactional
    fun markAllAsRead(userId: UUID) {
        val unread = notificationRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, NotificationStatus.UNREAD)
        val now = Instant.now()
        unread.forEach {
            it.status = NotificationStatus.READ
            it.readAt = now
        }
        notificationRepository.saveAll(unread)
    }

    private fun ensureNotification(
        userId: UUID,
        type: NotificationType,
        relatedEntityId: UUID,
        title: String,
        message: String,
        today: LocalDate,
    ): Notification? {
        val alreadyGeneratedForThisDay = notificationRepository.existsByUserIdAndTypeAndRelatedEntityIdAndForDate(
            userId, type, relatedEntityId, today,
        )
        if (alreadyGeneratedForThisDay) return null
        return notificationRepository.save(Notification(userId, type, title, message, relatedEntityId, today))
    }
}
