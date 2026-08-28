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

    private fun dayInMonth(yearMonth: YearMonth, day: Int): LocalDate =
        yearMonth.atDay(day.coerceAtMost(yearMonth.lengthOfMonth()))
}
