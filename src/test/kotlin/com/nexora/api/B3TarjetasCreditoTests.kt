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

/**
 * Pruebas de integración de B3 (tarjetas de crédito, compras y pagos) de
 * punta a punta: HTTP -> seguridad -> servicio -> Postgres (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B3TarjetasCreditoTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `crear tarjeta y consultarla`() {
        val auth = registerAndAuth("tarjeta")
        val cardId = createCreditCard(auth, creditLimit = "50000")

        val response = mockMvc.perform(get("/api/v1/credit-cards/$cardId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("50000", JsonPath.read<Any>(response, "$.creditLimit").toString())
        assertMoneyEquals("0", JsonPath.read<Any>(response, "$.currentDebt").toString())
        assertMoneyEquals("50000", JsonPath.read<Any>(response, "$.availableCredit").toString())
    }

    @Test
    fun `una compra aumenta la deuda y reduce el credito disponible`() {
        val auth = registerAndAuth("compra")
        val cardId = createCreditCard(auth, creditLimit = "50000")

        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":8500,"date":"2026-08-28","merchant":"Amazon"}""")
        ).andExpect(status().isCreated)
            .andExpect { result ->
                assertEquals("CREDIT_CARD_PURCHASE", JsonPath.read(result.response.contentAsString, "$.type"))
                assertEquals("Amazon", JsonPath.read(result.response.contentAsString, "$.merchant"))
            }

        val card = getCreditCard(auth, cardId)
        assertMoneyEquals("8500", JsonPath.read<Any>(card, "$.currentDebt").toString())
        assertMoneyEquals("41500", JsonPath.read<Any>(card, "$.availableCredit").toString())
    }

    @Test
    fun `un pago reduce la deuda de la tarjeta y descuenta de la cuenta origen`() {
        val auth = registerAndAuth("pago")
        val cardId = createCreditCard(auth, creditLimit = "50000")
        val bankAccountId = createAccount(auth, name = "Débito", type = "DEBIT", openingBalance = "10000")

        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":8500,"date":"2026-08-28","merchant":"Amazon"}""")
        ).andExpect(status().isCreated)

        val paymentResponse = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/payments")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$bankAccountId","amount":3000,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        assertEquals("CREDIT_CARD_PAYMENT", JsonPath.read(paymentResponse, "$.outgoing.type"))
        assertEquals("CREDIT_CARD_PAYMENT", JsonPath.read(paymentResponse, "$.incoming.type"))

        val card = getCreditCard(auth, cardId)
        assertMoneyEquals("5500", JsonPath.read<Any>(card, "$.currentDebt").toString())

        val bankResponse = mockMvc.perform(get("/api/v1/accounts/$bankAccountId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertMoneyEquals("7000", JsonPath.read<Any>(bankResponse, "$.balance").toString())
    }

    @Test
    fun `no se puede pagar una tarjeta con otra tarjeta`() {
        val auth = registerAndAuth("nocardtocard")
        val cardId = createCreditCard(auth, creditLimit = "50000")
        val otherCardId = createCreditCard(auth, creditLimit = "20000", last4 = "9999")
        val otherCardAccountId = JsonPath.read<String>(getCreditCard(auth, otherCardId), "$.accountId")

        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/payments")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$otherCardAccountId","amount":100,"date":"2026-08-28"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `un usuario no puede ver la tarjeta de otro usuario`() {
        val ownerAuth = registerAndAuth("cardowner")
        val cardId = createCreditCard(ownerAuth, creditLimit = "50000")

        val intruderAuth = registerAndAuth("cardintruder")

        mockMvc.perform(get("/api/v1/credit-cards/$cardId").with(intruderAuth))
            .andExpect(status().isNotFound)
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): RequestPostProcessor = mockMvc.registerAndAuthenticate(prefix)

    private fun createCreditCard(auth: RequestPostProcessor, creditLimit: String, last4: String = "1234"): String {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"BBVA Azul","bank":"BBVA","last4":"$last4","creditLimit":$creditLimit,"closingDay":15,"paymentDueDay":5,"currency":"MXN"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun getCreditCard(auth: RequestPostProcessor, cardId: String): String =
        mockMvc.perform(get("/api/v1/credit-cards/$cardId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun createAccount(auth: RequestPostProcessor, name: String, type: String, openingBalance: String): String {
        val response = mockMvc.perform(
            post("/api/v1/accounts")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"$type","currency":"MXN","openingBalance":$openingBalance}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun assertMoneyEquals(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "esperado $expected pero fue $actual")
    }
}
