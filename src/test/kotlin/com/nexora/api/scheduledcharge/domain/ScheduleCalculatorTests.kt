package com.nexora.api.scheduledcharge.domain

import com.nexora.api.scheduledcharge.domain.ScheduledChargeFrequency.MONTHLY
import com.nexora.api.scheduledcharge.domain.ScheduledChargeFrequency.YEARLY
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class ScheduleCalculatorTests {

    @Test
    fun `mensual - si el dia aun no pasa en el mes, cae en ese mes`() {
        assertEquals(LocalDate.of(2026, 10, 15), ScheduleCalculator.firstOnOrAfter(MONTHLY, 15, null, LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `mensual - la misma fecha cuenta como ocurrencia`() {
        assertEquals(LocalDate.of(2026, 10, 15), ScheduleCalculator.firstOnOrAfter(MONTHLY, 15, null, LocalDate.of(2026, 10, 15)))
    }

    @Test
    fun `mensual - si el dia ya paso, cae en el mes siguiente`() {
        assertEquals(LocalDate.of(2026, 11, 15), ScheduleCalculator.firstOnOrAfter(MONTHLY, 15, null, LocalDate.of(2026, 10, 16)))
    }

    @Test
    fun `mensual - cruza de diciembre a enero`() {
        assertEquals(LocalDate.of(2027, 1, 15), ScheduleCalculator.nextAfter(MONTHLY, 15, null, LocalDate.of(2026, 12, 15)))
    }

    @Test
    fun `dia 31 cae en el ultimo dia de los meses cortos y vuelve al 31 despues`() {
        var date = ScheduleCalculator.firstOnOrAfter(MONTHLY, 31, null, LocalDate.of(2027, 1, 1))
        val dates = mutableListOf(date)
        repeat(4) {
            date = ScheduleCalculator.nextAfter(MONTHLY, 31, null, date)
            dates += date
        }
        assertEquals(
            listOf(
                LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 3, 31),
                LocalDate.of(2027, 4, 30),
                LocalDate.of(2027, 5, 31),
            ),
            dates,
        )
    }

    @Test
    fun `dia 30 en febrero de un año bisiesto cae el 29`() {
        assertEquals(LocalDate.of(2028, 2, 29), ScheduleCalculator.nextAfter(MONTHLY, 30, null, LocalDate.of(2028, 1, 30)))
    }

    @Test
    fun `anual - si la fecha ya paso este año, cae el año siguiente`() {
        assertEquals(LocalDate.of(2027, 3, 3), ScheduleCalculator.firstOnOrAfter(YEARLY, 3, 3, LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `anual - si la fecha aun no pasa este año, cae este año`() {
        assertEquals(LocalDate.of(2026, 12, 1), ScheduleCalculator.firstOnOrAfter(YEARLY, 1, 12, LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `anual - 29 de febrero cae el 28 en años no bisiestos`() {
        assertEquals(LocalDate.of(2027, 2, 28), ScheduleCalculator.nextAfter(YEARLY, 29, 2, LocalDate.of(2026, 3, 1)))
        assertEquals(LocalDate.of(2028, 2, 29), ScheduleCalculator.nextAfter(YEARLY, 29, 2, LocalDate.of(2027, 2, 28)))
    }
}
