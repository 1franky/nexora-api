package com.nexora.api.scheduledcharge.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class ScheduledChargeFrequency {
    MONTHLY,
    YEARLY,
}

/**
 * - ACTIVE: genera movimientos en cada ocurrencia.
 * - PAUSED: no genera; al reanudar sigue desde hoy, sin recuperar lo del periodo pausado.
 *   También queda así cuando un cargo no se pudo registrar (ver [ScheduledCharge.lastError]).
 * - FINISHED: ya pasó su [ScheduledCharge.endDate].
 * - CANCELLED: baja lógica; los movimientos ya generados se conservan.
 */
enum class ScheduledChargeStatus {
    ACTIVE,
    PAUSED,
    FINISHED,
    CANCELLED,
}

/**
 * Cargo programado (B14, plan-cargos-programados.md): una regla del tipo
 * "$119, Disney+, tarjeta X, cada día 15" que [ScheduledChargeService]
 * convierte en un movimiento real en su fecha — CREDIT_CARD_PURCHASE si
 * [accountId] es una tarjeta, EXPENSE si es débito/ahorro.
 *
 * [nextRunDate] es el cursor: el job registra mientras sea <= hoy y lo
 * avanza a la siguiente ocurrencia. Así los cargos atrasados salen solos, y
 * borrar un movimiento ya generado no lo vuelve a generar.
 */
@Entity
@Table(name = "scheduled_charges")
class ScheduledCharge(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "account_id", nullable = false)
    var accountId: UUID,

    /** "Disney+" — también es el comercio de la compra cuando la cuenta es una tarjeta. */
    @Column(nullable = false, length = 120)
    var name: String,

    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal,

    @Column(name = "category_id")
    var categoryId: UUID? = null,

    @Column(length = 500)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var frequency: ScheduledChargeFrequency,

    @Column(name = "day_of_month", nullable = false)
    var dayOfMonth: Int,

    /** Solo para [ScheduledChargeFrequency.YEARLY]. */
    @Column(name = "month_of_year")
    var monthOfYear: Int? = null,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    /** Inclusive. */
    @Column(name = "end_date")
    var endDate: LocalDate? = null,

    /** null cuando está FINISHED/CANCELLED. */
    @Column(name = "next_run_date")
    var nextRunDate: LocalDate? = null,

    @Column(name = "last_run_date")
    var lastRunDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ScheduledChargeStatus = ScheduledChargeStatus.ACTIVE,

    /** Por qué se pausó automáticamente (cuenta o categoría archivada); se limpia al reanudar. */
    @Column(name = "last_error", length = 500)
    var lastError: String? = null,

) : BaseEntity() {

    fun firstOccurrenceOnOrAfter(date: LocalDate): LocalDate =
        ScheduleCalculator.firstOnOrAfter(frequency, dayOfMonth, monthOfYear, date)

    fun occurrenceAfter(date: LocalDate): LocalDate =
        ScheduleCalculator.nextAfter(frequency, dayOfMonth, monthOfYear, date)
}
