package com.nexora.api.scheduledcharge.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * Fechas de un cargo programado (B14, plan-cargos-programados.md sección 2).
 * Funciones puras, sin estado, para poder probarlas sin Spring.
 *
 * Si el mes no tiene el día configurado (31 en abril, 29-31 en febrero), el
 * cargo cae en el último día de ese mes — el día configurado no cambia: en
 * mayo vuelve al 31.
 */
object ScheduleCalculator {

    /** Primera ocurrencia de la regla que cae en [date] o después. */
    fun firstOnOrAfter(
        frequency: ScheduledChargeFrequency,
        dayOfMonth: Int,
        monthOfYear: Int?,
        date: LocalDate,
    ): LocalDate = when (frequency) {
        ScheduledChargeFrequency.MONTHLY -> {
            val candidate = clampedDate(YearMonth.from(date), dayOfMonth)
            if (candidate >= date) candidate else clampedDate(YearMonth.from(date).plusMonths(1), dayOfMonth)
        }

        ScheduledChargeFrequency.YEARLY -> {
            val month = requireNotNull(monthOfYear) { "monthOfYear es obligatorio para un cargo anual." }
            val candidate = clampedDate(YearMonth.of(date.year, month), dayOfMonth)
            if (candidate >= date) candidate else clampedDate(YearMonth.of(date.year + 1, month), dayOfMonth)
        }
    }

    /** Siguiente ocurrencia estrictamente posterior a [date]. */
    fun nextAfter(
        frequency: ScheduledChargeFrequency,
        dayOfMonth: Int,
        monthOfYear: Int?,
        date: LocalDate,
    ): LocalDate = firstOnOrAfter(frequency, dayOfMonth, monthOfYear, date.plusDays(1))

    private fun clampedDate(yearMonth: YearMonth, dayOfMonth: Int): LocalDate =
        yearMonth.atDay(minOf(dayOfMonth, yearMonth.lengthOfMonth()))
}
