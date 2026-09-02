package com.nexora.api.creditcard.domain

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `dentro del periodo de gracia, el ciclo relevante es el que ya cerro`() {
        // Corte 27, pago límite 7: el día 30 (después del corte) todavía no llega
        // el 7 del mes siguiente (fecha límite de ESE corte) — el ciclo relevante
        // sigue siendo el que cerró el 27, no el que cerrará el 27 del mes que sigue.
        val cycle = calculator.currentPaymentCycle(closingDay = 27, paymentDueDay = 7, date = LocalDate.of(2026, 8, 30))
        assertEquals(LocalDate.of(2026, 8, 27), cycle.closingDate)
        assertEquals(LocalDate.of(2026, 9, 7), cycle.paymentDueDate)
        assertTrue(cycle.isGracePeriod)
    }

    @Test
    fun `justo en la fecha limite de pago todavia cuenta como periodo de gracia`() {
        val cycle = calculator.currentPaymentCycle(closingDay = 27, paymentDueDay = 7, date = LocalDate.of(2026, 9, 7))
        assertEquals(LocalDate.of(2026, 8, 27), cycle.closingDate)
        assertTrue(cycle.isGracePeriod)
    }

    @Test
    fun `un dia despues de la fecha limite de pago ya no es periodo de gracia`() {
        val cycle = calculator.currentPaymentCycle(closingDay = 27, paymentDueDay = 7, date = LocalDate.of(2026, 9, 8))
        assertEquals(LocalDate.of(2026, 9, 27), cycle.closingDate)
        assertEquals(LocalDate.of(2026, 10, 7), cycle.paymentDueDate)
        assertFalse(cycle.isGracePeriod)
    }

    @Test
    fun `mucho antes del proximo corte, el ciclo relevante es el que viene, no hay periodo de gracia`() {
        val cycle = calculator.currentPaymentCycle(closingDay = 27, paymentDueDay = 7, date = LocalDate.of(2026, 8, 10))
        assertEquals(LocalDate.of(2026, 8, 27), cycle.closingDate)
        assertEquals(LocalDate.of(2026, 9, 7), cycle.paymentDueDate)
        assertFalse(cycle.isGracePeriod)
    }
}
