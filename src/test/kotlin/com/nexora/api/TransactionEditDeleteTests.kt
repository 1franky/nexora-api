package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.support.registerAndAuthenticate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Editar y borrar movimientos (nota #1 del backlog: la página de Movimientos
 * de nexora-web y la pestaña equivalente de nexora-android solo permitían
 * crear, nunca editar ni borrar). Solo INCOME/EXPENSE se editan por aquí —
 * TRANSFER, CREDIT_CARD_PURCHASE y CREDIT_CARD_PAYMENT ya tienen (o, en el
 * caso de PAYMENT, deliberadamente no tienen) su propio flujo, ver los
 * comentarios en TransactionService.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TransactionEditDeleteTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `editar un gasto ajusta el saldo de la cuenta por la diferencia`() {
        val auth = registerAndAuth("editgasto")
        val accountId = createAccount(auth, "Débito", "10000")
        val transactionId = recordExpense(auth, accountId, "300")

        mockMvc.perform(
            put("/api/v1/transactions/$transactionId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":500,"date":"2026-08-28","description":"editado"}""")
        ).andExpect(status().isOk)

        val accountResponse = mockMvc.perform(get("/api/v1/accounts/$accountId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        // 10000 - 500 = 9500, no 10000 - 300 - 500 (el delta se aplica, no se suma el nuevo monto tal cual).
        assertMoneyEquals("9500", JsonPath.read<Any>(accountResponse, "$.balance").toString())
    }

    @Test
    fun `borrar un ingreso revierte el saldo de la cuenta`() {
        val auth = registerAndAuth("delingreso")
        val accountId = createAccount(auth, "Débito", "1000")
        val transactionId = recordIncome(auth, accountId, "400")

        mockMvc.perform(delete("/api/v1/transactions/$transactionId").with(auth))
            .andExpect(status().isNoContent)

        val accountResponse = mockMvc.perform(get("/api/v1/accounts/$accountId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertMoneyEquals("1000", JsonPath.read<Any>(accountResponse, "$.balance").toString())

        mockMvc.perform(get("/api/v1/transactions").with(auth).param("accountId", accountId))
            .andExpect(status().isOk)
    }

    @Test
    fun `borrar una transferencia revierte ambas cuentas y borra las dos piernas`() {
        val auth = registerAndAuth("deltransfer")
        val fromId = createAccount(auth, "Débito", "5000")
        val toId = createAccount(auth, "Ahorro", "1000")

        val transferResponse = mockMvc.perform(
            post("/api/v1/transfers")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$fromId","toAccountId":"$toId","amount":800,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val outgoingId = JsonPath.read<String>(transferResponse, "$.outgoing.id")

        mockMvc.perform(delete("/api/v1/transactions/$outgoingId").with(auth))
            .andExpect(status().isNoContent)

        val fromResponse = mockMvc.perform(get("/api/v1/accounts/$fromId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val toResponse = mockMvc.perform(get("/api/v1/accounts/$toId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertMoneyEquals("5000", JsonPath.read<Any>(fromResponse, "$.balance").toString())
        assertMoneyEquals("1000", JsonPath.read<Any>(toResponse, "$.balance").toString())

        val remaining = mockMvc.perform(get("/api/v1/transactions").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(0, JsonPath.read<Int>(remaining, "$.length()"))
    }

    @Test
    fun `borrar una compra de tarjeta revierte la deuda`() {
        val auth = registerAndAuth("delcompra")
        val cardId = createCreditCard(auth)
        val purchaseResponse = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":850,"date":"2026-08-28","merchant":"OXXO"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val purchaseId = JsonPath.read<String>(purchaseResponse, "$.id")

        mockMvc.perform(delete("/api/v1/transactions/$purchaseId").with(auth))
            .andExpect(status().isNoContent)

        val card = getCreditCard(auth, cardId)
        assertMoneyEquals("0", JsonPath.read<Any>(card, "$.currentDebt").toString())
    }

    @Test
    fun `una compra ligada a un plan MSI-MCI no se puede borrar como compra suelta`() {
        val auth = registerAndAuth("delplanligado")
        val cardId = createCreditCard(auth)
        val planResponse = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":12000,"date":"2026-08-28","merchant":"Liverpool","installmentCount":12,"interestRate":0}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val transactionId = JsonPath.read<String>(planResponse, "$.transactionId")

        mockMvc.perform(delete("/api/v1/transactions/$transactionId").with(auth))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `un pago de tarjeta no se puede borrar`() {
        val auth = registerAndAuth("delpago")
        val cardId = createCreditCard(auth)
        val bankAccountId = createAccount(auth, "Débito", "10000")
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":1000,"date":"2026-08-28","merchant":"Amazon"}""")
        ).andExpect(status().isCreated)
        val paymentResponse = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/payments")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$bankAccountId","amount":300,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val outgoingId = JsonPath.read<String>(paymentResponse, "$.outgoing.id")

        mockMvc.perform(delete("/api/v1/transactions/$outgoingId").with(auth))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `una transferencia no se puede editar por el endpoint de movimientos simples`() {
        val auth = registerAndAuth("editrechazada")
        val fromId = createAccount(auth, "Débito", "5000")
        val toId = createAccount(auth, "Ahorro", "1000")
        val transferResponse = mockMvc.perform(
            post("/api/v1/transfers")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$fromId","toAccountId":"$toId","amount":800,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val outgoingId = JsonPath.read<String>(transferResponse, "$.outgoing.id")

        mockMvc.perform(
            put("/api/v1/transactions/$outgoingId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":900,"date":"2026-08-28"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `un usuario no puede editar ni borrar el movimiento de otro usuario`() {
        val ownerAuth = registerAndAuth("movowner")
        val intruderAuth = registerAndAuth("movintruder")
        val accountId = createAccount(ownerAuth, "Débito", "1000")
        val transactionId = recordExpense(ownerAuth, accountId, "300")

        mockMvc.perform(
            put("/api/v1/transactions/$transactionId")
                .with(intruderAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":500,"date":"2026-08-28"}""")
        ).andExpect(status().isNotFound)

        mockMvc.perform(delete("/api/v1/transactions/$transactionId").with(intruderAuth))
            .andExpect(status().isNotFound)
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): RequestPostProcessor = mockMvc.registerAndAuthenticate(prefix)

    private fun createAccount(auth: RequestPostProcessor, name: String, openingBalance: String): String {
        val response = mockMvc.perform(
            post("/api/v1/accounts")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"DEBIT","currency":"MXN","openingBalance":$openingBalance}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun recordExpense(auth: RequestPostProcessor, accountId: String, amount: String): String {
        val response = mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"EXPENSE","accountId":"$accountId","amount":$amount,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun recordIncome(auth: RequestPostProcessor, accountId: String, amount: String): String {
        val response = mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"INCOME","accountId":"$accountId","amount":$amount,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun createCreditCard(auth: RequestPostProcessor): String {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"BBVA Azul","bank":"BBVA","last4":"1234","creditLimit":50000,"closingDay":15,"paymentDueDay":5,"currency":"MXN"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun getCreditCard(auth: RequestPostProcessor, cardId: String): String =
        mockMvc.perform(get("/api/v1/credit-cards/$cardId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun assertMoneyEquals(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "esperado $expected pero fue $actual")
    }
}
