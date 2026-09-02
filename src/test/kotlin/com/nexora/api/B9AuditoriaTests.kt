package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.account.domain.AccountRepository
import com.nexora.api.support.registerAuthenticateAndGetUserId
import com.nexora.api.user.domain.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pruebas de integración de la auditoría (plan.md, sección 13, issue #7
 * de nexora-api): created_by por entidad + audit_log de eventos
 * financieros.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B9AuditoriaTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var accountRepository: AccountRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Test
    fun `crear una cuenta autenticado registra quien la creo`() {
        val (auth, userId) = mockMvc.registerAuthenticateAndGetUserId("createdby")
        val accountId = createAccount(auth, "Débito", "1000")

        val account = accountRepository.findByIdAndUserId(UUID.fromString(accountId), userId)
        assertEquals(userId, account?.createdBy)
    }

    @Test
    fun `el auto-registro de un usuario no tiene created_by`() {
        // No hay Authorization en la request de POST /users — todavía no existe la sesión.
        val response = mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"selfreg+${System.nanoTime()}@nexora.test","password":"password123","displayName":"Self"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val userId = UUID.fromString(JsonPath.read(response, "$.id"))

        // created_by no se expone por API (es interno) — se valida directo en la fila.
        val user = userRepository.findById(userId).orElseThrow()
        assertNull(user.createdBy)
    }

    @Test
    fun `registrar un ingreso deja un evento TRANSACTION_CREATED en el historial`() {
        val auth = registerAndAuth("auditingreso")
        val accountId = createAccount(auth, "Débito", "1000")

        mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"INCOME","accountId":"$accountId","amount":500,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated)

        val log = getAuditLog(auth)
        assertEquals(1, JsonPath.read<Int>(log, "$.length()"))
        assertEquals("TRANSACTION_CREATED", JsonPath.read(log, "$[0].eventType"))
        assertTrue(JsonPath.read<String>(log, "$[0].summary").contains("500.00"))
    }

    @Test
    fun `editar y borrar un movimiento dejan sus propios eventos, mas recientes primero`() {
        val auth = registerAndAuth("auditeditdel")
        val accountId = createAccount(auth, "Débito", "1000")
        val txResponse = mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"EXPENSE","accountId":"$accountId","amount":300,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val txId = JsonPath.read<String>(txResponse, "$.id")

        mockMvc.perform(
            put("/api/v1/transactions/$txId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":350,"date":"2026-08-28"}""")
        ).andExpect(status().isOk)

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/transactions/$txId").with(auth))
            .andExpect(status().isNoContent)

        val log = getAuditLog(auth)
        assertEquals(3, JsonPath.read<Int>(log, "$.length()"))
        // Más recientes primero: borrado, editado, creado.
        assertEquals("TRANSACTION_DELETED", JsonPath.read(log, "$[0].eventType"))
        assertEquals("TRANSACTION_UPDATED", JsonPath.read(log, "$[1].eventType"))
        assertEquals("TRANSACTION_CREATED", JsonPath.read(log, "$[2].eventType"))
    }

    @Test
    fun `una compra a MSI deja un evento de compra y uno de plan, no uno por cuota`() {
        val auth = registerAndAuth("auditmsi")
        val cardId = createCreditCard(auth)

        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":12000,"date":"2026-08-28","merchant":"Liverpool","installmentCount":12,"interestRate":0}""")
        ).andExpect(status().isCreated)

        val log = getAuditLog(auth)
        // Exactamente 2: la compra (monto total) y el plan — no 12 (una por cuota).
        assertEquals(2, JsonPath.read<Int>(log, "$.length()"))
        val eventTypes = JsonPath.read<List<String>>(log, "$[*].eventType").toSet()
        assertEquals(setOf("CREDIT_CARD_PURCHASE_CREATED", "INSTALLMENT_PLAN_CREATED"), eventTypes)
    }

    @Test
    fun `pagar la tarjeta deja un evento PAYMENT_CREATED`() {
        val auth = registerAndAuth("auditpago")
        val cardId = createCreditCard(auth)
        val bankAccountId = createAccount(auth, "Débito", "10000")
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":1000,"date":"2026-08-28","merchant":"Amazon"}""")
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/payments")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$bankAccountId","amount":300,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated)

        val log = getAuditLog(auth)
        val eventTypes = JsonPath.read<List<String>>(log, "$[*].eventType")
        assertTrue(eventTypes.contains("PAYMENT_CREATED"))
    }

    @Test
    fun `un usuario no ve el historial de auditoria de otro`() {
        val ownerAuth = registerAndAuth("auditowner")
        val ownerAccountId = createAccount(ownerAuth, "Ahorro", "0")
        mockMvc.perform(
            post("/api/v1/transactions")
                .with(ownerAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"INCOME","accountId":"$ownerAccountId","amount":100,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated)

        val intruderAuth = registerAndAuth("auditintruder")
        val log = getAuditLog(intruderAuth)
        assertEquals(0, JsonPath.read<Int>(log, "$.length()"))
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): RequestPostProcessor = mockMvc.registerAuthenticateAndGetUserId(prefix).first

    private fun createAccount(auth: RequestPostProcessor, name: String, openingBalance: String): String {
        val response = mockMvc.perform(
            post("/api/v1/accounts")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"DEBIT","currency":"MXN","openingBalance":$openingBalance}""")
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

    private fun getAuditLog(auth: RequestPostProcessor): String =
        mockMvc.perform(get("/api/v1/audit-log").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
}
