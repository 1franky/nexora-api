package com.nexora.api.creditcard.domain

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.YearMonth

/**
 * Calcula fechas de corte y de pago a partir del día de corte y el día
 * límite de pago de una tarjeta (plan.md, sección 7 "Ciclo de facturación
 * de tarjetas"). El día límite de pago siempre cae en el mes siguiente al
 * cierre del ciclo (ej. corte el 15, pago límite el 5 del mes siguiente).
 *
 * Los días 29-31 se ajustan al último día del mes cuando este es más corto
 * (ej. día de corte 31 en febrero -> 28 o 29 de febrero).
 */
@Component
class BillingCycleCalculator {

    /**
     * Fecha de corte del ciclo al que pertenece [date]: la primera fecha de
     * corte que es igual o posterior a [date]. Sirve tanto para "próxima
     * fecha de corte" (pasando la fecha de hoy) como para determinar a qué
     * ciclo pertenece una compra ya realizada (pasando su fecha).
     */
    fun closingDateOnOrAfter(closingDay: Int, date: LocalDate): LocalDate {
        val closingThisMonth = dayInMonth(YearMonth.from(date), closingDay)
        return if (!date.isAfter(closingThisMonth)) {
            closingThisMonth
        } else {
            dayInMonth(YearMonth.from(date).plusMonths(1), closingDay)
        }
    }

    /** Fecha límite de pago correspondiente a un ciclo que cierra en [closingDate]. */
    fun paymentDueDateFor(closingDate: LocalDate, paymentDueDay: Int): LocalDate =
        dayInMonth(YearMonth.from(closingDate).plusMonths(1), paymentDueDay)

    /**
     * El ciclo que de verdad corresponde pagar en [date] — a diferencia de
     * [closingDateOnOrAfter], que siempre mira hacia adelante (correcto
     * para "a qué ciclo pertenece una compra nueva", pero no para "cuál es
     * mi próximo pago"). Si [date] cae en el *período de gracia* de un
     * ciclo que ya cerró — después de su corte, pero antes de su propia
     * fecha límite de pago — ese ciclo ya cerrado es el que urge pagar, no
     * el que cierra más adelante: con corte 27 y pago 7, el día 30 todavía
     * debe el corte del 27 (pago límite el 7 del mes siguiente), no el
     * corte del 27 del mes que sigue.
     *
     * [RelevantCycle.isGracePeriod] le dice al llamador que además debe
     * calcular el ciclo que cierra más adelante como respaldo (ver
     * DashboardService.upcomingPayments): si resulta que no queda nada
     * pendiente de *este* ciclo de gracia — todo lo debido es más nuevo
     * que su corte — el ciclo que sigue es el que en realidad importa.
     */
    fun currentPaymentCycle(closingDay: Int, paymentDueDay: Int, date: LocalDate): RelevantCycle {
        val upcomingClosing = closingDateOnOrAfter(closingDay, date)
        val previousClosing = dayInMonth(YearMonth.from(upcomingClosing).minusMonths(1), closingDay)
        val previousDueDate = paymentDueDateFor(previousClosing, paymentDueDay)
        return if (!date.isAfter(previousDueDate)) {
            RelevantCycle(previousClosing, previousDueDate, isGracePeriod = true)
        } else {
            RelevantCycle(upcomingClosing, paymentDueDateFor(upcomingClosing, paymentDueDay), isGracePeriod = false)
        }
    }

    private fun dayInMonth(yearMonth: YearMonth, day: Int): LocalDate =
        yearMonth.atDay(day.coerceAtMost(yearMonth.lengthOfMonth()))
}

data class RelevantCycle(val closingDate: LocalDate, val paymentDueDate: LocalDate, val isGracePeriod: Boolean)
