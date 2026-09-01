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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
    fun `editar una tarjeta cambia nombre, banco, limite y dias de corte y pago`() {
        val auth = registerAndAuth("editartarjeta")
        val cardId = createCreditCard(auth, creditLimit = "50000")

        val response = mockMvc.perform(
            put("/api/v1/credit-cards/$cardId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"BBVA Oro","bank":"BBVA Bancomer","creditLimit":80000,"closingDay":20,"paymentDueDay":10}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals("BBVA Oro", JsonPath.read(response, "$.name"))
        assertEquals("BBVA Bancomer", JsonPath.read(response, "$.bank"))
        assertMoneyEquals("80000", JsonPath.read<Any>(response, "$.creditLimit").toString())
        assertEquals(20, JsonPath.read<Int>(response, "$.closingDay"))
        assertEquals(10, JsonPath.read<Int>(response, "$.paymentDueDay"))

        // El nombre de la Account subyacente (la que se lista en /accounts) queda sincronizado.
        val accountId = JsonPath.read<String>(response, "$.accountId")
        val account = mockMvc.perform(get("/api/v1/accounts/$accountId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals("BBVA Oro", JsonPath.read(account, "$.name"))
    }

    @Test
    fun `editar una compra normal ajusta la deuda por la diferencia`() {
        val auth = registerAndAuth("editarcompra")
        val cardId = createCreditCard(auth, creditLimit = "50000")

        val purchase = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":1000,"date":"2026-08-28","merchant":"Amazon"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val transactionId = JsonPath.read<String>(purchase, "$.id")

        val updated = mockMvc.perform(
            put("/api/v1/credit-cards/$cardId/purchases/$transactionId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":1500,"date":"2026-08-29","merchant":"Amazon Prime"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertMoneyEquals("1500", JsonPath.read<Any>(updated, "$.amount").toString())
        assertEquals("Amazon Prime", JsonPath.read(updated, "$.merchant"))

        val card = getCreditCard(auth, cardId)
        assertMoneyEquals("1500", JsonPath.read<Any>(card, "$.currentDebt").toString())
    }

    @Test
    fun `una compra a MSI no se puede editar por el endpoint de compra normal`() {
        val auth = registerAndAuth("editarmsibloqueado")
        val cardId = createCreditCard(auth, creditLimit = "50000")

        val plan = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":3000,"date":"2026-08-28","merchant":"Liverpool","installmentCount":3,"interestRate":0}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val transactionId = JsonPath.read<String>(plan, "$.transactionId")

        mockMvc.perform(
            put("/api/v1/credit-cards/$cardId/purchases/$transactionId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":4000,"date":"2026-08-28","merchant":"Liverpool"}""")
        ).andExpect(status().isBadRequest)
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
    fun `no se puede pagar una tarjeta desde una cuenta AFORE o PPR`() {
        val auth = registerAndAuth("noaforeppr")
        val cardId = createCreditCard(auth, creditLimit = "50000")
        val aforeAccountId = createAccount(auth, name = "Afore", type = "AFORE", openingBalance = "100000")

        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/payments")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$aforeAccountId","amount":100,"date":"2026-08-28"}""")
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
