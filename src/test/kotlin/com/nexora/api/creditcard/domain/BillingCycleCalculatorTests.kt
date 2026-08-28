package com.nexora.api.creditcard.domain

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class BillingCycleCalculatorTests {

    private val calculator = BillingCycleCalculator()

    @Test
    fun `una compra antes o en el dia de corte pertenece al ciclo que cierra ese mes`() {
        // plan.md, sección 7: corte el 15; una compra el 14 de agosto pertenece al ciclo por cerrar.
        val closing = calculator.closingDateOnOrAfter(closingDay = 15, date = LocalDate.of(2026, 8, 14))
        assertEquals(LocalDate.of(2026, 8, 15), closing)
    }

    @Test
    fun `una compra despues del dia de corte pertenece al ciclo siguiente`() {
        // plan.md, sección 7: una compra el 16 de agosto pertenece al ciclo siguiente.
        val closing = calculator.closingDateOnOrAfter(closingDay = 15, date = LocalDate.of(2026, 8, 16))
        assertEquals(LocalDate.of(2026, 9, 15), closing)
    }

    @Test
    fun `una compra justo el dia de corte pertenece al ciclo que cierra ese dia`() {
        val closing = calculator.closingDateOnOrAfter(closingDay = 15, date = LocalDate.of(2026, 8, 15))
        assertEquals(LocalDate.of(2026, 8, 15), closing)
    }

    @Test
    fun `la fecha limite de pago cae en el mes siguiente al cierre`() {
        val dueDate = calculator.paymentDueDateFor(closingDate = LocalDate.of(2026, 8, 15), paymentDueDay = 5)
        assertEquals(LocalDate.of(2026, 9, 5), dueDate)
    }

    @Test
    fun `el corte cruza de diciembre a enero correctamente`() {
        val closing = calculator.closingDateOnOrAfter(closingDay = 15, date = LocalDate.of(2026, 12, 20))
        assertEquals(LocalDate.of(2027, 1, 15), closing)
    }
}
