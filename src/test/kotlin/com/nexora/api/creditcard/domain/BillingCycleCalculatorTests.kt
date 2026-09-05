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
    fun `la fecha limite de pago cae en el mes siguiente al cierre cuando el dia limite es menor o igual al de corte`() {
        val dueDate = calculator.paymentDueDateFor(closingDate = LocalDate.of(2026, 8, 15), closingDay = 15, paymentDueDay = 5)
        assertEquals(LocalDate.of(2026, 9, 5), dueDate)
    }

    @Test
    fun `la fecha limite de pago cae en el mismo mes del cierre cuando el dia limite es mayor al de corte`() {
        // Bug real: corte 1, límite 11 — antes del fix, esta fecha se calculaba
        // siempre "un mes después" (11 de septiembre en vez de 11 de agosto), lo
        // que hacía que el pago no apareciera como "próximo" hasta un mes tarde.
        val dueDate = calculator.paymentDueDateFor(closingDate = LocalDate.of(2026, 8, 1), closingDay = 1, paymentDueDay = 11)
        assertEquals(LocalDate.of(2026, 8, 11), dueDate)
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
    fun `con dia limite mayor al de corte, el pago del ciclo ya cerrado sigue siendo el mismo mes`() {
        // Caso real reportado: corte el 1, límite el 11. El 5 de septiembre (después
        // del corte del 1, antes del límite del 11) debe seguir mostrando el pago
        // pendiente con fecha límite 11 de SEPTIEMBRE, no 11 de octubre.
        val cycle = calculator.currentPaymentCycle(closingDay = 1, paymentDueDay = 11, date = LocalDate.of(2026, 9, 5))
        assertEquals(LocalDate.of(2026, 9, 1), cycle.closingDate)
        assertEquals(LocalDate.of(2026, 9, 11), cycle.paymentDueDate)
        assertTrue(cycle.isGracePeriod)
    }

    @Test
    fun `mucho antes del proximo corte, el ciclo relevante es el que viene, no hay periodo de gracia`() {
        val cycle = calculator.currentPaymentCycle(closingDay = 27, paymentDueDay = 7, date = LocalDate.of(2026, 8, 10))
        assertEquals(LocalDate.of(2026, 8, 27), cycle.closingDate)
        assertEquals(LocalDate.of(2026, 9, 7), cycle.paymentDueDate)
        assertFalse(cycle.isGracePeriod)
    }
}
