package com.nexora.api

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import com.nexora.api.support.registerAndAuthenticate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pruebas de integración de B4 (compras a MSI/MCI) de punta a punta: HTTP
 * -> seguridad -> servicio -> Postgres (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B4MsiMciTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `un plan MSI reproduce el ejemplo del plan - 12000 a 12 cuotas sin interes`() {
        val auth = registerAndAuth("msi")
        val cardId = createCreditCard(auth)

        val response = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"amount":12000,"date":"2026-08-28","merchant":"Liverpool","installmentCount":12,"interestRate":0}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        assertEquals("MSI", JsonPath.read(response, "$.planType"))
        assertMoneyEquals("0", JsonPath.read<Any>(response, "$.interestAmount").toString())
        assertMoneyEquals("12000", JsonPath.read<Any>(response, "$.totalAmount").toString())
        assertMoneyEquals("1000", JsonPath.read<Any>(response, "$.installmentAmount").toString())
        assertEquals(12, JsonPath.read(response, "$.installments.length()"))
        assertEquals(0, JsonPath.read<Int>(response, "$.installmentsPaid"))
        assertEquals(12, JsonPath.read<Int>(response, "$.installmentsPending"))
        assertMoneyEquals("12000", JsonPath.read<Any>(response, "$.financedBalance").toString())

        // La tarjeta (corte 15, pago 5) refleja el total desde el día 1.
        val card = getCreditCard(auth, cardId)
        assertMoneyEquals("12000", JsonPath.read<Any>(card, "$.currentDebt").toString())

        // Compra el 28 de agosto: pertenece al ciclo que cierra el 15 de septiembre -> pago límite 5 de octubre.
        assertEquals("2026-10-05", JsonPath.read(response, "$.installments[0].dueDate"))
        assertEquals("2026-11-05", JsonPath.read(response, "$.installments[1].dueDate"))
        assertEquals("2027-09-05", JsonPath.read(response, "$.endDate"))
    }

    @Test
    fun `un plan MCI calcula intereses y el total de cuotas suma el total financiado`() {
        val auth = registerAndAuth("mci")
        val cardId = createCreditCard(auth)

        val response = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"amount":10000,"date":"2026-08-28","merchant":"Best Buy","installmentCount":6,"interestRate":2}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        // interés = 10000 * 2% * 6 = 1200; total = 11200
        assertEquals("MCI", JsonPath.read(response, "$.planType"))
        assertMoneyEquals("1200", JsonPath.read<Any>(response, "$.interestAmount").toString())
        assertMoneyEquals("11200", JsonPath.read<Any>(response, "$.totalAmount").toString())

        val installmentAmounts = (0..5).map {
            BigDecimal(JsonPath.read<Any>(response, "$.installments[$it].amount").toString())
        }
        val sum = installmentAmounts.fold(BigDecimal.ZERO) { acc, a -> acc + a }
        assertEquals(0, BigDecimal("11200").compareTo(sum), "la suma de las cuotas debe ser igual al total financiado")
    }

    @Test
    fun `marcar una cuota como pagada actualiza el resumen del plan`() {
        val auth = registerAndAuth("pagarcuota")
        val cardId = createCreditCard(auth)
        val plan = createPlan(auth, cardId, amount = "3000", installments = 3)
        val planId = JsonPath.read<String>(plan, "$.id")
        val firstInstallmentId = JsonPath.read<String>(plan, "$.installments[0].id")

        val response = mockMvc.perform(
            post("/api/v1/installment-plans/$planId/installments/$firstInstallmentId/pay").with(auth)
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals("PAID", JsonPath.read(response, "$.installments[0].status"))
        assertEquals(1, JsonPath.read<Int>(response, "$.installmentsPaid"))
        assertEquals(2, JsonPath.read<Int>(response, "$.installmentsPending"))
        assertEquals(2, JsonPath.read<Int>(response, "$.nextInstallment.number"))
        assertMoneyEquals("2000", JsonPath.read<Any>(response, "$.financedBalance").toString())
    }

    @Test
    fun `pagar todas las cuotas completa el plan`() {
        val auth = registerAndAuth("completar")
        val cardId = createCreditCard(auth)
        val plan = createPlan(auth, cardId, amount = "2000", installments = 2)
        val planId = JsonPath.read<String>(plan, "$.id")
        val installment1 = JsonPath.read<String>(plan, "$.installments[0].id")
        val installment2 = JsonPath.read<String>(plan, "$.installments[1].id")

        mockMvc.perform(post("/api/v1/installment-plans/$planId/installments/$installment1/pay").with(auth))
            .andExpect(status().isOk)
        val response = mockMvc.perform(post("/api/v1/installment-plans/$planId/installments/$installment2/pay").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals("COMPLETED", JsonPath.read(response, "$.status"))
        assertNull(JsonPath.read<Any?>(response, "$.nextInstallment"))
    }

    @Test
    fun `no se puede pagar una cuota ya pagada`() {
        val auth = registerAndAuth("doblepago")
        val cardId = createCreditCard(auth)
        val plan = createPlan(auth, cardId, amount = "2000", installments = 2)
        val planId = JsonPath.read<String>(plan, "$.id")
        val installmentId = JsonPath.read<String>(plan, "$.installments[0].id")

        mockMvc.perform(post("/api/v1/installment-plans/$planId/installments/$installmentId/pay").with(auth))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/installment-plans/$planId/installments/$installmentId/pay").with(auth))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `un plan de una sola cuota es rechazado`() {
        val auth = registerAndAuth("unacuota")
        val cardId = createCreditCard(auth)

        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":1000,"date":"2026-08-28","merchant":"Tienda","installmentCount":1,"interestRate":0}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `un usuario no puede ver el plan de otro usuario`() {
        val ownerAuth = registerAndAuth("planowner")
        val cardId = createCreditCard(ownerAuth)
        val plan = createPlan(ownerAuth, cardId, amount = "2000", installments = 2)
        val planId = JsonPath.read<String>(plan, "$.id")

        val intruderAuth = registerAndAuth("planintruder")
        mockMvc.perform(get("/api/v1/installment-plans/$planId").with(intruderAuth))
            .andExpect(status().isNotFound)
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): RequestPostProcessor = mockMvc.registerAndAuthenticate(prefix)

    private fun createCreditCard(auth: RequestPostProcessor): String {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"BBVA Azul","bank":"BBVA","last4":"1234","creditLimit":100000,"closingDay":15,"paymentDueDay":5,"currency":"MXN"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun getCreditCard(auth: RequestPostProcessor, cardId: String): String =
        mockMvc.perform(get("/api/v1/credit-cards/$cardId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun createPlan(auth: RequestPostProcessor, cardId: String, amount: String, installments: Int): String =
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"amount":$amount,"date":"2026-08-28","merchant":"Tienda","installmentCount":$installments,"interestRate":0}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString

    private fun assertMoneyEquals(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "esperado $expected pero fue $actual")
    }
}
